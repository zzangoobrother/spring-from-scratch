package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.support.LazyInterceptor;
import com.choisk.sfs.orm.support.LazyProxyFactory;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lazy/Eager 로딩 통합 테스트 (Task M3).
 *
 * <p>J1 구현(LAZY proxy / EAGER 별도 SELECT)이 실제 DB와 함께 올바르게 동작하는지 검증한다.
 *
 * <ul>
 *   <li>LAZY 첫 호출 시 SELECT 발생 (proxy 초기화 후 실제 값 접근)</li>
 *   <li>EAGER 별도 SELECT 발생 (find() 시점에 즉시 로드)</li>
 *   <li>LAZY proxy의 getId()는 SELECT 없음 (PK getter는 인터셉트 제외)</li>
 *   <li>LAZY + EAGER 혼합 (같은 트랜잭션 내 두 관계 공존)</li>
 * </ul>
 *
 * <p>학습 정점 ③ 박제: "LAZY proxy는 메서드 호출 시 초기화, EAGER는 find() 즉시 SELECT".
 * characterization test 성격: J1 구현 후 통합 시나리오를 비로소 코드로 박제.
 */
class LazyLoadingIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // 다른 통합 테스트 DB와 격리 — schema 충돌 + 데이터 오염 방지
        return "jdbc:h2:mem:lazyloading;DB_CLOSE_DELAY=-1";
    }

    /**
     * LAZY proxy의 첫 getter 호출 시 실제 DB SELECT가 발생해 값을 로드하는지 검증.
     *
     * <p>1번째 트랜잭션: TestUser + TestOrder(LAZY user) persist + flush
     * <p>2번째 트랜잭션:
     * <ol>
     *   <li>TestOrder find → order.user는 LAZY proxy (아직 SELECT 없음)</li>
     *   <li>order.user.getName() 호출 → LazyInterceptor trigger → DB fallback SELECT</li>
     *   <li>로드된 name 값이 원래 값과 일치</li>
     * </ol>
     *
     * <p>박제 함정: byte-buddy proxy는 *메서드 호출*만 인터셉트. 필드 직접 접근은 lazy init 미발동.
     */
    @Test
    void lazy_first_method_call_loads_entity_from_db() {
        // 1번째 트랜잭션: TestUser + TestOrder INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "lazy-alice";
            u.email = "lazy-a@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(100);
            o.status = "NEW";
            em.persist(o);
            em.flush();

            return new Long[]{u.id, o.id};
        });
        Long orderId = ids[1];

        // 2번째 트랜잭션: LAZY proxy 초기화 검증
        TransactionTemplate.execute(tm, () -> {
            TestOrder order = em.find(TestOrder.class, orderId);
            // order.user는 LAZY proxy — 아직 target == null (SELECT 미발생)

            // 반드시 public getter로 proxy 초기화 trigger — 필드 직접 접근은 byte-buddy intercept 안 됨
            String name = order.user.getName();

            // 첫 getter 호출 후 DB에서 로드한 값이 원래 값과 일치
            assertThat(name).isEqualTo("lazy-alice");
            return null;
        });
    }

    /**
     * EAGER fetch 관계를 가진 엔티티를 find()할 때 별도 SELECT로 즉시 로드되는지 검증.
     *
     * <p>spec § 4.7: EAGER는 JOIN 아닌 별도 SELECT (단순성 우선).
     * TestAuditLog.order가 EAGER ManyToOne — find(TestAuditLog) 시점에 TestOrder도 SELECT.
     *
     * <p>1번째 트랜잭션: TestUser + TestOrder + TestAuditLog persist + flush
     * <p>2번째 트랜잭션: TestAuditLog find → log.order가 즉시 로드된 실제 인스턴스
     */
    @Test
    void eager_fetch_loads_related_entity_immediately_on_find() {
        // 1번째 트랜잭션: TestUser + TestOrder + TestAuditLog INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "eager-bob";
            u.email = "eager-b@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(200);
            o.status = "PAID";
            em.persist(o);
            em.flush();

            TestAuditLog log = new TestAuditLog();
            log.order = o;
            log.action = "ORDER_PLACED";
            em.persist(log);
            em.flush();

            return new Long[]{u.id, o.id, log.id};
        });
        Long logId = ids[2];
        Long orderId = ids[1];

        // 2번째 트랜잭션: EAGER 관계 즉시 로드 검증
        TransactionTemplate.execute(tm, () -> {
            TestAuditLog log = em.find(TestAuditLog.class, logId);

            // EAGER: find() 시점에 이미 로드됨 — proxy가 아닌 실제 TestOrder 인스턴스
            assertThat(log.order).isNotNull();
            // proxy 여부 검증: EAGER는 프록시 아니므로 $$lazyInterceptor 필드 없음
            assertThat(log.order.getClass().getSimpleName()).doesNotContain("$");
            // 실제 id 값이 정합한지 확인
            assertThat(log.order.getId()).isEqualTo(orderId);
            // action 값이 원래 값과 일치
            assertThat(log.action).isEqualTo("ORDER_PLACED");
            return null;
        });
    }

    /**
     * LAZY proxy의 PK getter(getId())는 lazy init 없이 즉시 반환되는지 검증.
     *
     * <p>LazyProxyFactory는 {@code getId...} 계열 메서드를 인터셉트에서 제외한다.
     * (byte-buddy ElementMatchers.not(named(pkGetterName)) 조건)
     * 따라서 getId()를 호출해도 LazyInterceptor.target은 여전히 null이어야 한다.
     *
     * <p>검증 방법: proxy 내부 LazyInterceptor.target()이 null임을 직접 확인.
     */
    @Test
    void lazy_proxy_getId_does_not_trigger_lazy_init() {
        // 1번째 트랜잭션: TestUser + TestOrder INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "pk-carol";
            u.email = "pk-c@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(50);
            o.status = "PENDING";
            em.persist(o);
            em.flush();

            return new Long[]{u.id, o.id};
        });
        Long userId = ids[0];
        Long orderId = ids[1];

        // 2번째 트랜잭션: PK getter 호출 시 lazy init 미발동 검증
        TransactionTemplate.execute(tm, () -> {
            TestOrder order = em.find(TestOrder.class, orderId);
            // order.user는 LAZY proxy

            // PK getter 호출 — 인터셉터에서 제외되므로 lazy init 없이 즉시 반환
            Long proxiedUserId = order.user.getId();
            assertThat(proxiedUserId).isEqualTo(userId);

            // LazyInterceptor.target은 여전히 null — lazy init이 발동하지 않은 증거
            LazyInterceptor interceptor = extractInterceptor(order.user);
            assertThat(interceptor.target())
                    .as("getId() 호출 후에도 lazy target은 null이어야 한다 (SELECT 없음)")
                    .isNull();

            return null;
        });
    }

    /**
     * LAZY proxy(TestOrder.user)와 EAGER 관계(TestAuditLog.order)가 같은 트랜잭션에서
     * 올바르게 공존하는지 검증.
     *
     * <p>1번째 트랜잭션: TestUser + TestOrder + TestAuditLog 모두 INSERT
     * <p>2번째 트랜잭션:
     * <ol>
     *   <li>TestAuditLog find → log.order는 EAGER로 즉시 로드</li>
     *   <li>log.order.user는 LAZY proxy (TestOrder.user)</li>
     *   <li>log.order.user.getName() → LAZY proxy 초기화 → DB SELECT</li>
     *   <li>값 정합 확인</li>
     * </ol>
     */
    @Test
    void lazy_and_eager_can_coexist_in_same_transaction() {
        // 1번째 트랜잭션: 3개 엔티티 모두 INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "mixed-dave";
            u.email = "mixed-d@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(300);
            o.status = "NEW";
            em.persist(o);
            em.flush();

            TestAuditLog log = new TestAuditLog();
            log.order = o;
            log.action = "PAYMENT_PROCESSED";
            em.persist(log);
            em.flush();

            return new Long[]{u.id, o.id, log.id};
        });
        Long logId = ids[2];

        // 2번째 트랜잭션: LAZY + EAGER 혼합 검증
        TransactionTemplate.execute(tm, () -> {
            // EAGER: TestAuditLog find → log.order 즉시 로드
            TestAuditLog log = em.find(TestAuditLog.class, logId);
            assertThat(log.order).isNotNull();
            assertThat(log.action).isEqualTo("PAYMENT_PROCESSED");

            // log.order.user는 TestOrder의 LAZY proxy — 아직 target null
            TestUser userProxy = log.order.getUser();
            assertThat(userProxy).isNotNull(); // proxy 인스턴스 자체는 존재
            LazyInterceptor interceptor = extractInterceptor(userProxy);
            assertThat(interceptor.target())
                    .as("getName() 호출 전 lazy target은 null이어야 한다")
                    .isNull();

            // LAZY proxy getter 호출 → lazy init 발동
            String name = userProxy.getName();
            assertThat(name).isEqualTo("mixed-dave");

            // 초기화 후 target이 세팅됨
            assertThat(interceptor.target()).isNotNull();
            return null;
        });
    }

    /**
     * byte-buddy proxy에서 LazyInterceptor를 추출한다.
     *
     * <p>proxy 내부의 {@code $$lazyInterceptor} 필드를 reflection으로 읽는다.
     * M2 IdentityMapIntegrationTest의 동일 패턴 재사용.
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
}
