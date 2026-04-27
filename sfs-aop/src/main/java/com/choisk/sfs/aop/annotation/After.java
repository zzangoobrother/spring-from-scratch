package com.choisk.sfs.aop.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * After advice — 진짜 메서드 종료 *후* invoke. {@code try/finally}의 finally에 위치하여 *예외 시에도 호출*된다.
 * <p>advice 메서드 시그니처: {@code void name(JoinPoint jp)}.
 * 결과 *읽기*만 가능, 반환값 변형 X.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface After {
    Class<? extends Annotation> value();
}
