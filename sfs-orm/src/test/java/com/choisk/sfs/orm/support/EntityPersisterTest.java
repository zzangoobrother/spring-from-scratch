package com.choisk.sfs.orm.support;

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

class EntityPersisterTest {

    private JdbcDataSource ds;
    private JdbcTemplate jdbc;
    private EntityMetadata md;
    private SequenceGenerator gen;
    private EntityPersister persister;

    @SfsEntity(name = "epersister_test")
    static class TestEntity {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "ep_seq")
        Long id;
        @SfsColumn
        String name;
        @SfsColumn(name = "age_value")
        int age;
    }

    @BeforeEach
    void setup() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:epersister;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds, new ThreadLocalTsm());
        jdbc.update("DROP TABLE IF EXISTS epersister_test");
        jdbc.update("DROP SEQUENCE IF EXISTS ep_seq");
        jdbc.update("CREATE SEQUENCE ep_seq START WITH 1");
        jdbc.update("CREATE TABLE epersister_test (id BIGINT PRIMARY KEY, name VARCHAR(100), age_value INT)");
        md = new EntityMetadataAnalyzer().analyze(TestEntity.class);
        gen = new SequenceGenerator("ep_seq", jdbc);
        persister = new EntityPersister(md, gen, jdbc);
    }

    @Test
    void executeInsert_persists_row_to_db() {
        TestEntity e = new TestEntity();
        e.id = 1L;
        e.name = "alice";
        e.age = 30;
        persister.executeInsert(e);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM epersister_test WHERE id = 1 AND name = 'alice' AND age_value = 30",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void loadById_reads_row_and_maps_columns() {
        jdbc.update("INSERT INTO epersister_test VALUES (?, ?, ?)", 5L, "bob", 25);
        Object loaded = persister.loadById(5L, null /* PersistenceContext — J1까지 lazy 없음 */);
        assertThat(loaded).isInstanceOf(TestEntity.class);
        TestEntity t = (TestEntity) loaded;
        assertThat(t.id).isEqualTo(5L);
        assertThat(t.name).isEqualTo("bob");
        assertThat(t.age).isEqualTo(25);
    }

    @Test
    void executeDelete_removes_row() {
        jdbc.update("INSERT INTO epersister_test VALUES (?, ?, ?)", 7L, "x", 1);
        TestEntity e = new TestEntity();
        e.id = 7L;
        persister.executeDelete(e);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM epersister_test WHERE id = 7",
                Long.class);
        assertThat(count).isEqualTo(0L);
    }
}
