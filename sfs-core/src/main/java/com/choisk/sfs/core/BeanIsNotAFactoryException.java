package com.choisk.sfs.core;

public final class BeanIsNotAFactoryException extends BeansException {

    public BeanIsNotAFactoryException(String beanName, Class<?> actualType) {
        super("Bean '%s' is not a FactoryBean (actual type: %s)".formatted(
                beanName, actualType.getName()));
    }
}
