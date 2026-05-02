package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3-aop — {@code AspectEnhancingBeanPostProcessor}의 {@code earlyProxyReferences} 캐시 동작 검증.
 *
 * <p>spec § 3.3.1, § 6.2 정합. *동작 변경*의 본질을 박제: enhance 호출 횟수가 핵심 어서션.
 *
 * <p><strong>핵심 박제</strong>: Target에 매칭 advice를 적용해 enhance가 *실제 발생하는* 상태를 만든 뒤,
 * cache hit 시 enhance 스킵 / cache miss 시 enhance 진행을 *원본 vs enhanced 인스턴스 차이*로 검증.
 * (단순히 advice 미매칭 상태로는 캐시 분기를 타든 안 타든 원본 반환이라 변이를 못 잡음.)
 */
class AspectEnhancingBeanPostProcessorCacheTest {

    /**
     * 실제 advice를 등록하는 TestAspect — registry에 {@code @Around(Loggable.class)} advice가 등록되어
     * {@code findAnyApplicable}이 true를 반환하는 게 핵심.
     */
    @Aspect
    public static class TestAspect {
        @Around(Loggable.class)
        public Object passthrough(ProceedingJoinPoint pjp) throws Throwable {
            return pjp.proceed();
        }
    }

    /**
     * 매칭 advice가 적용되도록 {@code @Loggable}을 클래스 레벨에 부착.
     * 클래스 레벨 어노테이션이므로 모든 public 메서드가 advice 대상이 되어
     * {@code findAnyApplicable}이 true를 반환한다.
     */
    @Loggable
    public static class Target {}

    /**
     * 공통 셋업 — TestAspect를 BeanDefinition으로 등록 후 setBeanFactory 호출.
     * {@code preRegisterAspects}가 BD를 순회해 registry에 advice를 등록한다.
     */
    private AspectEnhancingBeanPostProcessor buildBpp() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("testAspect", new BeanDefinition(TestAspect.class));
        AspectEnhancingBeanPostProcessor bpp = new AspectEnhancingBeanPostProcessor();
        bpp.setBeanFactory(bf);
        return bpp;
    }

    /**
     * 시나리오 (a): {@code getEarlyBeanReference} 호출 후 {@code postProcessAfterInitialization} 진입 시
     * → cache hit → enhance 스킵 → *원본 그대로* 반환 (early가 1차로 승격됨).
     *
     * <p>변이 검출 대상:
     * <ul>
     *   <li>(M1): {@code postProcessAfterInitialization} 캐시 분기 제거 → {@code after.isSameAs(original)} FAIL</li>
     *   <li>(M2): {@code getEarlyBeanReference}의 put 제거 → {@code after.isSameAs(original)} FAIL</li>
     *   <li>(M3): {@code getEarlyBeanReference} 본문 → {@code return bean;} 교체 → {@code early.isNotSameAs(original)} FAIL</li>
     * </ul>
     */
    @Test
    void cacheHitSkipsEnhanceInPostProcessAfterInitialization() {
        AspectEnhancingBeanPostProcessor bpp = buildBpp();

        Target original = new Target();
        Object early = bpp.getEarlyBeanReference(original, "target");

        Object after = bpp.postProcessAfterInitialization(original, "target");

        // early는 enhance 적용된 인스턴스 — 서브클래스이므로 original과 다름 (M3 검출)
        assertThat(early).isNotSameAs(original);
        assertThat(early.getClass().getSuperclass()).isEqualTo(Target.class);
        // after는 cache hit → 원본 그대로 (이게 본 phase 동작 변경의 본질) (M1/M2 검출)
        assertThat(after).isSameAs(original);
    }

    /**
     * 시나리오 (b): {@code getEarlyBeanReference} 미호출 + {@code postProcessAfterInitialization}만 진입
     * → cache miss → 기존 enhance 경로 진행 → *enhanced* 반환 (비순환 의존 케이스 회귀).
     */
    @Test
    void cacheMissAppliesEnhance() {
        AspectEnhancingBeanPostProcessor bpp = buildBpp();

        Target original = new Target();
        // getEarlyBeanReference 호출 *없이* 바로 postProcessAfterInitialization
        Object after = bpp.postProcessAfterInitialization(original, "target");

        // cache miss → enhance 진행 → 서브클래스 반환 (비순환 의존 회귀 검증)
        assertThat(after).isNotSameAs(original);
        assertThat(after.getClass().getSuperclass()).isEqualTo(Target.class);
    }
}
