package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationClassPostProcessorTest {

    @Configuration
    static class AppConfig {
        @Bean
        public String greeting() {
            return "hello";
        }
    }

    /**
     * postProcessBeanFactory 호출 후 @Bean 메서드가 factoryMethod BeanDefinition으로 등록되어야 함.
     * factoryBeanName = 원래 @Configuration 빈 이름, factoryMethodName = 메서드명.
     */
    @Test
    void registersBeanMethodAsFactoryMethodBeanDefinition() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));

        new ConfigurationClassPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.containsBeanDefinition("greeting")).isTrue();
        BeanDefinition bd = bf.getBeanDefinition("greeting");
        assertThat(bd.getFactoryBeanName()).isEqualTo("appConfig");
        assertThat(bd.getFactoryMethodName()).isEqualTo("greeting");
    }

    /**
     * factoryMethod BeanDefinition으로 등록된 빈을 getBean으로 꺼낼 때 실제 메서드 반환값이 나와야 함.
     * C1의 factoryMethod 분기(AbstractAutowireCapableBeanFactory)가 동작해야 PASS.
     */
    @Test
    void beanInstanceCreatedViaFactoryMethod() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));
        new ConfigurationClassPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.getBean("greeting")).isEqualTo("hello");
    }
}
