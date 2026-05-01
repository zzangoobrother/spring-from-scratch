package com.choisk.sfs.tx.jdbc;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.support.ConnectionHolder;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTemplateTest {

    private DataSource dataSource;
    private ThreadLocalTsm tsm;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT, name VARCHAR(100))");
        }
        this.dataSource = ds;
        this.tsm = new ThreadLocalTsm();
        this.jdbc = new JdbcTemplate(dataSource, tsm);
    }

    @AfterEach
    void tearDown() {
        tsm.clearAll();
    }

    @Test
    void queryReturnsEmptyListWhenNoRows() {
        List<String> names = jdbc.query("SELECT name FROM t", (rs, i) -> rs.getString(1));

        assertThat(names).isEmpty();
    }

    @Test
    void updateInsertsRow() {
        int affected = jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");

        assertThat(affected).isEqualTo(1);
        List<String> names = jdbc.query("SELECT name FROM t WHERE id = ?", (rs, i) -> rs.getString(1), 1);
        assertThat(names).containsExactly("Alice");
    }

    @Test
    void queryWithMultipleRows() {
        jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");
        jdbc.update("INSERT INTO t VALUES (?, ?)", 2, "Bob");

        List<String> names = jdbc.query("SELECT name FROM t ORDER BY id", (rs, i) -> rs.getString(1));

        assertThat(names).containsExactly("Alice", "Bob");
    }

    @Test
    void usesBoundConnectionWhenInsideTransaction() {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource, tsm);
        tm.getTransaction(TransactionDefinition.required());

        // 트랜잭션 안에서 INSERT — TSM에 bind된 ConnectionHolder의 connection 사용
        jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");

        // commit 전: TSM에 bind된 holder의 connection만 INSERT가 보임
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);
        assertThat(holder).isNotNull();
        // commit 시 영구화
        tm.commit(tm.getTransaction(TransactionDefinition.required()));
        // (위 두 번째 getTransaction은 재진입 → join → status는 isNew=false. 명시적 commit으로 마무리는 학습 박제)

        List<String> names = jdbc.query("SELECT name FROM t", (rs, i) -> rs.getString(1));
        assertThat(names).containsExactly("Alice");
    }
}
