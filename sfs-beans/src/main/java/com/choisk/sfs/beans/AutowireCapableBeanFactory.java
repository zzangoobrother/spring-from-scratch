package com.choisk.sfs.beans;

public interface AutowireCapableBeanFactory extends BeanFactory {

    Object createBean(Class<?> beanClass);

    void autowireBean(Object existingBean);

    Object initializeBean(Object existingBean, String beanName);

    Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName);

    Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName);
}
