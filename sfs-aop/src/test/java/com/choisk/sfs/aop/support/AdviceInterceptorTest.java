package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.beans.BeanDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdviceInterceptorTest {

    public static class BeforeAndAroundAspect {
        public static final java.util.List<String> calls = new java.util.ArrayList<>();

        @Around(Loggable.class)
        public Object aroundCheck(ProceedingJoinPoint pjp) throws Throwable {
            calls.add("around-enter");
            Object r = pjp.proceed();
            calls.add("around-exit");
            return r;
        }

        @com.choisk.sfs.aop.annotation.Before(Loggable.class)
        public void beforeCheck(JoinPoint jp) {
            calls.add("before");
        }
    }

    public static class BeforeOnlyAspect {
        public static int beforeCount = 0;

        @com.choisk.sfs.aop.annotation.Before(Loggable.class)
        public void beforeOnly(JoinPoint jp) { beforeCount++; }
    }

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

    public static class ThrowingBeforeAspect {
        @com.choisk.sfs.aop.annotation.Before(Loggable.class)
        public void thrower(JoinPoint jp) { throw new IllegalStateException("blocked"); }
    }

    public static class BeforeAfterAroundAspect {
        public static final java.util.List<String> calls = new java.util.ArrayList<>();

        @Around(Loggable.class)
        public Object aroundCheck(ProceedingJoinPoint pjp) throws Throwable {
            calls.add("around-enter");
            try {
                return pjp.proceed();
            } finally {
                calls.add("around-exit");
            }
        }

        @com.choisk.sfs.aop.annotation.Before(Loggable.class)
        public void beforeCheck(JoinPoint jp) { calls.add("before"); }

        @After(Loggable.class)
        public void afterCheck(JoinPoint jp) { calls.add("after"); }
    }

    public static class AfterOnThrowAspect {
        public static boolean afterCalled = false;

        @After(Loggable.class)
        public void afterCheck(JoinPoint jp) { afterCalled = true; }
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

    @Test
    void beforeAdviceRunsBeforeMethodCall() throws Throwable {
        BeanFactory bf = beanFactoryWith("beforeAndAround", new BeforeAndAroundAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("beforeAndAround", BeforeAndAroundAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Object[] args = {"x"};
        Callable<Object> superCall = () -> {
            BeforeAndAroundAspect.calls.add("super");
            return target.greet((String) args[0]);
        };

        BeforeAndAroundAspect.calls.clear();
        interceptor.intercept(superCall, greet, args, target);

        assertThat(BeforeAndAroundAspect.calls)
                .containsExactly("around-enter", "before", "super", "around-exit");
    }

    @Test
    void beforeAdviceThrowingPreventsMethodCall() throws Throwable {
        BeanFactory bf = beanFactoryWith("throwAspect", new ThrowingBeforeAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("throwAspect", ThrowingBeforeAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        AtomicBoolean superCalled = new AtomicBoolean(false);
        Callable<Object> superCall = () -> { superCalled.set(true); return null; };

        assertThatThrownBy(() -> interceptor.intercept(superCall, greet, new Object[]{"x"}, target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("blocked");
        assertThat(superCalled.get()).isFalse();  // 진짜 메서드 차단됨
    }

    @Test
    void aroundlessBeforeOnlyInvokesInnerCallDirectly() throws Throwable {
        // @Around 없이 @Before만 적용 시 innerCall 직통 분기(@Around 없을 때 innerCall.call() 직접 호출)를 검증
        BeanFactory bf = beanFactoryWith("beforeOnly", new BeforeOnlyAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("beforeOnly", BeforeOnlyAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Object[] args = {"world"};
        Callable<Object> superCall = () -> target.greet((String) args[0]);

        BeforeOnlyAspect.beforeCount = 0;
        Object result = interceptor.intercept(superCall, greet, args, target);

        assertThat(result).isEqualTo("hello world");
        assertThat(BeforeOnlyAspect.beforeCount).isEqualTo(1);  // @Before가 정확히 1회 invoke됨
    }

    @Test
    void aroundComposesBeforeAndAfter() throws Throwable {
        BeanFactory bf = beanFactoryWith("triadic", new BeforeAfterAroundAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("triadic", BeforeAfterAroundAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Object[] args = {"x"};
        Callable<Object> superCall = () -> {
            BeforeAfterAroundAspect.calls.add("super");
            return target.greet((String) args[0]);
        };

        BeforeAfterAroundAspect.calls.clear();
        interceptor.intercept(superCall, greet, args, target);

        assertThat(BeforeAfterAroundAspect.calls)
                .containsExactly("around-enter", "before", "super", "after", "around-exit");
    }

    @Test
    void afterAdviceRunsEvenWhenMethodThrows() throws Throwable {
        BeanFactory bf = beanFactoryWith("afterOnThrow", new AfterOnThrowAspect());
        AspectRegistry registry = new AspectRegistry();
        registry.register("afterOnThrow", AfterOnThrowAspect.class);

        AdviceInterceptor interceptor = new AdviceInterceptor(bf, registry);
        Target target = new Target();
        Method greet = Target.class.getMethod("greet", String.class);
        Callable<Object> superCall = () -> { throw new RuntimeException("biz fail"); };

        AfterOnThrowAspect.afterCalled = false;
        assertThatThrownBy(() -> interceptor.intercept(superCall, greet, new Object[]{"x"}, target))
                .isInstanceOf(RuntimeException.class).hasMessage("biz fail");

        assertThat(AfterOnThrowAspect.afterCalled).isTrue();  // finally에서 호출됨
    }

    private static BeanFactory beanFactoryWith(String name, Object bean) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        BeanDefinition bd = new BeanDefinition(bean.getClass());
        bf.registerBeanDefinition(name, bd);
        bf.registerSingleton(name, bean);
        return bf;
    }
}
