package com.choisk.sfs.core;

/**
 * 빈 생성 과정에서 발생한 예외의 기본 타입.
 * <p>non-sealed: Phase 2+에서 {@code BeanCurrentlyInCreationException},
 * {@code UnsatisfiedDependencyException} 등 서브타입 추가 가능.
 */
public non-sealed class BeanCreationException extends BeansException {

    private final String beanName;

    public BeanCreationException(String beanName, String message) {
        super("Error creating bean '%s': %s".formatted(beanName, message));
        this.beanName = beanName;
    }

    public BeanCreationException(String beanName, String message, Throwable cause) {
        super("Error creating bean '%s': %s".formatted(beanName, message), cause);
        this.beanName = beanName;
    }

    public String getBeanName() {
        return beanName;
    }
}
