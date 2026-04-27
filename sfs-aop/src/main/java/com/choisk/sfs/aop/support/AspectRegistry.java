package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Before;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 컨테이너 전체의 advice 레지스트리. BPP의 *내부 상태* — 빈으로 등록 안 함.
 * <p>BPP가 {@code @Aspect} 빈 발견 시 {@link #register} 호출, 일반 빈 매칭 시 {@link #findApplicable} lookup.
 */
public class AspectRegistry {

    private final List<AdviceInfo> advices = new ArrayList<>();

    /**
     * {@code @Aspect} 빈의 메서드를 순회하며 {@code @Around}/{@code @Before}/{@code @After} 발견 시 advice 등록.
     */
    public void register(String aspectBeanName, Class<?> aspectClass) {
        for (Method m : aspectClass.getDeclaredMethods()) {
            Around around = m.getAnnotation(Around.class);
            if (around != null) {
                advices.add(new AdviceInfo(AdviceType.AROUND, around.value(), m, aspectBeanName));
            }
            Before before = m.getAnnotation(Before.class);
            if (before != null) {
                advices.add(new AdviceInfo(AdviceType.BEFORE, before.value(), m, aspectBeanName));
            }
            After after = m.getAnnotation(After.class);
            if (after != null) {
                advices.add(new AdviceInfo(AdviceType.AFTER, after.value(), m, aspectBeanName));
            }
        }
    }

    /**
     * 메서드의 매칭 advice 반환. 메서드 단위 애노테이션 우선, 없으면 declaring 클래스 단위 매칭.
     */
    public List<AdviceInfo> findApplicable(Method targetMethod) {
        List<AdviceInfo> result = new ArrayList<>();
        for (AdviceInfo info : advices) {
            Class<? extends Annotation> ann = info.targetAnnotation();
            if (targetMethod.isAnnotationPresent(ann)
                    || targetMethod.getDeclaringClass().isAnnotationPresent(ann)) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 클래스에 매칭 advice가 *하나라도* 있는지 — BPP의 enhance 결정에 사용 (전 메서드 순회 회피).
     */
    public boolean findAnyApplicable(Class<?> targetClass) {
        for (AdviceInfo info : advices) {
            Class<? extends Annotation> ann = info.targetAnnotation();
            if (targetClass.isAnnotationPresent(ann)) return true;
            for (Method m : targetClass.getDeclaredMethods()) {
                if (m.isAnnotationPresent(ann)) return true;
            }
        }
        return false;
    }
}
