package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.BeanFactoryAware;
import com.choisk.sfs.beans.BeanPostProcessor;
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
 */
public class AspectEnhancingBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;
    private final AspectRegistry registry = new AspectRegistry();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
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
                    .method(ElementMatchers.any())  // 매칭 검사는 인터셉터 안에서 (registry.findApplicable)
                    .intercept(MethodDelegation.to(new AdviceInterceptor(beanFactory, registry)))
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

    private void copyFields(Object source, Object target, Class<?> declaredClass) {
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
