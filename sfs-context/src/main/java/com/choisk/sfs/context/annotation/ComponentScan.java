package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 패키지 스캔 + @Component 빈 자동 등록을 선언하는 메타애노테이션.
 *
 * <p>{@code @Configuration} 클래스에 부착하면 {@link com.choisk.sfs.context.support.ConfigurationClassPostProcessor}가
 * BFPP 시점에 {@link com.choisk.sfs.context.support.ClassPathBeanDefinitionScanner}를 호출해
 * 지정 패키지의 {@code @Component} 보유 클래스를 BD로 등록한다.
 *
 * <p>{@code value()}와 {@code basePackages()}는 동의어 (Spring 본가 패턴).
 * 둘 다 비어있으면 *애노테이션 달린 클래스의 패키지*를 기본 스캔 — 본 phase는 *명시 지정만* 지원.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentScan {
    String[] value() default {};
    String[] basePackages() default {};
}
