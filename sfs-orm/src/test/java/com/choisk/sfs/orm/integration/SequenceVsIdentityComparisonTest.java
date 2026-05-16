package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.support.InsertAction;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ① 4분면 최종 박제 — SEQUENCE(정상) vs IDENTITY(깨짐) 비교 통합 테스트.
 *
 * <p>단위 테스트(E1+E2+G1+G2)가 각 사분면을 개별 박제했다면,
 * 이 테스트는 *동일 통합 환경*에서 *DB row 존재 시점* 차이를 나란히 박제한다.
 *
 * <pre>
 *                   pre-insert (SEQUENCE)            post-insert (IDENTITY)
 * generator       │ NEXTVAL만, INSERT 없음           │ generate()가 INSERT 수행
 * persist()       │ actionQueue 등재 (write-behind) │ actionQueue 미등재 (이미 INSERT됨)
 * DB 행 존재 시점 │ flush 호출 후                    │ persist 호출 직후
 * </pre>
 *
 * <p>characterization test 성격: SEQUENCE/IDENTITY 구현이 이미 G1/G2에서 검증됨.
 * 통합 환경(H2 + 실제 TransactionManager + JdbcTemplate)에서의 DB row 시점 비교를 추가 박제.
 */
class SequenceVsIdentityComparisonTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // 다른 통합 테스트와 DB 격리 — schema 충돌 방지
        return "jdbc:h2:mem:seqvsidentity;DB_CLOSE_DELAY=-1";
    }

    /**
     * 학습 정점 ① 정상 박제 — SEQUENCE 전략: persist() 직후 id 확보, DB row는 flush 후 생성.
     *
     * <p>검증 포인트:
     * <ol>
     *   <li>persist() 직후 user.id != null (시퀀스에서 pre-insert로 채움)</li>
     *   <li>persist() 직후 em.contains(user) == true (1차 캐시 등재)</li>
     *   <li>persist() 직후 DB row 미존재 (write-behind — INSERT는 아직 없음)</li>
     *   <li>actionQueue에 InsertAction 1건 등재 (flush 시 실행 예정)</li>
     *   <li>flush() 후 DB row 존재 (write-behind 큐 소비)</li>
     * </ol>
     */
    @Test
    void SEQUENCE_persist_assigns_id_but_does_not_insert_until_flush() {
        TransactionTemplate.execute(tm, () -> {
            TestUser user = new TestUser();
            user.name = "seq_user";
            user.email = "seq@test.com";

            em.persist(user);

            // 검증 1: SEQUENCE pre-insert — id가 이미 채워져 있어야 함
            assertThat(user.getId())
                    .as("SEQUENCE 전략: persist() 직후 id는 시퀀스에서 미리 받음")
                    .isNotNull();

            // 검증 2: 1차 캐시에 등재되어야 함
            assertThat(em.contains(user))
                    .as("persist() 직후 1차 캐시(identityMap)에 등재")
                    .isTrue();

            // 검증 3: DB row 미존재 — write-behind, flush 전에는 INSERT 없음
            Long countBeforeFlush = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_users WHERE id = ?", Long.class, user.getId());
            assertThat(countBeforeFlush)
                    .as("SEQUENCE 정상 박제: persist() 직후 DB row = 0 (write-behind)")
                    .isEqualTo(0L);

            // 검증 4: actionQueue에 InsertAction 1건 등재
            RealEntityManager realEm = factoryBean.bindToCurrentTransaction();
            assertThat(realEm.context().actionQueue())
                    .as("SEQUENCE: actionQueue에 InsertAction이 등재되어야 함 (flush 시 실행 예정)")
                    .hasSize(1);
            assertThat(realEm.context().actionQueue().get(0))
                    .as("등재된 action은 InsertAction 타입")
                    .isInstanceOf(InsertAction.class);

            // flush 후: DB row 존재 확인
            em.flush();
            Long countAfterFlush = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_users WHERE id = ?", Long.class, user.getId());
            assertThat(countAfterFlush)
                    .as("SEQUENCE: flush() 후 DB row = 1 (write-behind 큐 소비)")
                    .isEqualTo(1L);

            return null;
        });
    }

    /**
     * 학습 정점 ① 깨짐 박제 — IDENTITY 전략: persist() 직후 즉시 INSERT, write-behind 깨짐.
     *
     * <p>검증 포인트:
     * <ol>
     *   <li>persist() 직후 order.id != null (post-insert로 generated key 즉시 주입)</li>
     *   <li>persist() 직후 DB row 즉시 존재 (write-behind 깨짐)</li>
     *   <li>actionQueue 비어있음 (이미 INSERT됨, flush 시 중복 INSERT 방지)</li>
     *   <li>flush() 호출 후에도 COUNT 여전히 1 (중복 INSERT 발생 안 함)</li>
     * </ol>
     */
    @Test
    void IDENTITY_persist_inserts_immediately_breaking_write_behind() {
        TransactionTemplate.execute(tm, () -> {
            TestOrder order = new TestOrder();
            order.amount = new java.math.BigDecimal("9900");
            order.status = "PENDING";

            em.persist(order);

            // 검증 1: IDENTITY post-insert — generated key가 entity.id에 즉시 주입
            assertThat(order.getId())
                    .as("IDENTITY 전략: persist() 직후 id는 generated key로 채워짐")
                    .isNotNull();

            // 검증 2: DB row 즉시 존재 — write-behind 깨짐 박제
            Long countAfterPersist = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_orders WHERE id = ?", Long.class, order.getId());
            assertThat(countAfterPersist)
                    .as("IDENTITY 깨짐 박제: persist() 직후 DB row = 1 (즉시 INSERT, write-behind 우회)")
                    .isEqualTo(1L);

            // 검증 3: actionQueue 비어있음 (flush 시 중복 INSERT 방지를 위해 미등재)
            RealEntityManager realEm = factoryBean.bindToCurrentTransaction();
            assertThat(realEm.context().actionQueue())
                    .as("IDENTITY: actionQueue 미등재 — 이미 INSERT됨, 중복 INSERT 방지")
                    .isEmpty();

            // 검증 4: flush() 호출이 추가 INSERT를 일으키지 않음 (COUNT 여전히 1)
            em.flush();
            Long countAfterFlush = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM test_orders WHERE id = ?", Long.class, order.getId());
            assertThat(countAfterFlush)
                    .as("IDENTITY: flush() 후에도 DB row = 1 (중복 INSERT 없음 — actionQueue 비어있었음)")
                    .isEqualTo(1L);

            return null;
        });
    }
}
