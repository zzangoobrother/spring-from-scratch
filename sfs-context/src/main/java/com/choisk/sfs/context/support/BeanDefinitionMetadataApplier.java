package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;

/**
 * {@code @Scope}/{@code @Lazy}/{@code @Primary} 메타정보를 BeanDefinition으로 옮기는 어댑터.
 * AnnotatedBeanDefinitionReader와 ClassPathBeanDefinitionScanner가 같은 로직을 공유하기 위해 분리.
 */
final class BeanDefinitionMetadataApplier {

    private BeanDefinitionMetadataApplier() {}

    /**
     * @Scope/@Lazy/@Primary 세 애노테이션을 한 번에 BeanDefinition에 반영한다.
     */
    static void apply(BeanDefinition bd, Class<?> c) {
        applyScope(bd, c);
        applyLazy(bd, c);
        applyPrimary(bd, c);
    }

    private static void applyScope(BeanDefinition bd, Class<?> c) {
        com.choisk.sfs.context.annotation.Scope s =
                c.getAnnotation(com.choisk.sfs.context.annotation.Scope.class);
        if (s != null) bd.setScope(com.choisk.sfs.beans.Scope.byName(s.value()));
    }

    private static void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazyInit(l.value());
    }

    private static void applyPrimary(BeanDefinition bd, Class<?> c) {
        if (c.isAnnotationPresent(Primary.class)) bd.setPrimary(true);
    }
}
