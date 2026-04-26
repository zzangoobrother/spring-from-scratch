package com.choisk.sfs.beans;

import java.util.List;

public interface ConfigurableBeanFactory extends HierarchicalBeanFactory {

    void registerSingleton(String name, Object bean);

    void addBeanPostProcessor(BeanPostProcessor processor);

    int getBeanPostProcessorCount();

    /** 등록된 BPP 목록 조회 (AnnotationConfigUtils 멱등성 검사 등에서 사용). */
    List<BeanPostProcessor> getBeanPostProcessors();

    void destroySingletons();

    /**
     * 소멸 콜백 등록. {@code @PreDestroy} 메서드를 close() 시점에 호출하기 위해
     * BPP 처리기({@link com.choisk.sfs.context.support.CommonAnnotationBeanPostProcessor})가 사용.
     */
    void registerDisposableBean(String name, Runnable callback);

    /**
     * 완성된 싱글톤이 캐시에 있는지 확인. {@code BeanMethodInterceptor}가
     * {@code @Bean} 메서드 직접 호출 시 컨테이너 라우팅 가능 여부 판단에 사용.
     */
    boolean containsSingleton(String name);
}
