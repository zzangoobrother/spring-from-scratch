package com.choisk.sfs.tx.support;

/**
 * 현재 thread/scope에 트랜잭션 리소스(Connection 등)를 bind/unbind/getResource. Spring 본가
 * {@code org.springframework.transaction.support.TransactionSynchronizationManager}와 동명/다른 패키지.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link ThreadLocalTsm} — 메인 (Spring 본가 정합)</li>
 *   <li>{@link ScopedValueTsm} — Java 25 idiom 비교 박제 (Task D1)</li>
 * </ul>
 */
public interface TransactionSynchronizationManager {

    void bindResource(Object key, Object value);

    Object getResource(Object key);

    Object unbindResource(Object key);

    /**
     * 현재 트랜잭션에 동기화 콜백을 등록한다.
     * 트랜잭션 commit 직전 {@link TransactionSynchronization#beforeCommit()},
     * 완료 후 {@link TransactionSynchronization#afterCompletion(int)}이 호출된다.
     */
    void registerSynchronization(TransactionSynchronization sync);

    /** 테스트 셋업/티어다운용. */
    void clearAll();

    /**
     * 현재 스레드에 실제(물리) 트랜잭션이 활성화되어 있는지 반환한다.
     *
     * <p>Spring 본가 {@code TransactionSynchronizationManager.isActualTransactionActive()}와 동일 시멘틱.
     * {@link ThreadLocalTsm}은 {@code actualTransactionActive} ThreadLocal flag로 구현.
     * {@link ScopedValueTsm}은 scope 진입 여부({@code SLOT.isBound()})로 구현.
     *
     * <p>이 값은 {@link TransactionInterceptor}가 트랜잭션 시작 시 {@code true},
     * commit/rollback 완료 후 {@code false}로 관리한다.
     *
     * @return 트랜잭션이 활성이면 {@code true}
     */
    boolean isActualTransactionActive();
}
