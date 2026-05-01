package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 콘솔 출력만 하는 Mock TM. 추상화의 본질이 인터페이스에 있음을 박제하기 위한 학습용.
 *
 * <p>실제 DB 없이 propagation 분기 알고리즘({@link AbstractPlatformTransactionManager})만으로
 * suspend/resume 시연 가능. 출력 라인은 {@code [TX] BEGIN/COMMIT/ROLLBACK/SUSPEND/RESUME #N} 형식.
 */
public class MockTransactionManager extends AbstractPlatformTransactionManager {

    private static final Object TX_KEY = MockTransactionManager.class;

    private final TransactionSynchronizationManager tsm;
    private final AtomicLong txIdGen = new AtomicLong(0L);

    public MockTransactionManager(TransactionSynchronizationManager tsm) {
        this.tsm = tsm;
    }

    @Override
    protected Object doGetExisting() {
        return tsm.getResource(TX_KEY);
    }

    @Override
    protected Object doBegin(TransactionDefinition definition) {
        long id = txIdGen.incrementAndGet();
        String txObj = "tx#" + id;
        tsm.bindResource(TX_KEY, txObj);
        System.out.println("[TX] BEGIN     #" + id);
        return txObj;
    }

    @Override
    protected void doCommit(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] COMMIT    " + transaction);
    }

    @Override
    protected void doRollback(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] ROLLBACK  " + transaction);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] SUSPEND   " + transaction);
        return transaction;
    }

    @Override
    protected void doResume(Object suspendedResources) {
        tsm.bindResource(TX_KEY, suspendedResources);
        System.out.println("[TX] RESUME    " + suspendedResources);
    }
}
