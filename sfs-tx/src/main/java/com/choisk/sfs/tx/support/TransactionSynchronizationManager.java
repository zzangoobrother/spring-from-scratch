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

    /** 테스트 셋업/티어다운용. */
    void clearAll();
}
