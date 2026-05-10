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

class SequenceGeneratorTest {

    private JdbcDataSource ds;
    private JdbcTemplate jdbc;

    @SfsEntity(name = "seq_test")
    static class SeqEntity {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "seq_test_seq")
        Long id;
        @SfsColumn
        String name;
    }

    @BeforeEach
    void setup() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:seqtest;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds, new ThreadLocalTsm());
        jdbc.update("DROP SEQUENCE IF EXISTS seq_test_seq");
        jdbc.update("CREATE SEQUENCE seq_test_seq START WITH 1 INCREMENT BY 1");
    }

    @Test
    void isPostInsert_returns_false() {
        var gen = new SequenceGenerator("seq_test_seq", jdbc);
        assertThat(gen.isPostInsert()).isFalse();
    }

    @Test
    void generate_returns_next_sequence_value_without_inserting() {
        var analyzer = new EntityMetadataAnalyzer();
        var md = analyzer.analyze(SeqEntity.class);
        var gen = new SequenceGenerator("seq_test_seq", jdbc);

        SeqEntity e = new SeqEntity();
        e.name = "alice";

        Object key1 = gen.generate(e, md);
        Object key2 = gen.generate(e, md);

        assertThat(((Number) key1).longValue()).isEqualTo(1L);
        assertThat(((Number) key2).longValue()).isEqualTo(2L);
        // INSERT는 발생하지 않아야 함 — 학습 정점 ① 정상 박제
    }
}
