package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.support.LazyInterceptor;
import com.choisk.sfs.orm.support.LazyProxyFactory;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdentityMap 통합 테스트 (Task M2).
 *
 * <p>PersistenceContext.identityMap(1차 캐시)이 단일 SoT로서 올바르게 동작하는지 검증한다.
 *
 * <ul>
 *   <li>같은 트랜잭션 내 find 두 번 → 동일 인스턴스</li>
 *   <li>LAZY proxy가 로드된 target == 직접 find한 엔티티 (동일 인스턴스)</li>
 *   <li>다른 트랜잭션(별도 PersistenceContext) → 다른 인스턴스</li>
 * </ul>
 *
 * <p>학습 정점 ② 박제: "1 entity = 1 instance" 보장의 통합 안전망.
 * characterization test 성격: H1/J1 구현 후 통합 시나리오를 비로소 코드로 박제.
 */
class IdentityMapIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // basiccrud/dirty와 DB 격리 — schema 충돌 방지
        return "jdbc:h2:mem:identity;DB_CLOSE_DELAY=-1";
    }

    /**
     * 같은 트랜잭션 내에서 동일 PK로 find를 두 번 호출하면 동일 인스턴스를 반환하는지 검증.
     *
     * <p>1번째 트랜잭션: persist + flush → INSERT
     * <p>2번째 트랜잭션: find × 2 → isSameAs
     * <p>PersistenceContext.identityMap의 cache hit 검증 (학습 정점 ②).
     */
    @Test
    void find_twice_returns_same_instance() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "alice";
            u.email = "a@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: 같은 PK로 두 번 find → 동일 인스턴스
        TransactionTemplate.execute(tm, () -> {
            TestUser u1 = em.find(TestUser.class, persisted.id);
            TestUser u2 = em.find(TestUser.class, persisted.id);
            assertThat(u1).isSameAs(u2);
            return null;
        });
    }

    /**
     * LAZY proxy가 실제 로드된 target과 직접 find한 엔티티가 동일 인스턴스인지 검증.
     *
     * <p>1번째 트랜잭션: TestUser + TestOrder(LAZY user) persist + flush
     * <p>2번째 트랜잭션:
     * <ol>
     *   <li>TestOrder find → order.user는 LAZY proxy</li>
     *   <li>order.user.name 접근 → LazyInterceptor trigger → loader.load() 호출 → identityMap에 등록</li>
     *   <li>같은 트랜잭션에서 TestUser find → identityMap hit → 동일 인스턴스 반환</li>
     * </ol>
     *
     * <p>J1 LazyInterceptor fallback이 identityMap에 putEntity하는 경로 검증.
     */
    @Test
    void lazy_proxy_target_equals_directly_loaded_entity() {
        // 1번째 트랜잭션: TestUser + TestOrder INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "bob";
            u.email = "b@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.ONE;
            o.status = "NEW";
            em.persist(o);
            em.flush();

            return new Long[]{u.id, o.id};
        });
        Long userId = ids[0];
        Long orderId = ids[1];

        // 2번째 트랜잭션: LAZY proxy 초기화 → proxy target과 직접 find가 동일 인스턴스 검증
        TransactionTemplate.execute(tm, () -> {
            TestOrder order = em.find(TestOrder.class, orderId);
            // order.user는 LAZY proxy — 아직 target null

            // proxy getter 호출 → LazyInterceptor trigger → loader.load() → identityMap 등록
            // 반드시 public 메서드 호출로 lazy init trigger — 필드 직접 접근은 byte-buddy intercept 안 됨
            String loadedName = order.user.getName();
            assertThat(loadedName).isEqualTo("bob");

            // proxy 초기화 후 같은 트랜잭션에서 직접 find → identityMap hit → 동일 인스턴스
            TestUser direct = em.find(TestUser.class, userId);

            // proxy 자체(subclass 인스턴스)가 아니라 proxy 내부 LazyInterceptor.target()과 direct를 비교.
            // byte-buddy subclass proxy는 target(실제 엔티티)이 identityMap 기반이므로 isSameAs 성립.
            LazyInterceptor interceptor = extractInterceptor(order.user);
            assertThat(interceptor.target()).isSameAs(direct);

            return null;
        });
    }

    /**
     * byte-buddy proxy에서 LazyInterceptor를 추출한다.
     *
     * <p>proxy 내부의 {@code $$lazyInterceptor} 필드를 reflection으로 읽는다.
     * 이 메서드는 시나리오 5의 "proxy target == direct" assertion을 위해 사용된다.
     */
    private LazyInterceptor extractInterceptor(Object proxy) {
        try {
            Field f = proxy.getClass().getDeclaredField(LazyProxyFactory.INTERCEPTOR_FIELD);
            f.setAccessible(true);
            return (LazyInterceptor) f.get(proxy);
        } catch (Exception e) {
            throw new RuntimeException("LazyInterceptor 추출 실패", e);
        }
    }

    /**
     * 서로 다른 트랜잭션(다른 PersistenceContext)에서 같은 PK로 find하면 다른 인스턴스인지 검증.
     *
     * <p>트랜잭션 경계에서 PersistenceContext.close()가 호출되어 identityMap이 초기화된다.
     * 다음 트랜잭션은 새 PersistenceContext로 시작하므로 DB에서 새 인스턴스를 로드한다.
     *
     * <p>M1에서 afterCompletion → context.close() 콜백이 실제로 동작하는지 검증.
     */
    @Test
    void different_persistence_contexts_yield_different_instances() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "carol";
            u.email = "c@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: 새 PersistenceContext — DB에서 새 인스턴스 로드
        TestUser instance1 = TransactionTemplate.execute(tm, () ->
                em.find(TestUser.class, persisted.id));

        // 3번째 트랜잭션: 또 다른 새 PersistenceContext — 또 다른 인스턴스
        TestUser instance2 = TransactionTemplate.execute(tm, () ->
                em.find(TestUser.class, persisted.id));

        // 다른 트랜잭션 → 다른 인스턴스 (identity guarantee는 트랜잭션 범위 내에서만)
        assertThat(instance1).isNotSameAs(instance2);
        // 하지만 값은 동일
        assertThat(instance1.name).isEqualTo("carol");
        assertThat(instance2.name).isEqualTo("carol");
    }
}
