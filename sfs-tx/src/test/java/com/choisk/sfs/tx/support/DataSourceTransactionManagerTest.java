package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceTransactionManagerTest {

    private DataSource dataSource;
    private ThreadLocalTsm tsm;
    private DataSourceTransactionManager tm;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT)");
        }
        this.dataSource = ds;
        this.tsm = new ThreadLocalTsm();
        this.tm = new DataSourceTransactionManager(dataSource, tsm);
    }

    @AfterEach
    void tearDown() {
        tsm.clearAll();
    }

    @Test
    void beginBindsConnectionToTsm() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        assertThat(tsm.getResource(dataSource)).isNotNull();
        assertThat(status.isNewTransaction()).isTrue();

        tm.commit(status);
    }

    @Test
    void commitTriggersConnectionCommitAndUnbinds() throws Exception {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try (Statement s = holder.getConnection().createStatement()) {
            s.execute("INSERT INTO t VALUES (1)");
        }
        tm.commit(status);

        assertThat(tsm.getResource(dataSource)).isNull();
        // 새 connection으로 INSERT가 commit되었는지 확인
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM t");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void rollbackTriggersConnectionRollbackAndUnbinds() throws Exception {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try (Statement s = holder.getConnection().createStatement()) {
            s.execute("INSERT INTO t VALUES (1)");
        }
        tm.rollback(status);

        assertThat(tsm.getResource(dataSource)).isNull();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM t");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    @Test
    void requiresNewSuspendsCurrentConnection() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder outerHolder = (ConnectionHolder) tsm.getResource(dataSource);

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.requiresNew());
        ConnectionHolder innerHolder = (ConnectionHolder) tsm.getResource(dataSource);

        assertThat(innerHolder).isNotSameAs(outerHolder);
        assertThat(inner.isNewTransaction()).isTrue();

        tm.commit(inner);
        // outer 복원
        assertThat(tsm.getResource(dataSource)).isSameAs(outerHolder);

        tm.commit(outer);
    }

    @Test
    void connectionAutoCommitIsDisabledOnBegin() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try {
            assertThat(holder.getConnection().getAutoCommit()).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tm.commit(status);
    }
}
