package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.samples.basic.MetaTaggedService;
import com.choisk.sfs.context.samples.basic.SimpleService;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanFromConfigurationTest {

    @Configuration
    @ComponentScan(basePackages = "com.choisk.sfs.context.samples.basic")
    static class ScanFromConfig {}

    // value()가 basePackages()의 alias임을 박제하는 별도 설정 클래스
    @Configuration
    @ComponentScan("com.choisk.sfs.context.samples.basic")  // value() 사용
    static class ValueAliasConfig {}

    @Test
    void componentScanInsideConfigurationDiscoversBeans() {
        try (var ctx = new AnnotationConfigApplicationContext(ScanFromConfig.class)) {
            assertThat(ctx.containsBean("simpleService"))
                    .as("@ComponentScan이 패키지의 @Component 빈을 발견해야 함")
                    .isTrue();
            assertThat(ctx.containsBean("metaTaggedService")).isTrue();
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
            assertThat(ctx.getBean(MetaTaggedService.class)).isNotNull();
        }
    }

    @Test
    void componentScanValueAliasIsHonored() {
        try (var ctx = new AnnotationConfigApplicationContext(ValueAliasConfig.class)) {
            assertThat(ctx.containsBean("simpleService"))
                    .as("value()는 basePackages()의 alias")
                    .isTrue();
        }
    }
}
