package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BeanDefinitionTest {
    static class Sample {}

    @Test
    void defaultsAreSingletonNonLazy() {
        var def = new BeanDefinition(Sample.class);
        assertThat(def.getScope()).isEqualTo(Scope.Singleton.INSTANCE);
        assertThat(def.isLazyInit()).isFalse();
        assertThat(def.isPrimary()).isFalse();
        assertThat(def.getAutowireMode()).isEqualTo(AutowireMode.NO);
    }

    @Test
    void settersAreFluent() {
        var def = new BeanDefinition(Sample.class)
                .setScope(Scope.Prototype.INSTANCE)
                .setLazyInit(true)
                .setPrimary(true)
                .setQualifier("main")
                .setInitMethodName("init")
                .setDestroyMethodName("close");
        assertThat(def.getScope()).isEqualTo(Scope.Prototype.INSTANCE);
        assertThat(def.isLazyInit()).isTrue();
        assertThat(def.isPrimary()).isTrue();
        assertThat(def.getQualifier()).isEqualTo("main");
        assertThat(def.getInitMethodName()).isEqualTo("init");
        assertThat(def.getDestroyMethodName()).isEqualTo("close");
    }

    @Test
    void factoryBeanBasedDefinition() {
        var def = new BeanDefinition(Sample.class)
                .setFactoryBeanName("config")
                .setFactoryMethodName("buildSample");
        assertThat(def.getFactoryBeanName()).isEqualTo("config");
        assertThat(def.getFactoryMethodName()).isEqualTo("buildSample");
    }

    @Test
    void propertyValuesIsLazilyInitialized() {
        var def = new BeanDefinition(Sample.class);
        assertThat(def.getPropertyValues()).isNotNull();
        assertThat(def.getPropertyValues().isEmpty()).isTrue();
    }
}
