package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanDefinitionRegistry;

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
            BeanDefinitionMetadataApplier.apply(bd, c);
            registry.registerBeanDefinition(nameGenerator.generate(c), bd);
        }
    }
}
