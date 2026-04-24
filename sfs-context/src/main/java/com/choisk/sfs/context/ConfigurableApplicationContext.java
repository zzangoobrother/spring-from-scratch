package com.choisk.sfs.context;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;

/**
 * 설정 가능한 ApplicationContext. refresh/close 라이프사이클 + BFPP 등록 + JVM shutdown hook.
 *
 * <p>{@link AutoCloseable} 채택으로 try-with-resources 사용 가능.
 *
 * <p>Spring 원본: {@code ConfigurableApplicationContext}.
 */
public interface ConfigurableApplicationContext extends ApplicationContext, AutoCloseable {

    /** 컨테이너 초기화 — 8단계 템플릿 메서드. 한 번만 호출 가능. */
    void refresh();

    /** 컨테이너 종료. idempotent. */
    @Override
    void close();

    boolean isActive();

    /** JVM 종료 시 자동으로 {@link #close()}를 호출하도록 hook 등록. idempotent. */
    void registerShutdownHook();

    /** 내부 BeanFactory 노출 (BFPP가 BeanDefinition을 수정할 수 있도록). */
    ConfigurableListableBeanFactory getBeanFactory();

    /** refresh() 5단계에서 호출될 BFPP를 등록. */
    void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);
}
