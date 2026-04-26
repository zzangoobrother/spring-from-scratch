package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * {@code @Configuration} 클래스의 byte-buddy 서브클래스를 생성하여
 * 모든 {@code @Bean} 메서드를 {@link BeanMethodInterceptor}로 가로채도록 한다.
 *
 * <p>Spring 본가의 CGLIB 기반 enhance와 동일한 메커니즘을 byte-buddy로 구현.
 * 인터페이스가 아닌 *클래스 자체*의 서브클래스이므로 {@code final} 클래스/메서드는
 * enhance 불가 — 학습 범위 축소판은 이 제약을 수용 ({@code null}/`final` 입력 시 명시적 예외 발생, 동일 클래스 중복 enhance 캐시는 후속 phase로 보류).
 */
public class ConfigurationClassEnhancer {

    private final ConfigurableBeanFactory beanFactory;

    public ConfigurationClassEnhancer(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Class<?> enhance(Class<?> configClass) {
        Objects.requireNonNull(configClass, "configClass cannot be null");
        if (Modifier.isFinal(configClass.getModifiers())) {
            throw new IllegalArgumentException(
                    "@Configuration 클래스는 final이면 안 됨 — byte-buddy 서브클래싱 불가: " + configClass.getName());
        }
        // 인터셉터는 stateless(beanFactory 필드만) — 호출마다 신규 생성하지만 메모리 부담 없음.
        // enhancer 필드로 보유하지 않는 이유: 후속 phase에서 stateful 인터셉터(예: 호출 카운터)로 확장될 여지를 막지 않기 위함.
        // UsingLookup: Java 9+ 모듈 시스템에서 별도 ClassLoader 없이 configClass 패키지에 직접 define.
        // WRAPPER/CHILD_FIRST 는 ByteArrayClassLoader를 사용해 unnamed module 내부 클래스 접근이 막힘.
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(configClass, MethodHandles.lookup());
            return new ByteBuddy()
                    .subclass(configClass)
                    .method(ElementMatchers.isAnnotatedWith(Bean.class))
                    .intercept(MethodDelegation.to(new BeanMethodInterceptor(beanFactory)))
                    .make()
                    .load(configClass.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(lookup))
                    .getLoaded();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("enhance 서브클래스 로딩 실패: " + configClass.getName(), e);
        }
    }
}
