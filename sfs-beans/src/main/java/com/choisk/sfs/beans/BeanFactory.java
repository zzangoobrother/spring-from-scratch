package com.choisk.sfs.beans;

/**
 * 컨테이너 사용자가 주로 보는 최상위 인터페이스.
 * <p>Spring 원본: {@code org.springframework.beans.factory.BeanFactory}.
 */
public interface BeanFactory {

    String FACTORY_BEAN_PREFIX = "&";

    Object getBean(String name);

    <T> T getBean(String name, Class<T> requiredType);

    <T> T getBean(Class<T> requiredType);

    boolean containsBean(String name);

    boolean isSingleton(String name);

    boolean isPrototype(String name);

    Class<?> getType(String name);
}
