package com.choisk.sfs.beans;

/**
 * 3-level cache의 3차 팩토리가 실행될 때 호출되는 훅.
 * <p>AOP 프록시 조기 생성(Phase 2)이 여기에 꽂힘. 순환 참조 상황에서도
 * 다른 빈이 프록시를 참조하도록 보장.
 */
public interface SmartInstantiationAwareBeanPostProcessor
        extends InstantiationAwareBeanPostProcessor {

    /**
     * 조기 참조가 필요할 때 호출. 반환값이 다른 빈들에게 노출됨.
     */
    default Object getEarlyBeanReference(Object bean, String beanName) {
        return bean;
    }
}
