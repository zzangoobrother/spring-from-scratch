package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Before;
import com.choisk.sfs.aop.annotation.Loggable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void registerTwoAspectsAccumulatesAllAdvices() {
        AspectRegistry registry = new AspectRegistry();
        registry.register("aspectA", TestAspect.class);
        registry.register("aspectB", TestAspect.class);  // 같은 클래스 두 번 등록 — 누적 안전망

        List<AdviceInfo> all = registry.findApplicable(targetMethod(TargetWithLoggable.class, "annotatedMethod"));
        assertThat(all).hasSize(6);  // 3(aspectA) + 3(aspectB)
        assertThat(all).extracting(AdviceInfo::aspectBeanName)
                .containsExactlyInAnyOrder("aspectA", "aspectA", "aspectA", "aspectB", "aspectB", "aspectB");
    }

    // ---- 시그니처 검증 실패 케이스 픽스처 ----

    static class InvalidAroundAspect {
        /** @Around 첫 인자가 ProceedingJoinPoint 아님 — 시그니처 위반 */
        @Around(Loggable.class)
        public Object aroundWithWrongArg(String wrongParam) { return null; }
    }

    static class InvalidBeforeReturnAspect {
        /** @Before 반환 타입이 void 아님 — 시그니처 위반 */
        @Before(Loggable.class)
        public Object beforeWithReturnType(JoinPoint jp) { return null; }
    }

    static class InvalidAfterNoJoinPointAspect {
        /** @After 첫 인자가 JoinPoint 아님 — 시그니처 위반 */
        @After(Loggable.class)
        public void afterWithWrongArg(String wrongParam) {}
    }

    @Test
    void aroundWithoutProceedingJoinPointThrows() {
        AspectRegistry registry = new AspectRegistry();
        assertThatThrownBy(() -> registry.register("bad", InvalidAroundAspect.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Around")
                .hasMessageContaining("ProceedingJoinPoint");
    }

    @Test
    void beforeWithReturnTypeThrows() {
        AspectRegistry registry = new AspectRegistry();
        assertThatThrownBy(() -> registry.register("bad", InvalidBeforeReturnAspect.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Before")
                .hasMessageContaining("void");
    }

    @Test
    void afterWithoutJoinPointThrows() {
        AspectRegistry registry = new AspectRegistry();
        assertThatThrownBy(() -> registry.register("bad", InvalidAfterNoJoinPointAspect.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@After")
                .hasMessageContaining("JoinPoint");
    }

    private static Method targetMethod(Class<?> cls, String name) {
        try { return cls.getMethod(name); }
        catch (NoSuchMethodException e) { throw new RuntimeException(e); }
    }
}
