package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionStatus;

/**
 * {@link TransactionStatus} 기본 구현. {@link AbstractPlatformTransactionManager}가 발급.
 *
 * @param transaction TM별 트랜잭션 객체 (Mock은 String, DataSource는 ConnectionHolder)
 * @param newTransaction {@code true}이면 새 트랜잭션, {@code false}이면 join
 * @param suspendedResources REQUIRES_NEW 시 outer 보관본, 아니면 {@code null}
 */
public final class DefaultTransactionStatus implements TransactionStatus {

    private final Object transaction;
    private final boolean newTransaction;
    private final Object suspendedResources;
    private boolean rollbackOnly = false;

    public DefaultTransactionStatus(Object transaction, boolean newTransaction, Object suspendedResources) {
        this.transaction = transaction;
        this.newTransaction = newTransaction;
        this.suspendedResources = suspendedResources;
    }

    public Object getTransaction() { return transaction; }

    @Override public boolean isNewTransaction() { return newTransaction; }

    @Override public void setRollbackOnly() { this.rollbackOnly = true; }

    @Override public boolean isRollbackOnly() { return rollbackOnly; }

    @Override public Object getSuspendedResources() { return suspendedResources; }
}
