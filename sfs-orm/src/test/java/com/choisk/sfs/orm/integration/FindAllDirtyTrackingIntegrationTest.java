package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findAll() 로드 entity의 dirty-tracking 일관성 통합 테스트 (Task G2 I-1).
 *
 * <p>find()는 로드 후 snapshot을 등재해 dirty 체크 기준선을 확보하지만,
 * findAll()은 snapshot을 등재하지 않아 첫 flush에서 UPDATE가 누락되는 불일치를 검증.
 *
 * <p>GREEN 후: RealEntityManager.findAll()에 snapshot 등재 보강이 완료되면 이 테스트가 통과한다.
 */
class FindAllDirtyTrackingIntegrationTest extends AbstractOrmIntegrationTest {

    @Override
    protected String jdbcUrl() {
        // 다른 통합 테스트 DB와 격리 — schema 충돌 방지
        return "jdbc:h2:mem:findall-dirty;DB_CLOSE_DELAY=-1";
    }

    /**
     * findAll()로 로드한 entity를 수정 후 flush 시 첫 번째 flush에서 UPDATE가 발생해야 한다.
     *
     * <p>I-1 결함: findAll 후 snapshot 부재 → flush의 {@code original == null} 분기가
     * 현재 상태를 기준선으로 잡고 continue → 첫 flush에서 UPDATE 누락.
     * 이 테스트는 수정 후 DB에서 변경된 값이 조회되는지 검증한다.
     */
    @Test
    void findAll_로드_후_수정_flush_시_UPDATE_DB_반영() {
        // 1번째 트랜잭션: INSERT (find()로 persist 후 flush)
        TestUser persisted = TransactionTemplate.execute(tm, () -> {
            TestUser u = new TestUser();
            u.name = "original";
            u.email = "original@x.com";
            em.persist(u);
            em.flush();
            return u;
        });

        // 2번째 트랜잭션: findAll()로 로드 후 수정 + flush
        TransactionTemplate.execute(tm, () -> {
            List<TestUser> users = em.findAll(TestUser.class);
            // findAll로 로드된 entity를 수정
            TestUser loaded = users.stream()
                    .filter(u -> u.id.equals(persisted.id))
                    .findFirst()
                    .orElseThrow();
            loaded.name = "updated_via_findAll";
            em.flush();
            return null;
        });

        // 트랜잭션 밖에서 직접 JDBC로 DB 값 확인 — 첫 flush에서 UPDATE가 반영돼야 함
        String name = jdbc.queryForObject(
                "SELECT name FROM test_users WHERE id = ?", String.class, persisted.id);
        assertThat(name).isEqualTo("updated_via_findAll");
    }
}
