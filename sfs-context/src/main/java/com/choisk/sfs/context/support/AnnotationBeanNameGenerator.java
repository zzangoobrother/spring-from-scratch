package com.choisk.sfs.context.support;

import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.context.annotation.Configuration;

/**
 * Spring 기본 정책: 명시 value가 있으면 그것, 없으면 클래스 단순명을 첫 글자만 소문자로.
 * <p>예: {@code com.x.MyService} → {@code "myService"}.
 */
public class AnnotationBeanNameGenerator implements BeanNameGenerator {

    @Override
    public String generate(Class<?> beanClass) {
        String explicit = explicitName(beanClass);
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return defaultName(beanClass);
    }

    private String explicitName(Class<?> c) {
        Component comp = c.getAnnotation(Component.class);
        if (comp != null && !comp.value().isEmpty()) return comp.value();
        Service svc = c.getAnnotation(Service.class);
        if (svc != null && !svc.value().isEmpty()) return svc.value();
        Repository rep = c.getAnnotation(Repository.class);
        if (rep != null && !rep.value().isEmpty()) return rep.value();
        Controller ctrl = c.getAnnotation(Controller.class);
        if (ctrl != null && !ctrl.value().isEmpty()) return ctrl.value();
        Configuration cfg = c.getAnnotation(Configuration.class);
        if (cfg != null && !cfg.value().isEmpty()) return cfg.value();
        return null;
    }

    private String defaultName(Class<?> c) {
        String simple = c.getSimpleName();
        if (simple.isEmpty()) return simple;
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
