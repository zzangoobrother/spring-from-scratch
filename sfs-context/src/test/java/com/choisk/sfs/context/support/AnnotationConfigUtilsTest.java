package com.choisk.sfs.context.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnnotationConfigUtils — 처리기 3종 자동 등록 헬퍼 테스트.
 *
 * <ul>
 *   <li>{@code registersThreeProcessors} — 무인자 생성자 호출 시 BFPP 1+ / BPP 2+ 자동 등록 확인</li>
 *   <li>{@code idempotentSecondCallDoesNotDuplicate} — 두 번째 호출이 중복 등록을 일으키지 않음 확인</li>
 * </ul>
 */
class AnnotationConfigUtilsTest {

    @Test
    void registersThreeProcessors() {
        // AnnotationConfigApplicationContext 무인자 생성자에서 자동 등록 확인
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

        // ConfigurationClassPostProcessor 1개 이상 BFPP 등록 확인
        assertThat(ctx.getBeanFactoryPostProcessors().size()).isGreaterThanOrEqualTo(1);
        // AutowiredAnnotationBeanPostProcessor + CommonAnnotationBeanPostProcessor 2개 이상 BPP 등록 확인
        assertThat(ctx.getBeanFactory().getBeanPostProcessorCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void idempotentSecondCallDoesNotDuplicate() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();

        // 첫 번째 호출(생성자에서 이미 수행됨) 후 카운트 기록
        int firstBfppCount = ctx.getBeanFactoryPostProcessors().size();
        int firstBppCount = ctx.getBeanFactory().getBeanPostProcessorCount();

        // 두 번째 호출 — 중복 등록이 없어야 함
        AnnotationConfigUtils.registerAnnotationConfigProcessors(ctx);

        assertThat(ctx.getBeanFactoryPostProcessors().size()).isEqualTo(firstBfppCount);
        assertThat(ctx.getBeanFactory().getBeanPostProcessorCount()).isEqualTo(firstBppCount);
    }
}
