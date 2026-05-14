package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RealEntityManager.remove() — TDD 단위 테스트.
 *
 * K1 TDD 판단 근거: remove()에는 미관리 엔티티 → IllegalArgumentException, IllegalAccessException catch
 * 두 분기가 존재하며, M1(BasicCrudIntegrationTest) 안전망이 현재 미작성 상태 → CLAUDE.md 규약상 TDD 필수.
 *
 * 검증 시나리오:
 * 1. 미관리 엔티티 → IllegalArgumentException
 * 2. 관리 엔티티 → actionQueue에 DeleteAction 등록
 */
class RealEntityManagerRemoveTest {

    @SfsEntity(name = "remove_test")
    static class RemoveUser {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "remove_seq")
        Long id;
        @SfsColumn
        String name;
    }

    private SfsEntityManagerFactory emf;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:removetestdb;DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        jdbc = new JdbcTemplate(ds, tsm);

        // 테이블 + 시퀀스 초기화
        jdbc.update("DROP TABLE IF EXISTS remove_test");
        jdbc.update("DROP SEQUENCE IF EXISTS remove_seq");
        jdbc.update("CREATE SEQUENCE remove_seq START WITH 1");
        jdbc.update("CREATE TABLE remove_test (id BIGINT PRIMARY KEY, name VARCHAR(100))");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(RemoveUser.class)
                .build();
    }

    /**
     * 미관리 엔티티(1차 캐시에 없는 엔티티)를 remove() 하면 IllegalArgumentException.
     * JPA 스펙 §3.2.3: "If X is a detached object, the EntityNotFoundException may be thrown"
     * (SFS는 IllegalArgumentException으로 단순화).
     */
    @Test
    void remove_unmanaged_entity_throws_IllegalArgumentException() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        RemoveUser u = new RemoveUser();
        u.id = 99L;   // 1차 캐시에 없음
        u.name = "ghost";

        assertThatThrownBy(() -> em.remove(u))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not managed");
    }

    /**
     * 1차 캐시에 등재된 관리 엔티티를 remove() 하면 actionQueue에 DeleteAction 1건 등록.
     * flush(K2) 호출 시 실제 DELETE가 실행된다 (write-behind 패턴).
     */
    @Test
    void remove_managed_entity_enqueues_DeleteAction() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();

        // persist → 1차 캐시 등재 + actionQueue에 InsertAction
        RemoveUser u = new RemoveUser();
        u.name = "alice";
        em.persist(u);

        // actionQueue: [InsertAction]
        assertThat(em.context().actionQueue()).hasSize(1);

        em.remove(u);

        // actionQueue: [InsertAction, DeleteAction]
        assertThat(em.context().actionQueue()).hasSize(2);
        assertThat(em.context().actionQueue().get(1)).isInstanceOf(DeleteAction.class);
        DeleteAction da = (DeleteAction) em.context().actionQueue().get(1);
        assertThat(da.entity()).isSameAs(u);
    }
}
