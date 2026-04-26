package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.PreDestroy;
import com.choisk.sfs.core.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PostConstruct} / {@link PreDestroy} 애노테이션 처리기.
 *
 * <ul>
 *   <li>BPP:before — {@code @PostConstruct} 메서드를 리플렉션으로 즉시 호출</li>
 *   <li>BPP:after  — {@code @PreDestroy} 메서드를 {@code registerDisposableBean}에 등록.
 *       소멸 순서(LIFO)는 {@code DefaultSingletonBeanRegistry.destroySingletons}가 보장하므로
 *       본 처리기는 <em>등록만</em> 책임진다.</li>
 * </ul>
 * <p>{@link ReflectionUtils#doWithMethods}로 상속 계층 traversal을 일원화하여
 * 부모 클래스에 선언된 {@code @PostConstruct} / {@code @PreDestroy} 메서드도 올바르게 처리한다.
 */
public class CommonAnnotationBeanPostProcessor implements BeanPostProcessor {

    /** @PreDestroy 등록 시 beanFactory.registerDisposableBean 호출에 활용 — 인터페이스 의존 (AOP 서브클래스 등장 시 ClassCastException 사전 차단) */
    private final ConfigurableBeanFactory beanFactory;

    public CommonAnnotationBeanPostProcessor(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 빈 초기화 전, 상속 계층 전체에서 {@code @PostConstruct} 애노테이션이 붙은 메서드를 리플렉션으로 호출한다.
     * {@link ReflectionUtils#doWithMethods}를 사용해 자기 클래스 및 부모 클래스 메서드를 모두 처리한다.
     * 호출 실패 시 {@link ReflectionUtils#invokeMethod}가 {@link IllegalStateException}으로 래핑하여 던지므로
     * 빈 생성이 중단된다.
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        ReflectionUtils.doWithMethods(bean.getClass(), m -> {
            if (!m.isAnnotationPresent(PostConstruct.class)) {
                return;
            }
            ReflectionUtils.invokeMethod(m, bean);
        });
        return bean;
    }

    /**
     * 빈 초기화 후, 상속 계층 전체에서 {@code @PreDestroy} 애노테이션이 붙은 메서드들을 소멸 콜백으로 등록한다.
     * {@link ReflectionUtils#doWithMethods}를 사용해 자기 클래스 및 부모 클래스 메서드를 모두 처리한다.
     * LIFO 순서는 {@code DefaultSingletonBeanRegistry.destroySingletons}가 보장하므로
     * 본 메서드는 등록만 수행한다.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        List<Method> preDestroyMethods = new ArrayList<>();
        ReflectionUtils.doWithMethods(bean.getClass(), m -> {
            if (m.isAnnotationPresent(PreDestroy.class)) {
                m.setAccessible(true);
                preDestroyMethods.add(m);
            }
        });
        if (!preDestroyMethods.isEmpty()) {
            beanFactory.registerDisposableBean(beanName, () -> {
                for (Method m : preDestroyMethods) {
                    try {
                        m.invoke(bean);
                    } catch (ReflectiveOperationException e) {
                        System.err.println("@PreDestroy 호출 실패 — beanName=" + beanName
                                + ", method=" + m.getName() + ": " + e);
                    }
                }
            });
        }
        return bean;
    }
}
