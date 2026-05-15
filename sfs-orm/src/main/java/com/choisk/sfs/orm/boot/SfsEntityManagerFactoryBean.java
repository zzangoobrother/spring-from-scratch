package com.choisk.sfs.orm.boot;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.tx.support.TransactionSynchronization;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

/**
 * {@link SfsEntityManagerFactory}를 Spring bean 생명주기에 연결하는 어댑터.
 *
 * <p>역할:
 * <ol>
 *   <li>트랜잭션 시작 시 EM 생성 + TSM resource로 등록 (bind 멱등성 보장)</li>
 *   <li>commit 직전 {@code em.flush()} 콜백 등록 — write-behind 큐 일제 실행</li>
 *   <li>commit/rollback 완료 후 PersistenceContext close + resource unbind 콜백 등록</li>
 * </ol>
 *
 * <p>bind 멱등성: 같은 트랜잭션 내에서 여러 번 호출해도 동일한 EM 인스턴스를 반환 — JPA 트랜잭션-범위 EM 시멘틱.
 *
 * <p>통합 테스트: 단위 테스트 없음 — M1+ 통합 테스트가 실제 트랜잭션과 함께 검증.
 */
public class SfsEntityManagerFactoryBean {

    private final SfsEntityManagerFactory emf;
    private final TransactionSynchronizationManager tsm;

    public SfsEntityManagerFactoryBean(SfsEntityManagerFactory emf, TransactionSynchronizationManager tsm) {
        this.emf = emf;
        this.tsm = tsm;
    }

    /** 감싸고 있는 {@link SfsEntityManagerFactory}를 반환한다. */
    public SfsEntityManagerFactory factory() {
        return emf;
    }

    /**
     * 현재 트랜잭션에 EM을 바인딩하고 반환한다.
     *
     * <p>이미 바인딩된 EM이 있으면 그것을 그대로 반환(멱등성). 없으면 새 EM을 생성하고
     * TSM resource로 등록한 뒤 flush/close 콜백을 {@link TransactionSynchronizationManager}에 등록한다.
     *
     * <p>콜백 동작:
     * <ul>
     *   <li>{@link TransactionSynchronization#beforeCommit()} — {@code em.flush()} 실행 (변경분 SQL DB 반영)</li>
     *   <li>{@link TransactionSynchronization#afterCompletion(int)} — PersistenceContext close + resource unbind</li>
     * </ul>
     *
     * @return 현재 트랜잭션에 바인딩된 {@link RealEntityManager}
     */
    public RealEntityManager bindToCurrentTransaction() {
        Object existing = tsm.getResource(emf);
        if (existing != null) {
            return (RealEntityManager) existing;
        }

        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        tsm.bindResource(emf, em);

        // 트랜잭션 종료 콜백 등록 — commit 직전 flush, 완료 후 close + unbind
        tsm.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit() {
                em.flush();
            }

            @Override
            public void afterCompletion(int status) {
                em.context().close();
                tsm.unbindResource(emf);
            }
        });

        return em;
    }
}
