package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.FactoryBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.BeanIsNotAFactoryException;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 32a: FactoryBean 통합 테스트.
 * getBean(name) → 제품 객체, getBean("&name") → FactoryBean 자신 반환 검증.
 */
class FactoryBeanTest {

    static class DateFormatterFactory implements FactoryBean<DateTimeFormatter> {
        public DateTimeFormatter getObject() {
            return DateTimeFormatter.ISO_LOCAL_DATE;
        }
        public Class<?> getObjectType() { return DateTimeFormatter.class; }
    }

    static class PlainBean {}

    @Test
    void getBeanWithoutPrefixReturnsProduct() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object product = factory.getBean("fmt");
        assertThat(product).isInstanceOf(DateTimeFormatter.class);
    }

    @Test
    void getBeanWithPrefixReturnsFactoryItself() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object itself = factory.getBean("&fmt");
        assertThat(itself).isInstanceOf(DateFormatterFactory.class);
    }

    @Test
    void prefixOnNonFactoryThrows() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("plain", new BeanDefinition(PlainBean.class));
        assertThatThrownBy(() -> factory.getBean("&plain"))
                .isInstanceOf(BeanIsNotAFactoryException.class);
    }

    @Test
    void singletonFactoryBeanCachesProduct() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object first = factory.getBean("fmt");
        Object second = factory.getBean("fmt");
        assertThat(first).isSameAs(second);
    }
}
