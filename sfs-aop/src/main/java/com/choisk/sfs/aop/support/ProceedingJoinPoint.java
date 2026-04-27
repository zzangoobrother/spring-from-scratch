package com.choisk.sfs.aop.support;

/**
 * {@code @Around} advice 전용 — {@code proceed()}로 진짜 메서드 호출 트리거 가능.
 * 호출 시점/횟수/결과 변형을 모두 advice가 결정.
 */
public interface ProceedingJoinPoint extends JoinPoint {
    Object proceed() throws Throwable;
}
