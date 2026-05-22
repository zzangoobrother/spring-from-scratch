package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

class EntityPersisterFindAllTest {

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fap-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;
        TransactionSynchronizationManager tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource, tsm);

        jdbc.update("CREATE TABLE simples (id BIGINT PRIMARY KEY AUTO_INCREMENT, label VARCHAR(50))");
        jdbc.update("INSERT INTO simples (label) VALUES ('a')");
        jdbc.update("INSERT INTO simples (label) VALUES ('b')");
        jdbc.update("INSERT INTO simples (label) VALUES ('c')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(dataSource)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(SimpleEntity.class)
                .build();
    }

    @Test
    void findAll_SELECT_모든_행_반환_후_identityMap_등재() {
        EntityPersister persister = emf.persisterOf(SimpleEntity.class);
        PersistenceContext ctx = new PersistenceContext();

        List<Object> result = persister.findAll(ctx);

        assertThat(result).hasSize(3);
        // 정점 ② 정합 — 모든 result entity가 identityMap 등재 (D1 buildRowMapper 보강 의존)
        for (Object e : result) {
            Long id = ((SimpleEntity) e).id;
            assertThat(ctx.contains(new EntityKey(SimpleEntity.class, id))).isTrue();
        }
    }

    @SfsEntity(name = "simples")
    static class SimpleEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String label;
    }
}
