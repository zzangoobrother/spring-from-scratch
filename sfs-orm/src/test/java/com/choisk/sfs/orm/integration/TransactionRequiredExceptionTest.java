package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.exception.SfsTransactionRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SfsTransactionRequiredException 발생 통합 테스트 (Task M4).
 *
 * <p>L2의 {@link com.choisk.sfs.orm.boot.SfsTransactionalEntityManager#currentEm()} 구현 검증.
 * 트랜잭션 없이 EM 조작 시 {@link SfsTransactionRequiredException}이 발생하는 것을 박제.
 *
 * <p>spec § 6.1: "EM 조작 시 TX 없음" 예외 경로.
 * BasicCrudIntegrationTest에서 {@code persist}로 이미 한 번 검증했으나,
 * M4는 {@code find}로 검증 — 예외 발생 경로가 메서드 종류에 무관함을 박제.
 *
 * <p>characterization test 성격: L2 구현 선행 후 예외 발생 시나리오를 코드로 박제.
 */
class TransactionRequiredExceptionTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        return "jdbc:h2:mem:txrequired;DB_CLOSE_DELAY=-1";
    }

    /**
     * 활성 트랜잭션 없이 {@code em.find()} 호출 시 SfsTransactionRequiredException이 발생하는지 검증.
     *
     * <p>SfsTransactionalEntityManager.find() → currentEm() → TSM.isActualTransactionActive() == false
     * → SfsTransactionRequiredException throw. 트랜잭션이 없으면 어떤 EM 조작도 허용하지 않는다.
     */
    @Test
    void find_without_transaction_throws_SfsTransactionRequiredException() {
        // 트랜잭션 밖에서 직접 호출 — TSM에 활성 트랜잭션 없음
        assertThatThrownBy(() -> em.find(TestUser.class, 1L))
                .isInstanceOf(SfsTransactionRequiredException.class);
    }
}
