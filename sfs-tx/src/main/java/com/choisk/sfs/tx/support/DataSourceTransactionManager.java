package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC {@link Connection} 기반 TM. Spring 본가 {@code DataSourceTransactionManager}와 동명/다른 패키지.
 *
 * <p>begin: getConnection → setAutoCommit(false) → ConnectionHolder bind to TSM
 * <br>commit: doCommit(connection) → connection.close → unbind
 * <br>rollback: doRollback(connection) → connection.close → unbind
 * <br>suspend: unbind만 (보관본 = ConnectionHolder)
 * <br>resume: bind 보관본
 */
public class DataSourceTransactionManager extends AbstractPlatformTransactionManager {

    private final DataSource dataSource;
    private final TransactionSynchronizationManager tsm;

    public DataSourceTransactionManager(DataSource dataSource, TransactionSynchronizationManager tsm) {
        this.dataSource = dataSource;
        this.tsm = tsm;
    }

    public DataSource getDataSource() { return dataSource; }
    public TransactionSynchronizationManager getTsm() { return tsm; }

    @Override
    protected Object doGetExisting() {
        return tsm.getResource(dataSource);
    }

    @Override
    protected Object doBegin(TransactionDefinition definition) {
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            ConnectionHolder holder = new ConnectionHolder(conn);
            tsm.bindResource(dataSource, holder);
            return holder;
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("failed to begin", e);
        }
    }

    @Override
    protected void doCommit(Object transaction) {
        ConnectionHolder holder = (ConnectionHolder) transaction;
        try {
            holder.getConnection().commit();
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("commit failed", e);
        } finally {
            tsm.unbindResource(dataSource);
            closeQuietly(holder.getConnection());
        }
    }

    @Override
    protected void doRollback(Object transaction) {
        ConnectionHolder holder = (ConnectionHolder) transaction;
        try {
            holder.getConnection().rollback();
        } catch (SQLException e) {
            throw new TransactionException.RollbackFailedException("rollback failed", e);
        } finally {
            tsm.unbindResource(dataSource);
            closeQuietly(holder.getConnection());
        }
    }

    @Override
    protected Object doSuspend(Object transaction) {
        return tsm.unbindResource(dataSource);
    }

    @Override
    protected void doResume(Object suspendedResources) {
        tsm.bindResource(dataSource, suspendedResources);
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); } catch (SQLException ignored) {}
    }
}
