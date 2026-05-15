package com.choisk.sfs.tx.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ThreadLocal} 기반 TSM. Spring 본가 {@code TransactionSynchronizationManager} 동일 패턴.
 *
 * <p>가변 Map을 ThreadLocal에 보관 — synchronization 추가 등록 자연 가능. {@link ScopedValueTsm}의
 * immutable 제약과 대조되는 박제.
 *
 * <p>동기화 콜백 리스트도 ThreadLocal로 관리 — 트랜잭션 인터셉터가 commit/rollback 시
 * {@link #invokeSynchronizationsBeforeCommit()} / {@link #invokeSynchronizationsAfterCompletion(int)}를
 * 호출해 등록된 콜백을 순서대로 실행한다.
 */
public class ThreadLocalTsm implements TransactionSynchronizationManager {

    private final ThreadLocal<Map<Object, Object>> resources = ThreadLocal.withInitial(HashMap::new);
    /** 현재 스레드의 트랜잭션 동기화 콜백 리스트. */
    private final ThreadLocal<List<TransactionSynchronization>> synchronizations =
            ThreadLocal.withInitial(ArrayList::new);
    /**
     * 현재 스레드에 실제(물리) 트랜잭션이 활성화되어 있는지를 나타내는 flag.
     * Spring 본가 {@code TransactionSynchronizationManager.actualTransactionActive}와 동일 패턴.
     * {@link TransactionInterceptor}가 begin 시 {@code true}, commit/rollback 완료 후 {@code false}로 관리.
     */
    private final ThreadLocal<Boolean> actualTransactionActive = ThreadLocal.withInitial(() -> false);

    @Override
    public void bindResource(Object key, Object value) {
        resources.get().put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        return resources.get().get(key);
    }

    @Override
    public Object unbindResource(Object key) {
        return resources.get().remove(key);
    }

    @Override
    public void registerSynchronization(TransactionSynchronization sync) {
        synchronizations.get().add(sync);
    }

    /**
     * commit 직전 등록된 모든 콜백의 {@link TransactionSynchronization#beforeCommit()}을 실행한다.
     * 트랜잭션 인터셉터(SfsTransactionInterceptor 등)가 commit 직전에 호출.
     */
    public void invokeSynchronizationsBeforeCommit() {
        for (TransactionSynchronization sync : synchronizations.get()) {
            sync.beforeCommit();
        }
    }

    /**
     * commit/rollback 완료 후 등록된 모든 콜백의 {@link TransactionSynchronization#afterCompletion(int)}을 실행한다.
     * 트랜잭션 인터셉터가 완료 후 호출. 실행 후 콜백 리스트 초기화.
     *
     * @param status 0 = commit, 1 = rollback
     */
    public void invokeSynchronizationsAfterCompletion(int status) {
        List<TransactionSynchronization> toInvoke = new ArrayList<>(synchronizations.get());
        synchronizations.remove();
        for (TransactionSynchronization sync : toInvoke) {
            sync.afterCompletion(status);
        }
    }

    @Override
    public boolean isActualTransactionActive() {
        return Boolean.TRUE.equals(actualTransactionActive.get());
    }

    /**
     * 현재 스레드의 트랜잭션 활성 flag를 설정한다.
     * {@link TransactionInterceptor}가 트랜잭션 시작 시 {@code true},
     * commit/rollback 완료 후 {@code false}로 호출한다.
     *
     * @param active 트랜잭션 활성 여부
     */
    public void setActualTransactionActive(boolean active) {
        if (active) {
            actualTransactionActive.set(true);
        } else {
            actualTransactionActive.remove();
        }
    }

    @Override
    public void clearAll() {
        resources.remove();
        synchronizations.remove();
        actualTransactionActive.remove();
    }
}
