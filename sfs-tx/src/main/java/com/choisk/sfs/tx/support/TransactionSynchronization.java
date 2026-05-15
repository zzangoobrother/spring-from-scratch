package com.choisk.sfs.tx.support;

/**
 * 트랜잭션 생명주기 콜백 인터페이스.
 *
 * <p>Spring 본가 {@code org.springframework.transaction.support.TransactionSynchronization}과
 * 동일한 역할. {@link TransactionSynchronizationManager#registerSynchronization(TransactionSynchronization)}으로
 * 등록하면 트랜잭션 commit/rollback 시점에 콜백이 호출된다.
 *
 * <p>sfs-orm boot 통합: {@code SfsEntityManagerFactoryBean}이 트랜잭션 시작 시 EM을 생성하고
 * 이 인터페이스의 익명 구현을 등록해 commit 직전 {@code em.flush()}, 완료 후 context close를 수행한다.
 */
public interface TransactionSynchronization {

    /** 커밋 직전 호출. write-behind 큐의 SQL을 DB에 반영(flush)할 타이밍. */
    default void beforeCommit() {}

    /**
     * commit 또는 rollback 완료 후 호출.
     *
     * @param status 0 = commit, 1 = rollback (Spring 본가 STATUS_COMMITTED/STATUS_ROLLED_BACK 정합)
     */
    default void afterCompletion(int status) {}
}
