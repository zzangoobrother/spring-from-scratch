package com.choisk.sfs.core;

/**
 * Spring From Scratch 컨테이너의 모든 예외 루트.
 * <p>sealed: 허용된 서브타입만 존재할 수 있다. 외부 확장이 필요하면 이 계층에 명시적 추가.
 * <p>Spring 원본: {@code org.springframework.beans.BeansException}.
 */
public abstract sealed class BeansException extends RuntimeException
        permits NoSuchBeanDefinitionException,
                NoUniqueBeanDefinitionException,
                BeanDefinitionStoreException,
                BeanCreationException,
                BeanNotOfRequiredTypeException,
                FactoryBeanNotInitializedException,
                BeanIsNotAFactoryException {

    protected BeansException(String message) {
        super(message);
    }

    protected BeansException(String message, Throwable cause) {
        super(message, cause);
    }
}
