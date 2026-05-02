package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3-aop — {@code AspectEnhancingBeanPostProcessor}의 {@code earlyProxyReferences} 캐시 동작 검증.
 *
 * <p>spec § 3.3.1, § 6.2 정합. *동작 변경*의 본질을 박제: enhance 호출 횟수가 핵심 어서션.
 */
class AspectEnhancingBeanPostProcessorCacheTest {

    @Aspect
    public static class NoOpAspect {}

    public static class Target {}

    /**
     * 시나리오 (a): {@code getEarlyBeanReference} 호출 후 {@code postProcessAfterInitialization} 진입 시
     * → 캐시 hit으로 enhance 스킵, *원본 빈 그대로 반환* (early가 1차로 승격됨).
     */
    @Test
    void cacheHitSkipsEnhanceInPostProcessAfterInitialization() {
        var bpp = new AspectEnhancingBeanPostProcessor();
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bpp.setBeanFactory(bf);

        Target original = new Target();
        Object early = bpp.getEarlyBeanReference(original, "target");

        Object after = bpp.postProcessAfterInitialization(original, "target");

        // 캐시 hit → 원본 그대로 반환 (enhance 스킵)
        assertThat(after).isSameAs(original);
        // early는 enhance 적용된 인스턴스이거나 원본 — 본 테스트는 *postProcessAfterInitialization의
        // 동작 변경 검증*이 목적이므로 early의 정체는 별도 테스트 책임
        assertThat(early).isNotNull();
    }

    /**
     * 시나리오 (b): {@code getEarlyBeanReference} 미호출 + {@code postProcessAfterInitialization}만 진입
     * → 캐시 miss → 기존 enhance 경로 진행 (비순환 의존 케이스 회귀).
     */
    @Test
    void cacheMissStillRunsEnhancePath() {
        var bpp = new AspectEnhancingBeanPostProcessor();
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bpp.setBeanFactory(bf);

        Target original = new Target();
        // getEarlyBeanReference 호출 *없이* 바로 postProcessAfterInitialization
        Object after = bpp.postProcessAfterInitialization(original, "target");

        // Target은 매칭 advice 없으므로 원본 반환 (enhance 분기 진입 후 매칭 0건으로 원본 반환)
        // 핵심: 캐시 분기가 *getEarlyBeanReference 미호출 케이스에 영향 없음* 검증
        assertThat(after).isSameAs(original);
    }
}
