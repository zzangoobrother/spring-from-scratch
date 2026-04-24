package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanDefinitionRegistry;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;
import com.choisk.sfs.core.AnnotationUtils;
import com.choisk.sfs.core.ClassPathScanner;

/**
 * 패키지를 스캔하여 {@code @Component} 메타-애노테이션을 가진 클래스를 BeanDefinition으로 등록.
 *
 * <p>Spring 원본: {@code ClassPathBeanDefinitionScanner}.
 */
public class ClassPathBeanDefinitionScanner {

    private final BeanDefinitionRegistry registry;
    private final BeanNameGenerator nameGenerator;
    // Plan 라인 2056의 static Iterable 전제와 달리, 실제 ClassPathScanner는 인스턴스 메서드 기반 — 필드로 보유
    private final ClassPathScanner classPathScanner = new ClassPathScanner();

    public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry) {
        this(registry, new AnnotationBeanNameGenerator());
    }

    public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, BeanNameGenerator gen) {
        this.registry = registry;
        this.nameGenerator = gen;
    }

    public int scan(String... basePackages) {
        int count = 0;
        for (String pkg : basePackages) {
            for (ClassPathScanner.ClassInfo info : classPathScanner.scan(pkg)) {
                Class<?> clazz = loadClass(info.className());
                if (clazz == null) continue;
                if (clazz.isInterface() || clazz.isAnnotation()) continue;
                if (!AnnotationUtils.isAnnotated(clazz, Component.class)) continue;
                BeanDefinition bd = new BeanDefinition(clazz);
                applyScope(bd, clazz);
                applyLazy(bd, clazz);
                applyPrimary(bd, clazz);
                registry.registerBeanDefinition(nameGenerator.generate(clazz), bd);
                count++;
            }
        }
        return count;
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // 스캔 대상이 현재 ClassLoader에서 로드 불가 — skip (학습 범위)
            return null;
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
