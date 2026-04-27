package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.beans.BeanDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceInterceptorTest {

    public static class CountingAroundAspect {
        public static final AtomicInteger callCount = new AtomicInteger(0);

        @Around(Loggable.class)
        public Object countAndProceed(ProceedingJoinPoint pjp) throws Throwable {
            callCount.incrementAndGet();
            return pjp.proceed();
        }
    }

    public static class SkipProceedAspect {
        @Around(Loggable.class)
        public Object skipProceed(ProceedingJoinPoint pjp) throws Throwable {
            return "skipped";  // proceed() 호출 안 함
        }
    }

    static class Target {
        @Loggable
        public String greet(String name) { return "hello " + name; }

        // @Loggable 없는 메서드 — methodWithoutMatchingAnnotationCallsSuperDirectly 용
        public String plain(String name) { return "plain " + name; }
    }

    @Test
    void aroundAdviceWrapsMethodCall() throws Throwable {
        BeanFactory bf = beanFactoryWith("countingAspect", new CountingAroundAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("countingAspect", CountingAroundAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Object[] args = {"world"};
        Callable<Object> superCall = () -> target.greet((String) args[0]);

        CountingAroundAspect.callCount.set(0);
        Object result = interceptor.intercept(superCall, greet, args, target);

        assertThat(result).isEqualTo("hello world");
        assertThat(CountingAroundAspect.callCount.get()).isEqualTo(1);
    }

    @Test
    void aroundAdviceCanSkipProceed() throws Throwable {
        BeanFactory bf = beanFactoryWith("skipAspect", new SkipProceedAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("skipAspect", SkipProceedAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Callable<Object> superCall = () -> {
            throw new IllegalStateException("진짜 메서드 호출되면 안 됨");
        };

        Object result = interceptor.intercept(superCall, greet, new Object[]{"x"}, target);
        assertThat(result).isEqualTo("skipped");
    }

    @Test
    void methodWithoutMatchingAnnotationCallsSuperDirectly() throws Throwable {
        BeanFactory bf = beanFactoryWith("countingAspect", new CountingAroundAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("countingAspect", CountingAroundAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("plain", String.class);  // 매칭 애노테이션 없음
        Object[] args = {"x"};
        Callable<Object> superCall = () -> target.plain((String) args[0]);

        CountingAroundAspect.callCount.set(0);
        Object result = interceptor.intercept(superCall, greet, args, target);

        assertThat(result).isEqualTo("plain x");
        assertThat(CountingAroundAspect.callCount.get()).isEqualTo(0);
    }

    private static BeanFactory beanFactoryWith(String name, Object bean) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        BeanDefinition bd = new BeanDefinition(bean.getClass());
        bf.registerBeanDefinition(name, bd);
        bf.registerSingleton(name, bean);
        return bf;
    }
}
