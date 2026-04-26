package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class BeanMethodInterceptorTest {

    static class TestConfig {
        @Bean
        public Object user() { return new Object(); }

        @Bean(name = "customAccount")
        public Object account() { return new Object(); }
    }

    @Test
    void interceptReturnsCachedBeanWhenContainerHasIt() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        Object cachedBean = new Object();
        factory.registerSingleton("user", cachedBean);

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("user");
        Callable<Object> superCall = () -> {
            throw new AssertionError("superCall은 캐시 hit 시 호출되면 안 됨");
        };

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result).isSameAs(cachedBean);
    }

    @Test
    void interceptCallsSuperWhenBeanNotCached() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("user");
        Object freshBean = new Object();
        Callable<Object> superCall = () -> freshBean;

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result)
                .as("캐시 miss 시 superCall 결과를 그대로 반환")
                .isSameAs(freshBean);
    }

    @Test
    void interceptUsesBeanAnnotationNameOverride() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        Object cachedBean = new Object();
        factory.registerSingleton("customAccount", cachedBean);

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("account");
        Callable<Object> superCall = () -> {
            throw new AssertionError("@Bean(name=\"customAccount\")로 등록된 빈을 찾아야 함");
        };

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result)
                .as("@Bean(name=\"customAccount\") 처리 — 메서드명이 아닌 name 어노테이션 값 우선")
                .isSameAs(cachedBean);
    }
}
