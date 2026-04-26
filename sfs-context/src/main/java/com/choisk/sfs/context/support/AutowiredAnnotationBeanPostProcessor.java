package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DependencyDescriptor;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.PropertyValues;
import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.core.ReflectionUtils;

/**
 * {@code @Autowired} 애노테이션이 붙은 필드에 의존성을 주입하는 BeanPostProcessor 단순판.
 * <p>{@link InstantiationAwareBeanPostProcessor#postProcessProperties}에서 빈의 상속 계층 전체를
 * 탐색하며 {@code @Autowired}가 붙은 필드를 {@link ConfigurableListableBeanFactory#resolveDependency}로 채운다.
 * <p>{@link ReflectionUtils#doWithFields}로 상속 계층 traversal을 일원화하여
 * 부모 클래스에 선언된 {@code @Autowired} 필드도 올바르게 주입한다.
 * <p>필드 주입만 지원 (세터 주입·생성자 주입은 학습 범위 보류).
 */
public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    /** 의존성 해석에 사용할 빈 팩토리 — 인터페이스 의존 (AOP 서브클래스 등장 시 ClassCastException 사전 차단) */
    private final ConfigurableListableBeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 빈의 상속 계층 전체를 탐색하며 {@code @Autowired}가 붙은 필드에 의존성을 주입한다.
     * {@link ReflectionUtils#doWithFields}를 사용해 자기 클래스 및 부모 클래스 필드를 모두 처리한다.
     *
     * @param pvs      기존 PropertyValues (변경 없이 그대로 반환)
     * @param bean     주입 대상 빈 인스턴스
     * @param beanName 빈 이름 (resolveDependency 요청자 식별용)
     * @return 변경되지 않은 pvs
     */
    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            Autowired autowired = field.getAnnotation(Autowired.class);
            if (autowired == null) {
                return;
            }
            DependencyDescriptor desc = new DependencyDescriptor(
                    field.getType(), autowired.required(), field.getName());
            Object dep = beanFactory.resolveDependency(desc, beanName);
            if (dep == null) {
                // required=false + 미매칭은 null 반환이 정상 — 이 필드는 건드리지 않음
                return;
            }
            ReflectionUtils.setField(field, bean, dep);
        });
        return pvs;
    }
}
