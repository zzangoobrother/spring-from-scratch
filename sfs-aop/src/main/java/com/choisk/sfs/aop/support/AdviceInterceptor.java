package com.choisk.sfs.aop.support;

import com.choisk.sfs.beans.BeanFactory;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;  // byte-buddy @SuperCall 강제 타입 — 제거 불가
import java.util.stream.Collectors;

/**
 * byte-buddy {@code MethodDelegation} 인터셉터 — 매칭 메서드 호출을 가로채 advice 종류별로 합성.
 *
 * <p>합성 순서: {@code @Around} 진입 → {@code @Before} → 진짜 메서드({@code superCall}) →
 * {@code @After}(finally, 예외 시에도 보장) → {@code @Around} 종료.
 * {@code @Around}가 없으면 해당 묶음을 직통으로 실행한다.
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
        // innerCall: BEFORE → 진짜 메서드 → AFTER(finally) 묶음 — @Around는 이 묶음을 proceed()로 위임받음
        ThrowingCallable innerCall = () -> {
            invokeAll(applicable, AdviceType.BEFORE, jp);
            try {
                return superCall.call();
            } finally {
                invokeAll(applicable, AdviceType.AFTER, jp);  // 예외 시에도 반드시 호출
            }
        };

        AdviceInfo around = findAtMostOne(applicable, AdviceType.AROUND);
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

    /**
     * 지정 {@code type}의 advice를 최대 1개 반환한다. 2개 이상이면 {@link IllegalStateException} throw.
     *
     * <p>복수 {@code @Around}를 허용하지 않는 이유: 실행 체인(advice 우선순위)을 지원하지 않는 상황에서
     * 두 번째 {@code @Around}가 silent drop 되면 런타임 동작이 의도와 달라지므로, 등록 즉시 fail-fast가
     * 누락된 경우 intercept 시점에 명확한 오류로 알린다.
     * advice 우선순위(@Order) 도입 시 이 제한을 chain으로 교체한다.
     */
    private AdviceInfo findAtMostOne(List<AdviceInfo> list, AdviceType type) {
        List<AdviceInfo> found = list.stream()
                .filter(i -> i.type() == type)
                .toList();
        if (found.size() > 1) {
            String names = found.stream()
                    .map(AdviceInfo::aspectBeanName)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "동일 메서드에 @Around advice가 " + found.size() + "개 정의됨 — advice 우선순위(@Order) 미지원"
                            + ". advice bean name: [" + names + "]"
            );
        }
        return found.isEmpty() ? null : found.get(0);
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
