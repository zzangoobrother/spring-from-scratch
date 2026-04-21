package com.choisk.sfs.beans;

import java.util.List;

/**
 * 빈 생성 중 전달되는 구조화된 컨텍스트. 예외 메시지/로깅에서 활용.
 */
public record BeanCreationContext(
        String beanName,
        Class<?> beanClass,
        CreationStage stage,
        List<String> creationChain
) {
    public BeanCreationContext withStage(CreationStage newStage) {
        return new BeanCreationContext(beanName, beanClass, newStage, creationChain);
    }
}
