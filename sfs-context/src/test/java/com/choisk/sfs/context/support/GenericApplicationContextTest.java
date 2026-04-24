package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericApplicationContextTest {

    static class Foo {}

    @Test
    void registerBeanDefinitionThenRefreshSucceeds() {
        var ctx = new GenericApplicationContext();
        ctx.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        ctx.refresh();
        assertThat(ctx.getBean("foo")).isInstanceOf(Foo.class);
    }

    @Test
    void refreshTwiceThrows() {
        var ctx = new GenericApplicationContext();
        ctx.refresh();
        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already called");
    }

    @Test
    void externalBeanFactoryConstructorAccepted() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        var ctx = new GenericApplicationContext(bf);
        ctx.refresh();
        assertThat(ctx.getBean("foo")).isInstanceOf(Foo.class);
    }
}
