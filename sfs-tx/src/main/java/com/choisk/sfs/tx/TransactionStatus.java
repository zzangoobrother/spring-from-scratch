package com.choisk.sfs.tx;

/**
 * 현재 트랜잭션 상태 핸들. {@link PlatformTransactionManager#getTransaction}이
 * 발급, {@link PlatformTransactionManager#commit}/{@link PlatformTransactionManager#rollback}이 소비.
 */
public interface TransactionStatus {

    /** {@code true}이면 새 트랜잭션, {@code false}이면 외부에 join. */
    boolean isNewTransaction();

    /** outer가 inner의 실패를 받아 commit 시점에 rollback 결정 (REQUIRED join 경로). */
    void setRollbackOnly();

    boolean isRollbackOnly();

    /** {@link com.choisk.sfs.tx.annotation.Propagation#REQUIRES_NEW} 시 outer 보관. {@code null} 가능. */
    Object getSuspendedResources();
}
