package com.choisk.sfs.aop.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * 단일 advice 메서드의 메타정보.
 *
 * @param type advice 종류 (BEFORE/AFTER/AROUND)
 * @param targetAnnotation 매칭 대상 마커 애노테이션 (예: {@code Loggable.class})
 * @param adviceMethod aspect 빈에서 reflect-invoke될 메서드
 * @param aspectBeanName 호출 시 {@code BeanFactory.getBean(name)}으로 lookup할 빈 이름
 */
public record AdviceInfo(
        AdviceType type,
        Class<? extends Annotation> targetAnnotation,
        Method adviceMethod,
        String aspectBeanName
) {}
