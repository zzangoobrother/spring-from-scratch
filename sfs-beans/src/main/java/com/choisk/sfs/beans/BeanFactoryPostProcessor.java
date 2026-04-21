package com.choisk.sfs.beans;

/**
 * 모든 싱글톤 인스턴스화 전에 BeanDefinition 자체를 수정할 수 있는 훅.
 * <p>@Configuration 클래스 처리(ConfigurationClassPostProcessor, Plan 1B)가 여기에 꽂힘.
 */
public interface BeanFactoryPostProcessor {

    void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory);
}
