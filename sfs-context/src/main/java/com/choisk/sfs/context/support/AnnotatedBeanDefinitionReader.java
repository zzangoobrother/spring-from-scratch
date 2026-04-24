package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanDefinitionRegistry;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;

/**
 * 명시 등록 진입점. 클래스 객체에서 BeanDefinition을 생성하고
 * {@code @Scope}/{@code @Lazy}/{@code @Primary}를 적용한다.
 *
 * <p>Spring 원본: {@code AnnotatedBeanDefinitionReader}.
 */
public class AnnotatedBeanDefinitionReader {

    private final BeanDefinitionRegistry registry;
    private final BeanNameGenerator nameGenerator;

    public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry) {
        this(registry, new AnnotationBeanNameGenerator());
    }

    public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, BeanNameGenerator gen) {
        this.registry = registry;
        this.nameGenerator = gen;
    }

    public void register(Class<?>... componentClasses) {
        for (Class<?> c : componentClasses) {
            BeanDefinition bd = new BeanDefinition(c);
            applyScope(bd, c);
            applyLazy(bd, c);
            applyPrimary(bd, c);
            registry.registerBeanDefinition(nameGenerator.generate(c), bd);
        }
    }

    private void applyScope(BeanDefinition bd, Class<?> c) {
        com.choisk.sfs.context.annotation.Scope s =
                c.getAnnotation(com.choisk.sfs.context.annotation.Scope.class);
        if (s != null) bd.setScope(com.choisk.sfs.beans.Scope.byName(s.value()));
    }

    private void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazyInit(l.value());
    }

    private void applyPrimary(BeanDefinition bd, Class<?> c) {
        if (c.isAnnotationPresent(Primary.class)) bd.setPrimary(true);
    }
}
