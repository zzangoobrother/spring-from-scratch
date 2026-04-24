package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;
import com.choisk.sfs.context.annotation.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedBeanDefinitionReaderTest {

    @Component static class Plain {}
    @Component @Scope("prototype") static class Proto {}
    @Component @Lazy static class LazyOne {}
    @Component @Primary static class PrimaryOne {}

    @Test
    void registerPlainCreatesSingletonDefinition() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(Plain.class);

        BeanDefinition bd = bf.getBeanDefinition("plain");
        assertThat(bd.getBeanClass()).isEqualTo(Plain.class);
        assertThat(bd.isSingleton()).isTrue();
        assertThat(bd.isLazyInit()).isFalse();
        assertThat(bd.isPrimary()).isFalse();
    }

    @Test
    void registerWithScopeCreatesPrototypeDefinition() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(Proto.class);

        BeanDefinition bd = bf.getBeanDefinition("proto");
        assertThat(bd.isPrototype()).isTrue();
    }

    @Test
    void registerWithLazyMarksLazy() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(LazyOne.class);

        assertThat(bf.getBeanDefinition("lazyOne").isLazyInit()).isTrue();
    }

    @Test
    void registerWithPrimaryMarksPrimary() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(PrimaryOne.class);

        assertThat(bf.getBeanDefinition("primaryOne").isPrimary()).isTrue();
    }
}
