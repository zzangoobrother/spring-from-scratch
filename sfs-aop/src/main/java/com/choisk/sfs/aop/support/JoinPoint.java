package com.choisk.sfs.aop.support;

import java.lang.reflect.Method;

/**
 * advice 메서드가 받는 호출 컨텍스트. {@code @Before}/{@code @After}는 본 인터페이스만,
 * {@code @Around}는 {@link ProceedingJoinPoint}를 받는다 (권한 차이).
 */
public interface JoinPoint {
    Object getTarget();
    Method getMethod();
    Object[] getArgs();
}
