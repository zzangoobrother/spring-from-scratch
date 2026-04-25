package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;

import java.lang.reflect.Method;

/**
 * {@link PostConstruct} 애노테이션이 붙은 메서드를 BPP:before 시점에 호출하는 처리기.
 * G2에서 {@code @PreDestroy} 처리가 추가될 예정.
 */
public class CommonAnnotationBeanPostProcessor implements BeanPostProcessor {

    /** G2에서 registerDisposableBean 호출에 활용 */
    private final DefaultListableBeanFactory beanFactory;

    public CommonAnnotationBeanPostProcessor(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 빈 초기화 전, {@code @PostConstruct} 애노테이션이 붙은 메서드를 리플렉션으로 호출한다.
     * 호출 실패 시 빈 생성 자체가 실패해야 하므로 {@link RuntimeException}으로 래핑하여 던진다.
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        for (Method m : bean.getClass().getDeclaredMethods()) {
            if (!m.isAnnotationPresent(PostConstruct.class)) {
                continue;
            }
            m.setAccessible(true);
            try {
                m.invoke(bean);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "@PostConstruct 호출 실패 — beanName=" + beanName + ", method=" + m.getName(), e);
            }
        }
        return bean;
    }

    /**
     * G2에서 {@code @PreDestroy} 처리로 보강 예정. 현재는 빈을 그대로 반환.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
