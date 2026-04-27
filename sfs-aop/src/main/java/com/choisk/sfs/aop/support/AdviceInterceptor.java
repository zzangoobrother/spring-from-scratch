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
 * <p>본 단계는 {@code @Around}만 분기 — {@code @Before}/{@code @After}는 후속 task(C1/C2)에서 추가.
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

        AdviceInfo around = findOne(applicable, AdviceType.AROUND);
        if (around != null) {
            ProceedingJoinPoint pjp = new MethodInvocationJoinPoint(self, method, args, superCall);
            return invokeAdvice(around, pjp);
        }
        return superCall.call();
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
