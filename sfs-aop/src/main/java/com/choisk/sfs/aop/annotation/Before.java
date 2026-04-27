package com.choisk.sfs.aop.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Before advice — 진짜 메서드 *호출 직전*에 invoke. 메서드 *차단 권한*은 가짐 (advice 자체가 throw 시).
 * <p>advice 메서드 시그니처: {@code void name(JoinPoint jp)}.
 * 인자/타깃 *읽기*만 가능, 반환값 변형 X.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Before {
    Class<? extends Annotation> value();
}
