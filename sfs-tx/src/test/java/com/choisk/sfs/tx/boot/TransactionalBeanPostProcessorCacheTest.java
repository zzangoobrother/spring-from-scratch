package com.choisk.sfs.tx.boot;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.tx.annotation.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3-tx — {@code TransactionalBeanPostProcessor}의 {@code earlyProxyReferences} 캐시 동작 검증.
 *
 * <p>spec § 3.3.2, § 6.2 정합. *동작 변경*의 본질을 박제: enhance 호출 1회 보장.
 */
class TransactionalBeanPostProcessorCacheTest {

    public static class TxTarget {
        @Transactional
        public String hello() { return "hello"; }
    }

    public static class NoTxTarget {
        public String hello() { return "hello"; }
    }

    /**
     * 시나리오 (a): {@code getEarlyBeanReference} 호출 후 {@code postProcessAfterInitialization} 진입 시
     * → 캐시 hit → enhance 스킵 → *원본 그대로 반환*.
     *
     * <p>핵심 검증: getEarlyBeanReference의 결과(enhanced)와 postProcessAfterInitialization의 결과가
     * *서로 다른 인스턴스*가 되지 않도록 — 단일 인스턴스 보장.
     */
    @Test
    void cacheHitReturnsRawBeanInPostProcessAfterInitialization() {
        var bpp = new TransactionalBeanPostProcessor();
        bpp.setBeanFactory(new DefaultListableBeanFactory());

        TxTarget original = new TxTarget();
        Object early = bpp.getEarlyBeanReference(original, "txTarget");
        Object after = bpp.postProcessAfterInitialization(original, "txTarget");

        // early는 enhance 적용 (서브클래스), after는 원본 그대로
        assertThat(early).isNotSameAs(original);                 // early는 enhanced
        assertThat(early.getClass().getSuperclass()).isEqualTo(TxTarget.class); // byte-buddy 서브클래스
        assertThat(after).isSameAs(original);                    // ← 본 phase 동작 변경의 본질
    }

    /**
     * 시나리오 (b): {@code getEarlyBeanReference} 미호출 + {@code postProcessAfterInitialization}만 진입
     * → 캐시 miss → 기존 enhance 경로 진행 (단일 진입점 케이스 회귀).
     */
    @Test
    void cacheMissStillEnhancesInPostProcessAfterInitialization() {
        var bpp = new TransactionalBeanPostProcessor();
        bpp.setBeanFactory(new DefaultListableBeanFactory());

        TxTarget original = new TxTarget();
        Object after = bpp.postProcessAfterInitialization(original, "txTarget");

        // 캐시 분기가 진입 안 됨 → enhance 진행
        assertThat(after).isNotSameAs(original);
        assertThat(after.getClass().getSuperclass()).isEqualTo(TxTarget.class);
    }

    /**
     * 시나리오 (c): {@code @Transactional} 메서드가 *없는* 빈은 {@code getEarlyBeanReference}에서
     * 캐시 등록 자체 안 됨 → {@code postProcessAfterInitialization}에서도 enhance 스킵 → 원본 반환.
     *
     * <p>핵심 검증: `hasTransactionalMethod` 가드가 *put 전*에 있어 NoTx 빈이 캐시에 잘못 등록되지 않음.
     * (AspectBPP A finding fix와 동일 패턴 — 가드 순서가 정상 enhance 경로를 보호)
     */
    @Test
    void noCacheEntryForNonTransactionalBean() {
        var bpp = new TransactionalBeanPostProcessor();
        bpp.setBeanFactory(new DefaultListableBeanFactory());

        NoTxTarget original = new NoTxTarget();
        Object early = bpp.getEarlyBeanReference(original, "noTx");
        Object after = bpp.postProcessAfterInitialization(original, "noTx");

        // hasTransactionalMethod=false → 양쪽 진입점 모두 원본 그대로 (enhance 자체 발생 안 함)
        assertThat(early).isSameAs(original);
        assertThat(after).isSameAs(original);
    }
}
