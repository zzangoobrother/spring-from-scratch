package com.choisk.sfs.aop.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Around advice — {@code value()}로 지정된 애노테이션이 부착된 메서드 호출을 *둘러싸는* 형태로 가로챈다.
 * <p>advice 메서드 시그니처: {@code Object name(ProceedingJoinPoint pjp) throws Throwable}.
 * {@code pjp.proceed()} 호출 시점/횟수/결과 변형을 모두 advice가 결정 (가장 강한 권한).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Around {
    Class<? extends Annotation> value();
}
