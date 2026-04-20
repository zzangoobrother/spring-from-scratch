package com.choisk.sfs.core;

public final class BeanNotOfRequiredTypeException extends BeansException {

    public BeanNotOfRequiredTypeException(String beanName, Class<?> required, Class<?> actual) {
        super("Bean '%s' is of type %s, not %s".formatted(
                beanName, actual.getName(), required.getName()));
    }
}
