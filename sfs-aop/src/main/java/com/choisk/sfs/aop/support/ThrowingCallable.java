package com.choisk.sfs.aop.support;

/**
 * Throwable을 직접 throw할 수 있는 함수형 인터페이스.
 *
 * <p>표준 {@link java.util.concurrent.Callable}이 {@code throws Exception}만 허용해
 * advice chain의 Throwable 전파 시 Error 계열을 RuntimeException으로 wrap해야 하는
 * 제약을 해소한다 — 모든 예외를 변형 없이 그대로 propagate.
 */
@FunctionalInterface
public interface ThrowingCallable {
    Object call() throws Throwable;
}
