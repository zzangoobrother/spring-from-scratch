package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.Scope;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import com.choisk.sfs.core.NoUniqueBeanDefinitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultListableBeanFactoryTest {

    static class Greeter {
        private String message = "hello";
        public String greet() { return message; }
    }

    @Test
    void registerAndGetSimpleBean() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class));
        var g = factory.getBean("g", Greeter.class);
        assertThat(g.greet()).isEqualTo("hello");
    }

    @Test
    void getBeanByType_singleCandidate() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class));
        assertThat(factory.getBean(Greeter.class)).isNotNull();
    }

    @Test
    void getBeanByType_multipleCandidates_requiresPrimaryOrQualifier() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("b", new BeanDefinition(Greeter.class));
        assertThatThrownBy(() -> factory.getBean(Greeter.class))
                .isInstanceOf(NoUniqueBeanDefinitionException.class);
    }

    @Test
    void primaryWins() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("b", new BeanDefinition(Greeter.class).setPrimary(true));
        assertThat(factory.getBean(Greeter.class)).isNotNull();
    }

    @Test
    void prototypeNewInstanceEachGet() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class).setScope(Scope.Prototype.INSTANCE));
        assertThat(factory.getBean("g")).isNotSameAs(factory.getBean("g"));
    }

    @Test
    void preInstantiateSingletonsCreatesAllEagerBeans() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g1", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("g2", new BeanDefinition(Greeter.class).setLazyInit(true));
        factory.preInstantiateSingletons();
        assertThat(factory.containsSingleton("g1")).isTrue();
        assertThat(factory.containsSingleton("g2")).isFalse();
    }

    @Test
    void noSuchBeanHasHelpfulMessage() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("greater", new BeanDefinition(Greeter.class));
        assertThatThrownBy(() -> factory.getBean("greeter"))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("greeter")
                .hasMessageContaining("Possible solutions");
    }
}
