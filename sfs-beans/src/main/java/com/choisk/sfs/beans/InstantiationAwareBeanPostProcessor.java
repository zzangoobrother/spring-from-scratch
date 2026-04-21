package com.choisk.sfs.beans;

/**
 * 인스턴스화 자체를 가로채거나 프로퍼티 주입을 후킹하는 BPP.
 * <p>@Autowired 필드 주입 로직이 여기에 꽂힘 (AutowiredAnnotationBeanPostProcessor).
 */
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {

    /**
     * 반환값이 null이 아니면 그 객체를 빈으로 사용 (생성자 스킵).
     */
    default Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
        return null;
    }

    default boolean postProcessAfterInstantiation(Object bean, String beanName) {
        return true;
    }

    /**
     * populateBean 도중 호출. 여기서 @Autowired 필드를 채움.
     */
    default PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        return pvs;
    }
}
