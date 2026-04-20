package com.choisk.sfs.core;

public final class FactoryBeanNotInitializedException extends BeansException {

    public FactoryBeanNotInitializedException(String factoryBeanName) {
        super("FactoryBean '%s' returned null from getObject()".formatted(factoryBeanName));
    }
}
