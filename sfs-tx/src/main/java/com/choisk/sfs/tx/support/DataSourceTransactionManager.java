package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC {@link Connection} 기반 TM. Spring 본가 {@code DataSourceTransactionManager}와 동명/다른 패키지.
 *
 * <p>begin: getConnection → setAutoCommit(false) → ConnectionHolder bind to TSM → flag=true
 * <br>commit: beforeCommit 콜백 → doCommit(connection) → connection.close → unbind → afterCompletion(0) → flag=false
 * <br>rollback: doRollback(connection) → connection.close → unbind → afterCompletion(1) → flag=false
 * <br>suspend: unbind만 (보관본 = ConnectionHolder)
 * <br>resume: bind 보관본
 *
 * <p>TSM flag 관리: 신규 트랜잭션 begin 시 {@code isActualTransactionActive=true},
 * commit/rollback 완료 후 {@code false}로 설정한다. Spring 본가 {@code AbstractPlatformTransactionManager}의
 * {@code prepareSynchronization()} 패턴 정합. TSM이 {@link ThreadLocalTsm}인 경우에만 적용.
 *
 * <p>동기화 콜백 위임: TSM이 {@link ThreadLocalTsm}인 경우 commit 직전
 * {@link ThreadLocalTsm#invokeSynchronizationsBeforeCommit()},
 * commit/rollback 완료 후 {@link ThreadLocalTsm#invokeSynchronizationsAfterCompletion(int)}을 호출한다.
 * 이를 통해 {@code SfsEntityManagerFactoryBean}이 등록한 flush/close 콜백이 올바른 시점에 실행된다.
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
            // 신규 트랜잭션 시작 — flag 활성화 (Spring 본가 prepareSynchronization 패턴 정합)
            setActive(true);
            return holder;
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("failed to begin", e);
        }
    }

    @Override
    protected void doCommit(Object transaction) {
        // WHY: beforeCommit()에서 flush() 예외 발생 시 commit()은 실행되지 않고
        // finally의 releaseConnection()이 status=0(commit)으로 afterCompletion을 호출.
        // 실제로는 commit이 미수행이나 JDBC autoCommit=false + connection.close() 시
        // 미커밋 트랜잭션 rollback 보장으로 데이터 정합성은 유지됨. afterCompletion(0)을
        // "commit 완료" 신호로 해석하는 콜백은 본 sfs-orm에는 없음 (PC.close + unbind만).
        invokeBeforeCommit();
        ConnectionHolder holder = (ConnectionHolder) transaction;
        try {
            holder.getConnection().commit();
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("commit failed", e);
        } finally {
            releaseConnection(holder, 0);
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
            releaseConnection(holder, 1);
        }
    }

    /**
     * TSM unbind + Connection close + afterCompletion 콜백 + flag 비활성화
     * — commit/rollback 양쪽 finally 공통 처리.
     *
     * @param status 0 = commit, 1 = rollback
     */
    private void releaseConnection(ConnectionHolder holder, int status) {
        tsm.unbindResource(dataSource);
        closeQuietly(holder.getConnection());
        // afterCompletion 콜백 실행 — em.context().close() + resource unbind 등
        invokeAfterCompletion(status);
        // 트랜잭션 종료 — flag 비활성화
        setActive(false);
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

    /**
     * TSM이 {@link ThreadLocalTsm}이면 {@code isActualTransactionActive} flag를 설정한다.
     * 그 외 TSM 구현체는 자체 메커니즘으로 활성 여부를 표현하므로 무시.
     */
    private void setActive(boolean active) {
        if (tsm instanceof ThreadLocalTsm threadLocalTsm) {
            threadLocalTsm.setActualTransactionActive(active);
        }
    }

    /**
     * TSM이 {@link ThreadLocalTsm}이면 commit 직전 등록된 동기화 콜백을 실행한다.
     */
    private void invokeBeforeCommit() {
        if (tsm instanceof ThreadLocalTsm threadLocalTsm) {
            threadLocalTsm.invokeSynchronizationsBeforeCommit();
        }
    }

    /**
     * TSM이 {@link ThreadLocalTsm}이면 commit/rollback 완료 후 등록된 동기화 콜백을 실행한다.
     *
     * @param status 0 = commit, 1 = rollback
     */
    private void invokeAfterCompletion(int status) {
        if (tsm instanceof ThreadLocalTsm threadLocalTsm) {
            threadLocalTsm.invokeSynchronizationsAfterCompletion(status);
        }
    }
}
