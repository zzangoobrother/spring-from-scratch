package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory, BeanDefinitionRegistry {

    void preInstantiateSingletons();

    /**
     * 의존성 해석. {@link AutowiredAnnotationBeanPostProcessor}가
     * {@code @Autowired} 필드 주입에 사용.
     *
     * @param desc 의존성 메타 (타입 + required + name)
     * @param requestingBeanName 요청 빈 이름 (순환 참조 감지용)
     * @return 해석된 빈 또는 null (required=false + 미매칭)
     */
    Object resolveDependency(DependencyDescriptor desc, String requestingBeanName);
}
