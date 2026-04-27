package com.choisk.sfs.aop.integration;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.aop.annotation.Before;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.aop.support.AspectEnhancingBeanPostProcessor;
import com.choisk.sfs.aop.support.JoinPoint;
import com.choisk.sfs.aop.support.ProceedingJoinPoint;
import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AspectIntegrationTest {

    @Component
    public static class CallLog {
        public final List<String> entries = new ArrayList<>();
    }

    @Aspect
    @Component
    public static class TracingAspect {
        @Autowired
        private CallLog log;

        @Around(Loggable.class)
        public Object trace(ProceedingJoinPoint pjp) throws Throwable {
            log.entries.add("around:" + pjp.getMethod().getName());
            return pjp.proceed();
        }

        @Before(Loggable.class)
        public void traceBefore(JoinPoint jp) { log.entries.add("before:" + jp.getMethod().getName()); }

        @After(Loggable.class)
        public void traceAfter(JoinPoint jp) { log.entries.add("after:" + jp.getMethod().getName()); }
    }

    @Component
    public static class AnnotatedTarget {
        @Loggable
        public String greet(String name) { return "hello " + name; }

        public String unannotated() { return "plain"; }
    }

    @Component
    public static class PlainTarget {
        public String value() { return "no-advice"; }
    }

    @Configuration
    @ComponentScan(basePackages = "com.choisk.sfs.aop.integration")
    public static class TestConfig {
        @Bean
        public AspectEnhancingBeanPostProcessor aspectBpp() {
            return new AspectEnhancingBeanPostProcessor();
        }
    }

    @Test
    void aspectBeanIsRegisteredAndTargetIsEnhanced() {
        try (var ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            AnnotatedTarget target = ctx.getBean(AnnotatedTarget.class);
            assertThat(target.getClass()).isNotEqualTo(AnnotatedTarget.class);  // enhance됨
            assertThat(AnnotatedTarget.class.isAssignableFrom(target.getClass())).isTrue();
        }
    }

    @Test
    void adviceIsInvokedWhenTargetMethodCalled() {
        try (var ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            AnnotatedTarget target = ctx.getBean(AnnotatedTarget.class);
            CallLog log = ctx.getBean(CallLog.class);

            String result = target.greet("world");

            assertThat(result).isEqualTo("hello world");
            assertThat(log.entries).containsExactly("around:greet", "before:greet", "after:greet");
        }
    }

    @Test
    void aspectInjectedDependenciesAreAvailable() {
        try (var ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            AnnotatedTarget target = ctx.getBean(AnnotatedTarget.class);
            CallLog log = ctx.getBean(CallLog.class);

            target.greet("x");
            target.greet("y");

            // CallLog 빈이 advice에 주입되어 *동일 인스턴스*에 누적
            assertThat(log.entries).hasSize(6);  // 3 advice × 2 호출
        }
    }

    @Test
    void plainBeanWithoutMatchingAnnotationIsNotEnhanced() {
        try (var ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            PlainTarget plain = ctx.getBean(PlainTarget.class);
            assertThat(plain.getClass()).isEqualTo(PlainTarget.class);  // enhance 안 됨
            assertThat(plain.value()).isEqualTo("no-advice");

            CallLog log = ctx.getBean(CallLog.class);
            assertThat(log.entries).isEmpty();
        }
    }
}
