package com.choisk.sfs.beans;

import java.util.List;

public interface ConfigurableBeanFactory extends HierarchicalBeanFactory {

    void registerSingleton(String name, Object bean);

    void addBeanPostProcessor(BeanPostProcessor processor);

    int getBeanPostProcessorCount();

    /** 등록된 BPP 목록 조회 (AnnotationConfigUtils 멱등성 검사 등에서 사용). */
    List<BeanPostProcessor> getBeanPostProcessors();

    void destroySingletons();
}
