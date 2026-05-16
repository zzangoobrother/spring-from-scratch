package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LazyInitializationException 통합 테스트 (Task M3).
 *
 * <p>트랜잭션/EM 닫힌 후 LAZY proxy에 접근하면 {@link SfsLazyInitializationException}이
 * 발생하는지 검증한다. Hibernate의 동일 패턴 박제.
 *
 * <ul>
 *   <li>트랜잭션 종료 후 트랜잭션 밖에서 lazy 접근 → SfsLazyInitializationException</li>
 *   <li>트랜잭션 안이라도 em.close() 명시 호출 후 → SfsLazyInitializationException</li>
 * </ul>
 *
 * <p>학습 정점 ③ 박제: "LAZY proxy는 PersistenceContext가 열린 상태에서만 초기화 가능".
 * characterization test 성격: I2(LazyInterceptor closed 체크) 구현 후 통합 시나리오 박제.
 */
class LazyInitializationExceptionTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // 다른 통합 테스트 DB와 격리
        return "jdbc:h2:mem:lazyinit;DB_CLOSE_DELAY=-1";
    }

    /**
     * 트랜잭션 종료 후 트랜잭션 밖에서 LAZY proxy에 접근하면 SfsLazyInitializationException이 발생하는지 검증.
     *
     * <p>시나리오 DE (spec § 5.4):
     * <ol>
     *   <li>1번째 트랜잭션: TestUser + TestOrder(LAZY user) persist + flush</li>
     *   <li>2번째 트랜잭션: TestOrder find → order.user를 LAZY proxy인 채로 트랜잭션 밖으로 가져옴</li>
     *   <li>트랜잭션 종료 → afterCompletion 콜백 → PersistenceContext.close()</li>
     *   <li>트랜잭션 밖에서 order.user.getName() → context.isClosed() == true → 예외</li>
     * </ol>
     *
     * <p>박제 핵심: PersistenceContext 닫힌 후 lazy proxy 접근 시 Hibernate와 동일한 예외 패턴.
     */
    @Test
    void lazy_access_after_transaction_throws_SfsLazyInitializationException() {
        // 1번째 트랜잭션: TestUser + TestOrder INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "detached-eve";
            u.email = "eve@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(150);
            o.status = "NEW";
            em.persist(o);
            em.flush();

            return new Long[]{u.id, o.id};
        });
        Long orderId = ids[1];

        // 2번째 트랜잭션: TestOrder find 후 LAZY proxy를 트랜잭션 밖으로 노출
        // 트랜잭션 종료 후 PersistenceContext가 close됨
        TestOrder detachedOrder = TransactionTemplate.execute(tm, () ->
                em.find(TestOrder.class, orderId));

        // 트랜잭션 밖에서 LAZY proxy에 접근 — context.isClosed() == true → 예외 기대
        // spec § 5.4 시나리오 DE: "tx 밖에서 detached.getUser() 호출 → SfsLazyInitializationException"
        assertThatThrownBy(() -> detachedOrder.user.getName())
                .as("트랜잭션 종료 후 LAZY proxy 접근 시 SfsLazyInitializationException이 발생해야 한다")
                .isInstanceOf(SfsLazyInitializationException.class);
    }

    /**
     * 트랜잭션 안이라도 em.close()를 명시 호출한 후 LAZY proxy에 접근하면 예외가 발생하는지 검증.
     *
     * <p>일반적으로 사용자가 em.close()를 직접 호출하지 않으나 (SfsEntityManager 인터페이스에 없음),
     * SfsEntityManagerFactoryBean이 afterCompletion에서 em.context().close()를 호출하는 경로를 직접 시뮬레이션한다.
     * RealEntityManager를 factoryBean.bindToCurrentTransaction()으로 직접 획득해서 context를 닫는다.
     *
     * <p>PersistenceContext.close() 이후 LazyInterceptor.intercept()가
     * context.isClosed() == true를 감지해 SfsLazyInitializationException을 던지는지 검증.
     */
    @Test
    void lazy_access_after_context_close_throws_SfsLazyInitializationException() {
        // 1번째 트랜잭션: TestUser + TestOrder INSERT
        Long[] ids = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "closed-frank";
            u.email = "frank@x.com";
            em.persist(u);
            em.flush();

            TestOrder o = new TestOrder();
            o.user = u;
            o.amount = BigDecimal.valueOf(250);
            o.status = "PENDING";
            em.persist(o);
            em.flush();

            return new Long[]{u.id, o.id};
        });
        Long orderId = ids[1];

        // 2번째 트랜잭션: find 후 context를 명시 close → proxy 접근 시 예외 기대
        assertThatThrownBy(() ->
                TransactionTemplate.execute(tm, () -> {
                    TestOrder order = em.find(TestOrder.class, orderId);
                    // order.user는 LAZY proxy — 아직 target null

                    // 트랜잭션 안이지만 RealEntityManager.context()를 통해 명시적으로 close
                    RealEntityManager realEm = factoryBean.bindToCurrentTransaction();
                    realEm.context().close();

                    // context가 닫힌 후 LAZY proxy 메서드 접근 → SfsLazyInitializationException 기대
                    order.user.getName();
                    return null;
                }))
                .as("PersistenceContext.close() 후 LAZY proxy 접근 시 SfsLazyInitializationException이 발생해야 한다")
                .isInstanceOf(SfsLazyInitializationException.class);
    }
}
