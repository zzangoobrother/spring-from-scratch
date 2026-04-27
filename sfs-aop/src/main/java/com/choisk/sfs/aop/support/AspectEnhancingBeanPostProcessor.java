package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.BeanFactoryAware;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * byte-buddy 기반 {@code @Aspect} 라우팅 BPP. 빈 생성 직후({@code postProcessAfterInitialization}) 동작:
 *
 * <ol>
 *   <li>{@code BeanPostProcessor} 빈은 그대로 반환 — 자기 참조 격리</li>
 *   <li>{@code @Aspect} 빈은 {@link AspectRegistry}에 advice 등록 후 원본 반환</li>
 *   <li>일반 빈은 매칭 검사 → 매칭 시 byte-buddy 서브클래스 + 인터셉터 적용 + 필드 reflection 복사</li>
 *   <li>매칭 없으면 원본 그대로 — enhance 비용 0</li>
 * </ol>
 *
 * <p>한계: {@code final} 클래스/필드는 fail-fast로 명확한 에러. {@code final} 메서드는 silent skip
 * (Phase 2C에서 검출 추가 예정).
 *
 * <p><strong>한계 (자원 수명):</strong> {@link ClassLoadingStrategy.UsingLookup}으로 생성한 동적 클래스는
 * 대상 클래스의 ClassLoader에 주입되어 ClassLoader 수명에 묶인다. ApplicationContext close 시
 * 별도 cleanup이 없어 <em>컨텍스트 재생성이 잦은 환경</em>(테스트 스위트, hot-reload)에서는 Metaspace 누적 가능.
 * 학습 컨텍스트(단일 ApplicationContext) 범위에서는 영향 없음. 격리 ClassLoader 기반 회수는 Phase 2C+ 검토.
 *
 * <p><strong>제약 (등록 순서):</strong> {@code @Aspect} 빈이 <em>대상 빈보다 먼저</em> 본 BPP를 통과해야
 * advice가 적용된다. 컨테이너의 빈 생성 순서가 보장되지 않는 환경에서는 <em>late-registered aspect</em>가
 * <em>먼저 enhance된 빈</em>에 적용되지 않는다.
 * Phase 2C+에서 two-pass(BPP 사전 수집) 방식으로 해소 예정.
 */
public class AspectEnhancingBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;
    private final AspectRegistry registry = new AspectRegistry();
    // 매 enhance마다 새 인스턴스 생성을 회피 — setBeanFactory 시점에 1회 초기화 후 모든 enhanced 빈에 공유
    private AdviceInterceptor sharedInterceptor;

    /**
     * BeanFactory 주입 시점에 모든 BeanDefinition을 사전 순회하여 {@code @Aspect} 클래스를
     * registry에 미리 등록한다.
     *
     * <p>이 two-pass 사전 수집이 없으면, {@code preInstantiateSingletons()}에서 {@code @Aspect} 빈보다
     * 대상 빈이 먼저 생성될 경우 advice가 누락된다 (등록 순서 의존 문제).
     * {@code setBeanFactory}는 BFPP(ConfigurationClassPostProcessor) 실행 이후에 호출되므로
     * 모든 BeanDefinition이 이미 등록된 상태 — 안전하게 사전 순회 가능.
     *
     * <p>이 시점에서 Aspect 빈을 {@code getBean()}으로 즉시 생성하면 순환 의존 위험이 있으므로,
     * BeanDefinition에서 {@code beanClass}만 읽어 클래스 정보로만 registry에 등록한다.
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        // registry는 생성자에서 final 초기화 — setBeanFactory 진입 시점에 안전 사용 가능
        this.sharedInterceptor = new AdviceInterceptor(beanFactory, registry);
        // @Aspect BD 사전 수집 — two-pass: 등록 순서 무관하게 모든 Aspect 미리 등록
        preRegisterAspects(beanFactory);
    }

    /**
     * BeanDefinition을 순회하여 {@code @Aspect} 클래스를 registry에 미리 등록한다.
     * 빈 이름은 {@code aspectBeanName}으로, 실제 advice 호출 시 {@code beanFactory.getBean(name)}으로
     * 인스턴스를 조회하므로 이 시점에서 실제 빈 생성이 필요 없다.
     */
    private void preRegisterAspects(BeanFactory bf) {
        if (!(bf instanceof ConfigurableListableBeanFactory clbf)) return;
        for (String name : clbf.getBeanDefinitionNames()) {
            Class<?> beanClass = clbf.getBeanDefinition(name).getBeanClass();
            if (beanClass != null && beanClass.isAnnotationPresent(Aspect.class)) {
                registry.register(name, beanClass);
            }
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;  // 자기 참조 격리

        if (bean.getClass().isAnnotationPresent(Aspect.class)) {
            registry.register(beanName, bean.getClass());
            return bean;
        }

        if (!registry.findAnyApplicable(bean.getClass())) return bean;

        Class<?> originalClass = bean.getClass();
        if (Modifier.isFinal(originalClass.getModifiers())) {
            throw new IllegalArgumentException(
                    "Cannot subclass final class: " + originalClass.getName()
                            + ". Remove final or relocate @Loggable");
        }

        Class<?> enhanced = createEnhancedSubclass(originalClass);
        Object newInstance = newInstance(enhanced);
        copyFields(bean, newInstance, originalClass);
        return newInstance;
    }

    private Class<?> createEnhancedSubclass(Class<?> originalClass) {
        try {
            return new ByteBuddy()
                    .subclass(originalClass)
                    // byte-buddy 정적 matcher는 registry(런타임 상태)를 참조 불가 — 모든 메서드 위임 후 인터셉터에서 분기
                    .method(ElementMatchers.any())
                    .intercept(MethodDelegation.to(sharedInterceptor))
                    .make()
                    .load(originalClass.getClassLoader(),
                            ClassLoadingStrategy.UsingLookup.of(
                                    MethodHandles.privateLookupIn(originalClass, MethodHandles.lookup())))
                    .getLoaded();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot enhance " + originalClass.getName(), e);
        }
    }

    private Object newInstance(Class<?> enhancedClass) {
        try {
            return enhancedClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot enhance " + enhancedClass.getSuperclass().getName()
                            + ": requires accessible no-arg constructor", e);
        }
    }

    /**
     * 복사 시작 전 상속 체인 전체를 선검사 — final 필드 발견 시 *부분 복사 없이* 즉시 throw.
     * pre-check가 통과한 이후 copyFields 본문에서는 final 검사 불필요.
     */
    private void validateNoFinalFields(Class<?> declaredClass) {
        Class<?> c = declaredClass;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isFinal(f.getModifiers())) {
                    throw new IllegalStateException(
                            "Cannot copy final field " + f.getName()
                                    + " on enhanced " + declaredClass.getName()
                                    + ". Remove final or use constructor injection (Phase 2C+)");
                }
            }
            c = c.getSuperclass();
        }
    }

    private void copyFields(Object source, Object target, Class<?> declaredClass) {
        validateNoFinalFields(declaredClass);  // 사전 검사: 부분 복사 상태로 throw하지 않도록 선행
        Class<?> c = declaredClass;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    f.set(target, f.get(source));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot copy field " + f.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
    }
}
