package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * {@code @Configuration} 클래스의 byte-buddy 서브클래스를 생성하여
 * 모든 {@code @Bean} 메서드를 {@link BeanMethodInterceptor}로 가로채도록 한다.
 *
 * <p>Spring 본가의 CGLIB 기반 enhance와 동일한 메커니즘을 byte-buddy로 구현.
 * 인터페이스가 아닌 *클래스 자체*의 서브클래스이므로 {@code final} 클래스/메서드는
 * enhance 불가 — 학습 범위 축소판은 이 제약을 수용 (검증 task는 후속 phase로 보류).
 */
public class ConfigurationClassEnhancer {

    private final ConfigurableBeanFactory beanFactory;

    public ConfigurationClassEnhancer(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Class<?> enhance(Class<?> configClass) {
        return new ByteBuddy()
                .subclass(configClass)
                .method(ElementMatchers.isAnnotatedWith(Bean.class))
                .intercept(MethodDelegation.to(new BeanMethodInterceptor(beanFactory)))
                .make()
                .load(configClass.getClassLoader())
                .getLoaded();
    }
}
