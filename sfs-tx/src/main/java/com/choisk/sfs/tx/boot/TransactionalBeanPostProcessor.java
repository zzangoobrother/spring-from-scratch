package com.choisk.sfs.tx.boot;

import com.choisk.sfs.core.BeanCreationException;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.BeanFactoryAware;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.SmartInstantiationAwareBeanPostProcessor;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.support.TransactionInterceptor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * byte-buddy 기반 {@code @Transactional} BPP. Phase 2B {@code AspectEnhancingBeanPostProcessor}와 동일 패턴.
 *
 * <p>postProcessAfterInitialization 동작:
 * <ol>
 *   <li>BPP 자기 격리 (Phase 2B 패턴)</li>
 *   <li>{@code @Transactional} 메서드 없으면 원본 그대로</li>
 *   <li>final 메서드 가드 (A-1) — WARN 박제, enhance는 진행</li>
 *   <li>byte-buddy 서브클래스 + interceptor 적용 + 필드 reflection 복사</li>
 * </ol>
 *
 * <p>{@link SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference} 구현: 순환 의존 시 enhance된 early reference 반환 (A-2 흡수).
 */
public class TransactionalBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;
    private final List<String> lastFinalMethodWarnings = new ArrayList<>();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Phase 1A SIABPP 훅 — 순환 의존 시 *enhance된 early reference* 반환.
     * 3-level cache의 첫 의미 있는 사용 (A-2 흡수, spec § 5.4).
     */
    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;
        if (!hasTransactionalMethod(bean.getClass())) return bean;
        try {
            return enhance(bean);
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Failed to enhance early reference", e);
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;

        if (!hasTransactionalMethod(bean.getClass())) return bean;

        warnOnFinalTransactionalMethods(bean.getClass());

        try {
            return enhance(bean);
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Failed to enhance @Transactional bean", e);
        }
    }

    private boolean hasTransactionalMethod(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Transactional.class)) return true;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Transactional.class)) return true;
        }
        return false;
    }

    private void warnOnFinalTransactionalMethods(Class<?> clazz) {
        lastFinalMethodWarnings.clear();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Transactional.class) && Modifier.isFinal(m.getModifiers())) {
                String warn = "WARN: @Transactional method is final and will be silently skipped: " + m;
                System.err.println(warn);
                lastFinalMethodWarnings.add(warn);
            }
        }
    }

    private Object enhance(Object bean) throws Exception {
        Class<?> originalClass = bean.getClass();
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);

        // privateLookupIn 사용: 대상 클래스의 패키지와 무관하게 enhance 가능 (AspectEnhancingBeanPostProcessor 정합)
        Class<?> enhanced = new ByteBuddy()
                .subclass(originalClass)
                .method(ElementMatchers.isAnnotatedWith(Transactional.class)
                        .and(ElementMatchers.not(ElementMatchers.isFinal())))
                .intercept(MethodDelegation.to(new TxMethodInterceptor(interceptor)))
                .make()
                .load(originalClass.getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(
                                MethodHandles.privateLookupIn(originalClass, MethodHandles.lookup())))
                .getLoaded();

        Object enhancedInstance = enhanced.getDeclaredConstructor().newInstance();
        copyFields(bean, enhancedInstance);
        return enhancedInstance;
    }

    private void copyFields(Object src, Object dst) throws Exception {
        // superclass 체인 전체 순회 — 상속 빈의 부모 @Autowired 필드 복사 누락 방지
        // DefaultSingletonBeanRegistry.copyFieldsToEarlyReference와 동일 패턴
        for (Class<?> c = src.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(dst, f.get(src));
            }
        }
    }

    /** 테스트 보조 — A-1 WARN 박제 검증용. */
    public List<String> getLastFinalMethodWarnings() {
        return List.copyOf(lastFinalMethodWarnings);
    }

    /** byte-buddy interceptor — TransactionInterceptor.invoke로 위임. */
    public static class TxMethodInterceptor {
        private final TransactionInterceptor delegate;
        public TxMethodInterceptor(TransactionInterceptor delegate) { this.delegate = delegate; }

        @RuntimeType
        public Object intercept(@Origin Method method, @AllArguments Object[] args, @SuperCall Callable<Object> superCall) throws Throwable {
            return delegate.invoke(null, method, superCall);
        }
    }
}
