package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BasicCrud 통합 테스트 (Task M1).
 *
 * <p>H2 in-memory DB + {@link TransactionTemplate}을 사용해 persist/find/remove/flush/TxRequired 5건 검증.
 * K2/K3/L1/L2에서 단위 테스트가 없었던 부분의 통합 안전망.
 *
 * <p>공통 인프라는 {@link AbstractOrmIntegrationTest}에서 제공.
 */
class BasicCrudIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        return "jdbc:h2:mem:basiccrud;DB_CLOSE_DELAY=-1";
    }

    /**
     * 같은 트랜잭션 내에서 persist 후 find 시 동일 인스턴스를 반환하는지 검증.
     * SEQUENCE 전략: persist 직후 id가 set됨 (G1 동작).
     */
    @Test
    void persist_and_find_returns_same_instance_in_same_transaction() {
        TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "alice";
            u.email = "a@x.com";
            em.persist(u);
            assertThat(em.find(TestUser.class, u.getId())).isSameAs(u);
            return null;
        });
    }

    /**
     * persist 후 flush 시 DB에 실제로 INSERT가 실행되는지 검증.
     */
    @Test
    void persist_then_flush_inserts_to_db() {
        TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "bob";
            u.email = "b@x.com";
            em.persist(u);
            em.flush();
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_users WHERE name = 'bob'", Long.class);
            assertThat(count).isEqualTo(1L);
            return null;
        });
    }

    /**
     * DB에 없는 ID로 find 시 null을 반환하는지 검증 (H1 동작).
     */
    @Test
    void find_returns_null_when_not_in_db() {
        TransactionTemplate.execute(tm, () -> {
            assertThat(em.find(TestUser.class, 9999L)).isNull();
            return null;
        });
    }

    /**
     * remove 후 flush 시 DB에서 실제로 DELETE가 실행되는지 검증.
     */
    @Test
    void remove_then_flush_deletes_from_db() {
        TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "to_delete";
            em.persist(u);
            em.flush();
            em.remove(u);
            em.flush();
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_users WHERE id = ?", Long.class, u.getId());
            assertThat(count).isEqualTo(0L);
            return null;
        });
    }

    /**
     * 트랜잭션 없이 persist 호출 시 SfsTransactionRequiredException이 발생하는지 검증 (L2 동작).
     */
    @Test
    void persist_without_transaction_throws_TransactionRequiredException() {
        assertThatThrownBy(() -> em.persist(new TestUser()))
                .isInstanceOf(com.choisk.sfs.orm.exception.SfsTransactionRequiredException.class);
    }
}
