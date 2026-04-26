package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.ConfigurableApplicationContext;

/**
 * 애노테이션 기반 컨테이너에 필수 처리기 3종을 자동 등록하는 유틸리티.
 *
 * <ul>
 *   <li>{@link ConfigurationClassPostProcessor} — {@code @Bean} 메서드 → BeanDefinition 변환 (BFPP)</li>
 *   <li>{@link AutowiredAnnotationBeanPostProcessor} — {@code @Autowired} 필드 주입 (BPP)</li>
 *   <li>{@link CommonAnnotationBeanPostProcessor} — {@code @PostConstruct} / {@code @PreDestroy} (BPP)</li>
 * </ul>
 *
 * <p>멱등성 보장: 이미 동일 타입의 처리기가 등록되어 있으면 중복 등록하지 않는다.
 *
 * <p>Spring 원본: {@code AnnotationConfigUtils}.
 */
public final class AnnotationConfigUtils {

    /** 유틸리티 클래스 — 인스턴스화 금지. */
    private AnnotationConfigUtils() {}

    /**
     * 처리기 3종을 컨텍스트에 등록한다. 각 처리기는 이미 등록된 경우 건너뛴다(멱등).
     *
     * @param ctx 처리기를 등록할 컨텍스트
     */
    public static void registerAnnotationConfigProcessors(ConfigurableApplicationContext ctx) {
        ConfigurableListableBeanFactory bf = ctx.getBeanFactory();

        // 1. ConfigurationClassPostProcessor (BFPP) — @Bean 메서드 → BD 변환
        if (!hasBfpp(ctx, ConfigurationClassPostProcessor.class)) {
            ctx.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
        }

        // 2. AutowiredAnnotationBeanPostProcessor (BPP) — @Autowired 필드 주입
        if (!hasBpp(ctx, AutowiredAnnotationBeanPostProcessor.class)) {
            bf.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(bf));
        }

        // 3. CommonAnnotationBeanPostProcessor (BPP) — @PostConstruct/@PreDestroy
        if (!hasBpp(ctx, CommonAnnotationBeanPostProcessor.class)) {
            bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        }
    }

    /**
     * 컨텍스트의 BFPP 목록에 지정 타입의 처리기가 이미 등록되어 있는지 확인한다.
     *
     * @param ctx  검사 대상 컨텍스트
     * @param type 찾을 처리기 타입
     * @return 등록되어 있으면 true
     */
    private static boolean hasBfpp(ConfigurableApplicationContext ctx, Class<? extends BeanFactoryPostProcessor> type) {
        return ctx.getBeanFactoryPostProcessors().stream().anyMatch(type::isInstance);
    }

    /**
     * BeanFactory의 BPP 목록에 지정 타입의 처리기가 이미 등록되어 있는지 확인한다.
     *
     * @param ctx  검사 대상 컨텍스트
     * @param type 찾을 처리기 타입
     * @return 등록되어 있으면 true
     */
    private static boolean hasBpp(ConfigurableApplicationContext ctx, Class<? extends BeanPostProcessor> type) {
        return ctx.getBeanFactory().getBeanPostProcessors().stream().anyMatch(type::isInstance);
    }
}
