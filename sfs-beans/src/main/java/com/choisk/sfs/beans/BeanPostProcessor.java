package com.choisk.sfs.beans;

/**
 * 초기화 전/후로 빈 인스턴스를 감싸는 훅. AOP 프록시가 여기에 꽂힘 (Phase 2).
 */
public interface BeanPostProcessor {

    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
