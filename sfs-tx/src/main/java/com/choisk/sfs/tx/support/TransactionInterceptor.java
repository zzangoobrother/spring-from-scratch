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
 */
public class TransactionInterceptor {

    private final BeanFactory beanFactory;

    public TransactionInterceptor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Object invoke(Object target, Method method, Callable<Object> proceed) throws Throwable {
        Transactional anno = findAnnotation(method);
        PlatformTransactionManager tm = resolveTransactionManager(anno);
        TransactionDefinition def = new TransactionDefinition(anno.propagation(), anno.isolation());

        TransactionStatus status = tm.getTransaction(def);
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
