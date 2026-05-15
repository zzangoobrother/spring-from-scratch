package com.choisk.sfs.tx.support;

import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;
import com.choisk.sfs.tx.TransactionStatus;
import com.choisk.sfs.tx.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * {@link Transactional} advice 본체. Phase 2B {@code AdviceInterceptor}와 동일 구조.
 *
 * <p>분기:
 * <ul>
 *   <li>정상 반환 → commit</li>
 *   <li>RuntimeException/Error → rollback</li>
 *   <li>Checked Exception → commit (Spring 본가 정합)</li>
 * </ul>
 *
 * <p>{@link Transactional#transactionManager()}로 다중 TM 빈 라우팅 (Phase 2B B-2 흡수).
 *
 * <p>TSM flag 관리: {@link TransactionSynchronizationManager}가 주입된 경우
 * 트랜잭션 begin 직후 {@code isActualTransactionActive = true},
 * commit/rollback 완료 후 {@code false}로 설정 — JPA {@code SfsTransactionalEntityManager}가
 * 트랜잭션 활성 여부를 판단하는 데 사용 (Spring 본가 패턴 정합).
 */
public class TransactionInterceptor {

    private final BeanFactory beanFactory;
    /** TSM flag 관리를 위한 선택적 주입. null이면 flag 관리 없이 동작 (하위 호환). */
    private final TransactionSynchronizationManager tsm;

    /** TSM 없이 사용할 때의 생성자 — 기존 코드 하위 호환. */
    public TransactionInterceptor(BeanFactory beanFactory) {
        this(beanFactory, null);
    }

    /** TSM flag 관리를 포함한 생성자 — JPA 통합 시 사용. */
    public TransactionInterceptor(BeanFactory beanFactory, TransactionSynchronizationManager tsm) {
        this.beanFactory = beanFactory;
        this.tsm = tsm;
    }

    public Object invoke(Object target, Method method, Callable<Object> proceed) throws Throwable {
        Transactional anno = findAnnotation(method);
        PlatformTransactionManager tm = resolveTransactionManager(anno);
        TransactionDefinition def = new TransactionDefinition(anno.propagation(), anno.isolation());

        TransactionStatus status = tm.getTransaction(def);
        // 신규 트랜잭션인 경우에만 flag 설정 — join 트랜잭션은 이미 outer가 true로 설정
        boolean flagOwner = status.isNewTransaction() && tsm != null;
        if (flagOwner) {
            setActive(true);
        }
        try {
            Object result = proceed.call();
            tm.commit(status);
            return result;
        } catch (RuntimeException | Error t) {
            tm.rollback(status);
            throw t;
        } catch (Throwable t) {
            // checked exception → commit (Spring 본가 정합)
            // commit 자체가 실패하면 원본 t를 suppressed로 첨부해 디버깅 정보 보존
            try {
                tm.commit(status);
            } catch (Throwable c) {
                c.addSuppressed(t);
                throw c;
            }
            throw t;
        } finally {
            // 신규 트랜잭션을 열었던 interceptor만 flag를 해제
            if (flagOwner) {
                setActive(false);
            }
        }
    }

    /** TSM이 {@link ThreadLocalTsm}이면 flag를 직접 설정, 그 외 타입은 무시 (박제 정합). */
    private void setActive(boolean active) {
        if (tsm instanceof ThreadLocalTsm threadLocalTsm) {
            threadLocalTsm.setActualTransactionActive(active);
        }
    }

    private Transactional findAnnotation(Method method) {
        Transactional onMethod = method.getAnnotation(Transactional.class);
        if (onMethod != null) return onMethod;
        Transactional onClass = method.getDeclaringClass().getAnnotation(Transactional.class);
        if (onClass != null) return onClass;
        throw new IllegalStateException("@Transactional not found on " + method);
    }

    private PlatformTransactionManager resolveTransactionManager(Transactional anno) {
        String name = anno.transactionManager();
        if (!name.isEmpty()) {
            return beanFactory.getBean(name, PlatformTransactionManager.class);
        }
        // type 기반 조회 시도 (B-2 흡수)
        try {
            return beanFactory.getBean(PlatformTransactionManager.class);
        } catch (Exception e) {
            // 복수 TM 등록 환경에서 NoUniqueBeanDefinitionException 발생 시
            // Spring 본가 기본 규약: "transactionManager" 이름으로 fallback
            try {
                return beanFactory.getBean("transactionManager", PlatformTransactionManager.class);
            } catch (Exception e2) {
                throw new TransactionException.NoTransactionManagerException(
                        "No PlatformTransactionManager bean found");
            }
        }
    }
}
