package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dirty Checking 통합 테스트 (Task M2).
 *
 * <p>K2의 dirty BitSet + F2의 동적 SET UPDATE가 실제 DB에서 올바르게 작동하는지 검증한다.
 *
 * <ul>
 *   <li>단일 필드 변경 → UPDATE 발생</li>
 *   <li>다중 필드 변경 → SET에 두 컬럼 모두 포함</li>
 *   <li>변경 없음 → UPDATE 미발생 (DB 값 그대로)</li>
 * </ul>
 *
 * <p>characterization test 성격: K2/F2 구현 후 통합 시나리오를 비로소 코드로 박제.
 */
class DirtyCheckingIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // basiccrud와 DB 격리 — schema 충돌 방지
        return "jdbc:h2:mem:dirty;DB_CLOSE_DELAY=-1";
    }

    /**
     * 단일 필드 변경 시 flush에서 UPDATE가 발생하는지 검증.
     *
     * <p>1번째 트랜잭션: persist + flush → INSERT
     * <p>2번째 트랜잭션: find → name 변경 → flush → DB에서 변경 확인
     * <p>K2의 dirty BitSet 단일 비트 + F2의 SET name=? 검증.
     */
    @Test
    void single_dirty_field_generates_UPDATE_on_flush() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "original";
            u.email = "x@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: 단일 필드 변경 후 flush
        TransactionTemplate.execute(tm, () -> {
            TestUser u = em.find(TestUser.class, persisted.id);
            u.name = "updated_name";
            em.flush();
            String name = jdbc.queryForObject(
                    "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
            assertThat(name).isEqualTo("updated_name");
            return null;
        });
    }

    /**
     * 여러 필드 동시 변경 시 dirty BitSet에 다중 비트가 설정되어
     * UPDATE SET 절에 모든 변경 컬럼이 포함되는지 검증.
     *
     * <p>1번째 트랜잭션: persist + flush → INSERT
     * <p>2번째 트랜잭션: find → name + email 둘 다 변경 → flush → DB에서 두 값 모두 확인
     * <p>K2의 BitSet 다중 dirty + F2의 SET name=?, email=? 검증.
     */
    @Test
    void multiple_dirty_fields_generate_UPDATE() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "before";
            u.email = "before@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: name + email 둘 다 변경
        TransactionTemplate.execute(tm, () -> {
            TestUser u = em.find(TestUser.class, persisted.id);
            u.name = "after_name";
            u.email = "after@x.com";
            em.flush();
            return null;
        });

        // 변경 결과 확인 — 트랜잭션 밖에서 직접 JDBC로 검증
        String name = jdbc.queryForObject(
                "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
        String email = jdbc.queryForObject(
                "SELECT email FROM test_users WHERE id = ?", String.class, persisted.id);
        assertThat(name).isEqualTo("after_name");
        assertThat(email).isEqualTo("after@x.com");
    }

    /**
     * 엔티티를 수정하지 않고 flush 호출 시 UPDATE가 발생하지 않아
     * DB 값이 원래 그대로 유지되는지 검증.
     *
     * <p>K2의 {@code if (!dirty.isEmpty())} 가드 검증.
     * JdbcTemplate spy 미사용 — "DB 값 변경 없음 + 예외 없음"으로 충분.
     */
    @Test
    void no_dirty_fields_skips_UPDATE() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "unchanged";
            u.email = "keep@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: find만 호출, 수정 없이 flush
        TransactionTemplate.execute(tm, () -> {
            TestUser u = em.find(TestUser.class, persisted.id);
            // 수정 없음 — dirty BitSet이 비어야 함
            em.flush();
            return null;
        });

        // DB 값이 원래와 동일한지 확인
        String name = jdbc.queryForObject(
                "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
        assertThat(name).isEqualTo("unchanged");
    }
}
