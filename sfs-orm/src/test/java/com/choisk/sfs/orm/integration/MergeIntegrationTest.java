package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merge 통합 테스트 (Task M4).
 *
 * <p>K3의 {@link com.choisk.sfs.orm.RealEntityManager#merge(Object)} 구현을 실제 DB와 함께 검증.
 *
 * <ul>
 *   <li>시나리오 1: merge 정상 — return value 받아 contains() 확인 + find() 동일 인스턴스 보장</li>
 *   <li>시나리오 2: return 무시 함정 — merge 반환값 무시 + detached 변경 → flush 시 DB 변경 미발생</li>
 *   <li>시나리오 3: snapshot 갱신 검증 — merge 후 managed 인스턴스 변경이 dirty check로 감지 → UPDATE 발생</li>
 * </ul>
 *
 * <p>characterization test 성격: K3 구현 선행 후 시나리오를 코드로 박제.
 *
 * <p>설계 포인트: spec § 4.5의 merge flow에서 shallow copy 후 snapshot을 "merge 시점 상태"로 갱신.
 * 따라서 merge 자체만으로 dirty가 발생하지 않으며, managed에 추가 변경이 있어야만 UPDATE.
 * merge의 핵심 학습 가치는 시나리오 2 — return value를 무시하면 DB에 변경이 전혀 반영되지 않음.
 */
class MergeIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        return "jdbc:h2:mem:merge;DB_CLOSE_DELAY=-1";
    }

    /**
     * merge 정상 시나리오 — return value를 받은 managed 인스턴스가 1차 캐시에 등재되어 있음을 검증.
     *
     * <p>spec § 4.5: merge()는 identityMap에 등재된 managed 인스턴스를 반환한다.
     * 반환된 managed는 contains() 검사에서 true이고, find()에서 동일 인스턴스(==)를 반환한다.
     *
     * <p>주의: merge 후 snapshot이 shallow copy 시점으로 갱신되므로,
     * merge 자체만으로는 dirty가 발생하지 않는다 (추가 변경이 있어야 UPDATE).
     *
     * <p>흐름:
     * <ol>
     *   <li>1번째 트랜잭션: persist + flush → INSERT</li>
     *   <li>2번째 트랜잭션: merge(detached) return 받음 → contains(managed) == true + find() == managed</li>
     * </ol>
     */
    @Test
    void merge_return_value_is_managed_and_in_identity_map() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "original";
            u.email = "original@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: merge return 받아 managed 상태 확인
        TransactionTemplate.execute(tm, () -> {
            TestUser detached = new TestUser();
            detached.id = persisted.id;
            detached.name = "from_detached";
            detached.email = "new@x.com";

            // return value를 받아 managed 인스턴스 사용 — 핵심
            TestUser managed = em.merge(detached);

            // managed는 1차 캐시에 등재된 상태여야 함
            assertThat(em.contains(managed)).isTrue();

            // find()로 다시 조회하면 동일 인스턴스 반환 (identity 보장)
            TestUser found = em.find(TestUser.class, persisted.id);
            assertThat(found).isSameAs(managed);

            return null;
        });
    }

    /**
     * return 무시 함정 박제 — merge 반환값을 무시하고 detached 인스턴스에만 변경하면 flush 후 DB 변경 없음.
     *
     * <p>이것이 {@code T merge(T)} 시그니처가 return value를 강제하는 이유.
     *
     * <p>spec § 4.5 함정 박제:
     * {@code em.merge(detached); detached.name = "changed";} 패턴.
     * merge()는 managed 인스턴스를 반환하며, detached는 여전히 detached 상태.
     * merge 후 snapshot도 shallow copy 시점 상태로 갱신되므로,
     * detached 변경은 dirty check 대상 밖 — flush 후 DB는 원본 그대로.
     *
     * <p>흐름:
     * <ol>
     *   <li>1번째 트랜잭션: persist + flush → INSERT (name="before_merge")</li>
     *   <li>2번째 트랜잭션: merge(detached, name="before_merge") — return 무시 → detached.name 변경 → flush → DB 그대로</li>
     * </ol>
     */
    @Test
    void merge_without_using_return_value_loses_subsequent_changes() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "before_merge";
            u.email = "trap@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: merge — return 무시 (함정)
        TransactionTemplate.execute(tm, () -> {
            TestUser detached = new TestUser();
            detached.id = persisted.id;
            // detached.name도 원본과 같은 "before_merge"로 설정 (merge 자체로는 변경 없음)
            detached.name = "before_merge";
            detached.email = "trap@x.com";

            // merge 호출하되 return value 무시 — 함정 시나리오
            // managed가 1차 캐시에 등재되나, 호출자는 detached만 가지고 있음
            em.merge(detached);

            // detached 인스턴스에 추가 변경 — 1차 캐시 밖, dirty check 대상 아님
            // 이 변경은 DB에 전혀 반영되지 않는다
            detached.name = "trap_change_after_merge";
            em.flush();

            return null;
        });

        // DB 값 확인 — detached 변경이 사라짐: DB는 원본 "before_merge" 그대로
        String name = jdbc.queryForObject(
                "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
        // 학습 정점: return 무시 + detached 변경 → DB 반영 안 됨
        assertThat(name).isEqualTo("before_merge");
    }

    /**
     * snapshot 갱신 검증 — merge 후 managed 인스턴스를 통한 추가 변경이 dirty check로 감지되어 UPDATE 발생.
     *
     * <p>merge()는 내부적으로 snapshot을 갱신(putSnapshot)한다.
     * 이후 managed 인스턴스에 가한 변경은 갱신된 snapshot과 비교되어 dirty가 잡힌다.
     *
     * <p>흐름:
     * <ol>
     *   <li>1번째 트랜잭션: persist + flush → INSERT</li>
     *   <li>2번째 트랜잭션: merge → managed.name 추가 변경 → flush → DB에 추가 변경 반영 확인</li>
     * </ol>
     */
    @Test
    void merge_then_modify_managed_triggers_dirty_check() {
        // 1번째 트랜잭션: INSERT
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "step1";
            u.email = "snap@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: merge 후 managed 인스턴스에 추가 변경
        TransactionTemplate.execute(tm, () -> {
            TestUser detached = new TestUser();
            detached.id = persisted.id;
            detached.name = "step2_from_merge";
            detached.email = "snap@x.com";

            // merge → managed 인스턴스 반환 (snapshot도 함께 갱신됨)
            TestUser managed = em.merge(detached);

            // managed에 추가 변경 — merge 갱신된 snapshot 기준으로 dirty 감지 대상
            managed.name = "step3_post_merge_change";

            em.flush();  // dirty check → "step3_post_merge_change" UPDATE 발생

            String name = jdbc.queryForObject(
                    "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
            assertThat(name).isEqualTo("step3_post_merge_change");
            return null;
        });
    }
}
