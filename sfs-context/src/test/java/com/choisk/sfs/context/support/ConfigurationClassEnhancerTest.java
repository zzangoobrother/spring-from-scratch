package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationClassEnhancerTest {

    @Configuration
    static final class FinalConfig {
        @Bean
        public String value() { return "x"; }
    }

    @Configuration
    public static class SampleConfig {
        @Bean
        public String greeting() { return "hello"; }

        @Bean
        public Integer counter() { return 42; }
    }

    @Test
    void enhanceProducesSubclassOfOriginal() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);

        Class<?> enhanced = enhancer.enhance(SampleConfig.class);

        assertThat(SampleConfig.class.isAssignableFrom(enhanced))
                .as("enhance 결과는 원본의 서브클래스여야 함")
                .isTrue();
        assertThat(enhanced)
                .as("enhance 결과는 원본 클래스 자체와 다른 클래스여야 함")
                .isNotEqualTo(SampleConfig.class);
    }

    @Test
    void enhancedClassRoutesBeanMethodCallsThroughContainer() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        String cachedGreeting = "cached!";
        factory.registerSingleton("greeting", cachedGreeting);

        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);
        Class<?> enhanced = enhancer.enhance(SampleConfig.class);
        SampleConfig instance = (SampleConfig) enhanced.getDeclaredConstructor().newInstance();

        assertThat(instance.greeting())
                .as("@Bean 메서드 직접 호출이 인터셉터 경유로 컨테이너 빈을 반환해야 함")
                .isEqualTo(cachedGreeting);

        assertThat(instance.counter())
                .as("캐시 miss 시 원본 메서드 본문이 실행되어야 함")
                .isEqualTo(42);
    }

    @Test
    void enhanceThrowsNullPointerExceptionWhenConfigClassIsNull() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);

        assertThatThrownBy(() -> enhancer.enhance(null))
                .as("null 입력 시 NullPointerException 발생")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("configClass cannot be null");
    }

    @Test
    void enhanceThrowsIllegalArgumentExceptionWhenConfigClassIsFinal() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);

        assertThatThrownBy(() -> enhancer.enhance(FinalConfig.class))
                .as("final 클래스 입력 시 IllegalArgumentException 발생")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final");
    }
}
