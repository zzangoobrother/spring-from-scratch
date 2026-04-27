package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2: {@link AbstractApplicationContext#registerBeanPostProcessors} 직접 단위 검증.
 *
 * <p>{@code AbstractApplicationContextTest}의 {@code TracingContext}는
 * {@code registerBeanPostProcessors}를 no-op으로 오버라이드하므로 실 구현 검증 부재.
 * 본 테스트는 {@code AnnotationConfigApplicationContext}를 통해 실 구현 경로를 검증한다.
 */
class RegisterBeanPostProcessorsTest {

    /** 추적 목록을 가진 테스트용 BPP */
    static class TrackingBpp implements BeanPostProcessor {
        final java.util.List<String> processed = new java.util.ArrayList<>();

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            processed.add(beanName);
            return bean;
        }
    }

    /** BPP 후처리 대상이 되는 일반 빈 */
    static class SimpleBean {}

    @Configuration
    static class SingleBppConfig {
        @Bean
        public TrackingBpp trackingBpp() { return new TrackingBpp(); }

        @Bean
        public SimpleBean simpleBean() { return new SimpleBean(); }
    }

    /**
     * @Bean으로 선언한 BPP가 refresh() 후 BPP 체인에 등록되어야 한다.
     * <p>동작 근거: registerBeanPostProcessors → getBeanNamesForType(BPP) → getBean → addBeanPostProcessor.
     * BPP 등록 후 finishBeanFactoryInitialization에서 simpleBean이 생성될 때 TrackingBpp가 적용된다.
     */
    @Test
    void beanDeclaredBppIsRegisteredInPostProcessorChain() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(SingleBppConfig.class);

        TrackingBpp trackingBpp = ctx.getBean("trackingBpp", TrackingBpp.class);

        // BPP 체인에 TrackingBpp가 포함되어야 함
        assertThat(ctx.getBeanFactory().getBeanPostProcessors())
                .as("@Bean BPP가 BPP 체인에 등록되어야 함")
                .anyMatch(bpp -> bpp instanceof TrackingBpp);

        // registerBeanPostProcessors 이후 생성된 simpleBean이 TrackingBpp를 통과해야 함
        assertThat(trackingBpp.processed)
                .as("BPP 등록 이후 생성된 빈(simpleBean)은 TrackingBpp를 통과해야 함")
                .contains("simpleBean");

        ctx.close();
    }

    /** 동일 BPP 인스턴스를 두 번 addBeanPostProcessor해도 BPP 체인에 1번만 등장해야 한다 (contains 중복 방지). */
    @Test
    void bppIsNotDuplicatedInPostProcessorChain() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(SingleBppConfig.class);

        long count = ctx.getBeanFactory().getBeanPostProcessors().stream()
                .filter(bpp -> bpp instanceof TrackingBpp)
                .count();

        assertThat(count)
                .as("TrackingBpp는 BPP 체인에 정확히 1번만 등록되어야 함")
                .isEqualTo(1L);

        ctx.close();
    }
}
