package com.choisk.sfs.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 클래스가 advice를 정의함을 표시하는 마커.
 * <p>컨테이너 등록은 별도 — {@code @Component}와 함께 부착해야 빈으로 등록됨 (Spring 본가 패턴).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Aspect {}
