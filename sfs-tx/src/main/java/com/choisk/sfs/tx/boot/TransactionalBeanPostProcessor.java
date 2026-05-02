package com.choisk.sfs.tx.boot;

import com.choisk.sfs.core.BeanCreationException;
import com.choisk.sfs.core.ReflectionUtils;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

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
    // AspectEnhancingBeanPostProcessor와 동일하게 setBeanFactory 시점에 1회 생성해 enhance 호출마다 재사용
    private TransactionInterceptor sharedInterceptor;
    private final List<String> lastFinalMethodWarnings = new ArrayList<>();

    /**
     * 순환 의존 + @Transactional enhance 시 단일 인스턴스 보장. {@code getEarlyBeanReference}로
     * enhance한 빈은 {@code postProcessAfterInitialization}에서 *재enhance하지 않음*.
     *
     * <p>key: beanName — Spring 본가 {@code AbstractAutoProxyCreator.earlyProxyReferences}와 정합.
     * spec § 3.3.2. {@code AspectEnhancingBeanPostProcessor.earlyProxyReferences}와 동일 패턴.
     */
    private final Map<String, Object> earlyProxyReferences = new ConcurrentHashMap<>();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        this.sharedInterceptor = new TransactionInterceptor(beanFactory);
    }

    /**
     * Phase 1A SIABPP 훅 — 순환 의존 시 *enhance된 early reference* 반환.
     * 3-level cache의 첫 의미 있는 사용 (A-2 흡수, spec § 5.4).
     */
    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;
        if (!hasTransactionalMethod(bean.getClass())) return bean;

        // 순환 의존 시 postProcessAfterInitialization에서 재enhance 방지를 위한 추적 등록
        earlyProxyReferences.put(beanName, bean);
        try {
            return enhance(bean);
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Failed to enhance early reference", e);
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;

        // 캐시 hit: getEarlyBeanReference에서 이미 enhance 처리됨 — 원본 그대로 반환.
        // remove(key)는 조회+삭제 atomic → 메모리 누수 방지 + ConcurrentHashMap 동시성 안전.
        // (early가 3-level 캐시 2차에 있고 1차로 승격되며, 본 메서드 반환값은 컨테이너가 무시함)
        if (earlyProxyReferences.remove(beanName) != null) {
            return bean;
        }

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

    /**
     * byte-buddy 서브클래스 생성 + {@code TxMethodInterceptor} 적용 + 필드 복사.
     *
     * <p>{@code getEarlyBeanReference}와 {@code postProcessAfterInitialization} 두 진입점이 공유.
     * final 메서드는 {@link ElementMatchers#isFinal()} 필터로 enhance 대상에서 제외 (A-1 박제).
     */
    private Object enhance(Object bean) throws Exception {
        Class<?> originalClass = bean.getClass();

        // privateLookupIn 사용: 대상 클래스의 패키지와 무관하게 enhance 가능 (AspectEnhancingBeanPostProcessor 정합)
        Class<?> enhanced = new ByteBuddy()
                .subclass(originalClass)
                .method(ElementMatchers.isAnnotatedWith(Transactional.class)
                        .and(ElementMatchers.not(ElementMatchers.isFinal())))
                .intercept(MethodDelegation.to(new TxMethodInterceptor(sharedInterceptor)))
                .make()
                .load(originalClass.getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(
                                MethodHandles.privateLookupIn(originalClass, MethodHandles.lookup())))
                .getLoaded();

        Object enhancedInstance = enhanced.getDeclaredConstructor().newInstance();
        copyFields(bean, enhancedInstance);
        return enhancedInstance;
    }

    private void copyFields(Object src, Object dst) {
        // superclass 체인 전체 순회 — 상속 빈의 부모 @Autowired 필드 복사 누락 방지
        // DefaultSingletonBeanRegistry.copyFieldsToEarlyReference와 동일 패턴
        // ReflectionUtils.doWithFields: AspectEnhancingBeanPostProcessor와 동일 유틸 재사용
        ReflectionUtils.doWithFields(src.getClass(), f -> {
            if (Modifier.isStatic(f.getModifiers())) return;
            ReflectionUtils.setField(f, dst, ReflectionUtils.getField(f, src));
        });
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
