package com.choisk.sfs.orm.boot;

import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.orm.exception.SfsTransactionRequiredException;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

/**
 * 트랜잭션-범위 EntityManager 프록시.
 *
 * <p>Spring 본가 {@code SharedEntityManagerCreator}의 학습 박제 버전.
 * 각 메서드 호출 시 {@link #currentEm()}을 통해 현재 스레드의 트랜잭션에 바인딩된
 * 실제 {@link com.choisk.sfs.orm.RealEntityManager}를 조회한다.
 *
 * <p>동작 흐름:
 * <ol>
 *   <li>{@link TransactionSynchronizationManager#isActualTransactionActive()} — 트랜잭션 활성 여부 확인</li>
 *   <li>비활성이면 {@link SfsTransactionRequiredException} throw</li>
 *   <li>활성이면 {@link SfsEntityManagerFactoryBean#bindToCurrentTransaction()} 호출 —
 *       이미 바인딩된 EM이 있으면 그것을 반환(멱등성), 없으면 새로 생성 + 콜백 등록</li>
 *   <li>조회된 EM에 실제 작업 위임</li>
 * </ol>
 *
 * <p>단위 테스트 없음 — 통합 테스트(M1+)가 실제 트랜잭션과 함께 검증. L1과 동일 패턴.
 */
public class SfsTransactionalEntityManager implements SfsEntityManager {

    private final SfsEntityManagerFactoryBean factoryBean;
    private final TransactionSynchronizationManager tsm;

    public SfsTransactionalEntityManager(SfsEntityManagerFactoryBean factoryBean,
                                         TransactionSynchronizationManager tsm) {
        this.factoryBean = factoryBean;
        this.tsm = tsm;
    }

    /**
     * 현재 트랜잭션에 바인딩된 EntityManager를 반환한다.
     *
     * <p>트랜잭션이 활성이 아니면 {@link SfsTransactionRequiredException}을 던진다.
     * 활성이면 {@link SfsEntityManagerFactoryBean#bindToCurrentTransaction()}을 통해
     * 현재 트랜잭션의 EM을 반환 (멱등성 보장 — 같은 트랜잭션 내 재호출 시 동일 인스턴스).
     *
     * @return 현재 트랜잭션에 바인딩된 {@link SfsEntityManager}
     * @throws SfsTransactionRequiredException 활성 트랜잭션이 없을 때
     */
    private SfsEntityManager currentEm() {
        if (!tsm.isActualTransactionActive()) {
            throw new SfsTransactionRequiredException();
        }
        return factoryBean.bindToCurrentTransaction();
    }

    @Override
    public void persist(Object entity) {
        currentEm().persist(entity);
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        return currentEm().find(entityClass, primaryKey);
    }

    @Override
    public void remove(Object entity) {
        currentEm().remove(entity);
    }

    @Override
    public void flush() {
        currentEm().flush();
    }

    @Override
    public <T> T merge(T entity) {
        return currentEm().merge(entity);
    }

    @Override
    public boolean contains(Object entity) {
        return currentEm().contains(entity);
    }
}
