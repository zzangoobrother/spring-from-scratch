package com.choisk.sfs.context.support;

import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.context.annotation.Service;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Spring 기본 정책: 명시 value가 있으면 그것, 없으면 클래스 단순명을 첫 글자만 소문자로.
 * <p>예: {@code com.x.MyService} → {@code "myService"}.
 */
public class AnnotationBeanNameGenerator implements BeanNameGenerator {

    /**
     * 우선순위 순서를 고정해 결정론적 이름 선택을 보장.
     * 향후 stereotype 추가 시 이 목록에만 추가하면 된다.
     */
    private static final List<Class<? extends Annotation>> STEREOTYPES = List.of(
            Component.class, Service.class, Repository.class, Controller.class, Configuration.class
    );

    @Override
    public String generate(Class<?> beanClass) {
        String explicit = explicitName(beanClass);
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return defaultName(beanClass);
    }

    private String explicitName(Class<?> c) {
        for (Class<? extends Annotation> type : STEREOTYPES) {
            String v = valueOf(c, type);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private String valueOf(Class<?> c, Class<? extends Annotation> annoType) {
        Annotation a = c.getAnnotation(annoType);
        if (a == null) return null;
        try {
            Method m = annoType.getMethod("value");
            Object v = m.invoke(a);
            return (v instanceof String s) ? s : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private String defaultName(Class<?> c) {
        String simple = c.getSimpleName();
        if (simple.isEmpty()) return simple;
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
