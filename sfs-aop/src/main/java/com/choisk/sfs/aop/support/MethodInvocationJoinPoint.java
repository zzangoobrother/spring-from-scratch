package com.choisk.sfs.aop.support;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * {@link JoinPoint}와 {@link ProceedingJoinPoint}의 단일 구현.
 *
 * <p>{@code innerCall}이 {@code null}이면 {@code proceed()} 호출 시 {@link IllegalStateException} —
 * {@code @Around}가 아닌 advice 컨텍스트(@Before/@After)에서 잘못 호출되는 케이스를 fail-fast.
 */
public class MethodInvocationJoinPoint implements ProceedingJoinPoint {

    private final Object target;
    private final Method method;
    private final Object[] args;
    private final Callable<Object> innerCall;

    public MethodInvocationJoinPoint(Object target, Method method, Object[] args, Callable<Object> innerCall) {
        this.target = target;
        this.method = method;
        this.args = args;
        this.innerCall = innerCall;
    }

    @Override
    public Object getTarget() { return target; }

    @Override
    public Method getMethod() { return method; }

    @Override
    public Object[] getArgs() { return args; }

    @Override
    public Object proceed() throws Throwable {
        if (innerCall == null) {
            throw new IllegalStateException(
                    "proceed() called on JoinPoint without inner call — @Around가 아닌 advice에서 호출됨");
        }
        return innerCall.call();
    }
}
