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

/**
 * RealEntityManager.persist() — SEQUENCE 분기 (write-behind) 검증.
 *
 * 학습 정점 ① 정상 박제:
 * persist() 직후 entity.id는 시퀀스 값으로 채워지지만 DB row는 0건.
 * 1차 캐시 등재 + snapshot 캡처 + actionQueue InsertAction 등록 3가지가
 * 모두 완료되어야 ORM의 동일성/dirty/트랜잭션 의미가 유지된다.
 */
class RealEntityManagerPersistTest {

    @SfsEntity(name = "persist_seq_test")
    static class SeqUser {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "persist_seq")
        Long id;
        @SfsColumn
        String name;
    }

    private SfsEntityManagerFactory emf;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rempersist;DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("DROP TABLE IF EXISTS persist_seq_test");
        jdbc.update("DROP SEQUENCE IF EXISTS persist_seq");
        jdbc.update("CREATE SEQUENCE persist_seq START WITH 1");
        jdbc.update("CREATE TABLE persist_seq_test (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(SeqUser.class)
                .build();
    }

    @Test
    void persist_with_SEQUENCE_strategy_assigns_id_but_does_not_insert() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        SeqUser u = new SeqUser();
        u.name = "alice";

        em.persist(u);

        // 검증 1: SEQUENCE 미리 받아 id 세팅
        assertThat(u.id).isEqualTo(1L);
        // 검증 2: DB에는 아직 INSERT 안 됨 (학습 정점 ① write-behind)
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM persist_seq_test", Long.class);
        assertThat(count).isEqualTo(0L);
        // 검증 3: 1차 캐시에 등재
        assertThat(em.context().contains(new EntityKey(SeqUser.class, 1L))).isTrue();
        // 검증 4: actionQueue에 InsertAction 1건
        assertThat(em.context().actionQueue()).hasSize(1);
        assertThat(em.context().actionQueue().get(0)).isInstanceOf(InsertAction.class);
    }
}
