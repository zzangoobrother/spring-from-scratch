package com.choisk.sfs.beans;

/**
 * BeanDefinition을 등록/조회할 수 있는 저장소의 추상화. reader/scanner/BFPP가 이 타입을 통해
 * BeanFactory를 건드리고, BeanFactory 구현은 이 인터페이스를 구현해 BeanDefinition 저장소 역할도 겸한다.
 *
 * <p>Spring 원본: {@code org.springframework.beans.factory.support.BeanDefinitionRegistry}.
 */
public interface BeanDefinitionRegistry {

    void registerBeanDefinition(String name, BeanDefinition definition);

    BeanDefinition getBeanDefinition(String name);

    boolean containsBeanDefinition(String name);

    String[] getBeanDefinitionNames();

    int getBeanDefinitionCount();
}
