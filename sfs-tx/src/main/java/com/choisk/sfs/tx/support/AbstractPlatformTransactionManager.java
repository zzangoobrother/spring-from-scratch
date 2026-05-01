package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;
import com.choisk.sfs.tx.TransactionStatus;
import com.choisk.sfs.tx.annotation.Propagation;

/**
 * propagation 분기 알고리즘 박제. 구현체는 {@link #doBegin}/{@link #doCommit}/{@link #doRollback}/{@link #doSuspend}/{@link #doResume}만 override.
 *
 * <p>본 추상 골격은 Spring 본가 {@code AbstractPlatformTransactionManager}의 핵심 알고리즘만
 * 발췌 박제. 5종 propagation은 의도적 비목표 (spec § 7 한계).
 */
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager {

    @Override
    public final TransactionStatus getTransaction(TransactionDefinition definition) {
        Object existing = doGetExisting();

        if (existing == null) {
            // 신규 트랜잭션: REQUIRED + REQUIRES_NEW 모두 동일 처리
            Object newTx = doBegin(definition);
            return new DefaultTransactionStatus(newTx, true, null);
        }

        // 존재하는 트랜잭션이 있을 때
        if (definition.propagation() == Propagation.REQUIRES_NEW) {
            Object suspended = doSuspend(existing);
            Object newTx = doBegin(definition);
            return new DefaultTransactionStatus(newTx, true, suspended);
        }

        // REQUIRED — join
        return new DefaultTransactionStatus(existing, false, null);
    }

    @Override
    public final void commit(TransactionStatus status) {
        DefaultTransactionStatus dts = (DefaultTransactionStatus) status;

        if (dts.isRollbackOnly()) {
            // outer가 inner 실패를 받아 rollback (REQUIRED join 경로의 정점)
            rollback(status);
            return;
        }

        try {
            if (dts.isNewTransaction()) {
                try {
                    doCommit(dts.getTransaction());
                } catch (Throwable t) {
                    throw new TransactionException.CommitFailedException("commit failed", t);
                }
            }
            // join인 경우 outer가 commit 책임 — 여기서 아무것도 안 함
        } finally {
            resumeIfNecessary(dts);
        }
    }

    @Override
    public final void rollback(TransactionStatus status) {
        DefaultTransactionStatus dts = (DefaultTransactionStatus) status;

        try {
            if (dts.isNewTransaction()) {
                try {
                    doRollback(dts.getTransaction());
                } catch (Throwable t) {
                    throw new TransactionException.RollbackFailedException("rollback failed", t);
                }
            } else {
                // join인 경우 outer에게 rollback 요청
                dts.setRollbackOnly();
            }
        } finally {
            resumeIfNecessary(dts);
        }
    }

    /**
     * REQUIRES_NEW outer 복원 — commit/rollback 실패 시에도 반드시 실행 (Spring 본가 패턴 정합).
     * finally 블록 공통 처리.
     */
    private void resumeIfNecessary(DefaultTransactionStatus dts) {
        if (dts.getSuspendedResources() != null) {
            doResume(dts.getSuspendedResources());
        }
    }

    /** 현재 thread/scope에 묶인 트랜잭션이 있으면 반환, 없으면 {@code null}. */
    protected abstract Object doGetExisting();

    /** 새 트랜잭션 시작 + thread/scope에 bind. 반환값은 트랜잭션 객체. */
    protected abstract Object doBegin(TransactionDefinition definition);

    /** 트랜잭션 커밋 + bind 해제. */
    protected abstract void doCommit(Object transaction);

    /** 트랜잭션 롤백 + bind 해제. */
    protected abstract void doRollback(Object transaction);

    /** 현재 트랜잭션을 thread/scope에서 분리 + 반환 (REQUIRES_NEW 진입). */
    protected abstract Object doSuspend(Object transaction);

    /** 보관된 리소스를 thread/scope에 다시 bind (REQUIRES_NEW 종료). */
    protected abstract void doResume(Object suspendedResources);
}
