package com.choisk.sfs.aop.support;

import com.choisk.sfs.beans.BeanFactory;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * byte-buddy {@code MethodDelegation} 인터셉터 — 매칭 메서드 호출을 가로채 advice 종류별로 합성.
 *
 * <p>합성 순서: {@code @Around} 진입 → {@code @Before} 전체 invoke → 진짜 메서드({@code superCall}) →
 * {@code @Around} 종료. {@code @Around}가 없으면 {@code @Before} invoke 후 {@code superCall} 직통.
 *
 * <p>매칭이 *없는* 메서드는 {@code superCall} 직통 — enhance 비용 사실상 0.
 */
public class AdviceInterceptor {

    private final BeanFactory beanFactory;
    private final AspectRegistry registry;

    public AdviceInterceptor(BeanFactory beanFactory, AspectRegistry registry) {
        this.beanFactory = beanFactory;
        this.registry = registry;
    }

    @RuntimeType
    public Object intercept(@SuperCall Callable<Object> superCall,
                            @Origin Method method,
                            @AllArguments Object[] args,
                            @This Object self) throws Throwable {
        List<AdviceInfo> applicable = registry.findApplicable(method);
        if (applicable.isEmpty()) return superCall.call();

        // @Before/After advice는 proceed() 호출하지 않으므로 innerCall=null JoinPoint 사용 (fail-fast 보장)
        JoinPoint jp = new MethodInvocationJoinPoint(self, method, args, null);
        // innerCall: BEFORE advice 전체 실행 후 진짜 메서드 호출 — @Around는 이 묶음을 proceed()로 위임받음
        Callable<Object> innerCall = () -> {
            try {
                invokeAll(applicable, AdviceType.BEFORE, jp);
            } catch (Exception e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);  // Error 계열만 도달 — RuntimeException으로 전파
            }
            return superCall.call();
        };

        AdviceInfo around = findOne(applicable, AdviceType.AROUND);
        if (around != null) {
            ProceedingJoinPoint pjp = new MethodInvocationJoinPoint(self, method, args, innerCall);
            return invokeAdvice(around, pjp);
        }
        return innerCall.call();
    }

    /**
     * 지정한 {@code type}에 해당하는 advice를 목록 순서대로 전부 invoke한다.
     * advice가 throw하면 즉시 propagate — 후속 advice 및 진짜 메서드 호출이 차단된다.
     */
    private void invokeAll(List<AdviceInfo> list, AdviceType type, JoinPoint jp) throws Throwable {
        for (AdviceInfo info : list) {
            if (info.type() == type) invokeAdvice(info, jp);
        }
    }

    private AdviceInfo findOne(List<AdviceInfo> list, AdviceType type) {
        for (AdviceInfo info : list) {
            if (info.type() == type) return info;
        }
        return null;
    }

    private Object invokeAdvice(AdviceInfo info, JoinPoint jp) throws Throwable {
        Object aspectBean = beanFactory.getBean(info.aspectBeanName());
        try {
            return info.adviceMethod().invoke(aspectBean, jp);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();  // advice가 throw한 진짜 예외를 그대로 propagate
        }
    }
}
