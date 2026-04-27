package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Before;
import com.choisk.sfs.aop.annotation.Loggable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AspectRegistryTest {

    static class TestAspect {
        @Around(Loggable.class)
        public Object aroundLog(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }

        @Before(Loggable.class)
        public void beforeLog(JoinPoint jp) {}

        @After(Loggable.class)
        public void afterLog(JoinPoint jp) {}

        public void notAdvice() {}
    }

    static class TargetWithLoggable {
        @Loggable
        public void annotatedMethod() {}

        public void unannotatedMethod() {}
    }

    @Loggable
    static class TargetClassLoggable {
        public void publicMethodOne() {}
        public void publicMethodTwo() {}
    }

    @Test
    void registerAddsAdvicesFromAspectClass() {
        AspectRegistry registry = new AspectRegistry();
        registry.register("testAspect", TestAspect.class);

        List<AdviceInfo> all = registry.findApplicable(targetMethod(TargetWithLoggable.class, "annotatedMethod"));
        assertThat(all).hasSize(3);
        assertThat(all).extracting(AdviceInfo::type)
                .containsExactlyInAnyOrder(AdviceType.AROUND, AdviceType.BEFORE, AdviceType.AFTER);
        assertThat(all).extracting(AdviceInfo::aspectBeanName).containsOnly("testAspect");
        assertThat(all).extracting(AdviceInfo::targetAnnotation).containsOnly(Loggable.class);
    }

    @Test
    void findApplicableReturnsEmptyForUnannotatedMethod() {
        AspectRegistry registry = new AspectRegistry();
        registry.register("testAspect", TestAspect.class);

        List<AdviceInfo> all = registry.findApplicable(targetMethod(TargetWithLoggable.class, "unannotatedMethod"));
        assertThat(all).isEmpty();
    }

    @Test
    void findApplicableHonorsClassLevelAnnotation() {
        AspectRegistry registry = new AspectRegistry();
        registry.register("testAspect", TestAspect.class);

        List<AdviceInfo> one = registry.findApplicable(targetMethod(TargetClassLoggable.class, "publicMethodOne"));
        List<AdviceInfo> two = registry.findApplicable(targetMethod(TargetClassLoggable.class, "publicMethodTwo"));
        assertThat(one).hasSize(3);
        assertThat(two).hasSize(3);
    }

    @Test
    void findAnyApplicableDetectsTargetClassWithMatchingMethods() {
        AspectRegistry registry = new AspectRegistry();
        registry.register("testAspect", TestAspect.class);

        assertThat(registry.findAnyApplicable(TargetWithLoggable.class)).isTrue();
        assertThat(registry.findAnyApplicable(TargetClassLoggable.class)).isTrue();

        class NoLoggable {}
        assertThat(registry.findAnyApplicable(NoLoggable.class)).isFalse();
    }

    private static Method targetMethod(Class<?> cls, String name) {
        try { return cls.getMethod(name); }
        catch (NoSuchMethodException e) { throw new RuntimeException(e); }
    }
}
