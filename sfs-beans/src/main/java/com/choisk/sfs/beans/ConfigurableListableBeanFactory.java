package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory, BeanDefinitionRegistry {

    void preInstantiateSingletons();
}
