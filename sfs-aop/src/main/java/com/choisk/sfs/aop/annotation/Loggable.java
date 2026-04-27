package com.choisk.sfs.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * advice 매칭 대상을 표시하는 시연용 마커. 메서드/클래스 둘 다 부착 가능.
 * <p>본 phase는 {@code @Loggable} 한 종만 정의. 사용자 정의 마커 애노테이션은 advice의 {@code value()}로 자유 지정 가능.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Loggable {}
