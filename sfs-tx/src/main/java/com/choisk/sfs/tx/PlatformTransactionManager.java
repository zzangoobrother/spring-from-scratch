package com.choisk.sfs.tx;

/**
 * 모든 TM의 공통 인터페이스. Spring 본가 {@code org.springframework.transaction.PlatformTransactionManager}와
 * 동명/다른 패키지 (의사결정 #3).
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link com.choisk.sfs.tx.support.MockTransactionManager} — 콘솔 출력만</li>
 *   <li>{@link com.choisk.sfs.tx.support.DataSourceTransactionManager} — JDBC Connection 기반</li>
 * </ul>
 */
public interface PlatformTransactionManager {

    /** 트랜잭션 시작 또는 join. */
    TransactionStatus getTransaction(TransactionDefinition definition);

    /** 정상 커밋. status가 rollback-only면 내부적으로 rollback으로 분기. */
    void commit(TransactionStatus status);

    /** 강제 rollback. */
    void rollback(TransactionStatus status);
}
