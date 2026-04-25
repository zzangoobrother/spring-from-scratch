package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.DependencyDescriptor;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.PropertyValues;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Autowired;

import java.lang.reflect.Field;

/**
 * {@code @Autowired} 애노테이션이 붙은 필드에 의존성을 주입하는 BeanPostProcessor 단순판.
 * <p>{@link InstantiationAwareBeanPostProcessor#postProcessProperties}에서 빈의 모든 필드를 순회하며
 * {@code @Autowired}가 붙은 필드를 {@link DefaultListableBeanFactory#resolveDependency}로 채운다.
 * <p>필드 주입만 지원 (세터 주입·생성자 주입은 학습 범위 보류).
 */
public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    /** 의존성 해석에 사용할 빈 팩토리 */
    private final DefaultListableBeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 빈의 모든 선언 필드를 순회하며 {@code @Autowired}가 붙은 필드에 의존성을 주입한다.
     *
     * @param pvs      기존 PropertyValues (변경 없이 그대로 반환)
     * @param bean     주입 대상 빈 인스턴스
     * @param beanName 빈 이름 (resolveDependency 요청자 식별용)
     * @return 변경되지 않은 pvs
     */
    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            Autowired autowired = field.getAnnotation(Autowired.class);
            if (autowired == null) {
                // @Autowired가 없는 필드는 건너뜀
                continue;
            }
            DependencyDescriptor desc = new DependencyDescriptor(
                    field.getType(), autowired.required(), field.getName());
            Object dep = beanFactory.resolveDependency(desc, beanName);
            if (dep == null) {
                // required=false + 매칭 빈 없음 → 건너뜀 (예외 없이 다음 필드로)
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(bean, dep);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                        "Failed to inject @Autowired field '" + field.getName()
                        + "' in " + bean.getClass().getName(), e);
            }
        }
        return pvs;
    }
}
