package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.samples.basic.MetaTaggedService;
import com.choisk.sfs.context.samples.basic.SimpleService;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanIntegrationTest {

    @Test
    void scanRegistersAndGetBeanWorks() {
        try (var ctx = new AnnotationConfigApplicationContext("com.choisk.sfs.context.samples.basic")) {
            assertThat(ctx.containsBean("simpleService")).isTrue();
            assertThat(ctx.containsBean("metaTaggedService")).isTrue();
            assertThat(ctx.containsBean("plainPojo")).isFalse();
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
            assertThat(ctx.getBean(MetaTaggedService.class)).isNotNull();
        }
    }

    @Test
    void registerEntrypointAlsoWorks() {
        try (var ctx = new AnnotationConfigApplicationContext(SimpleService.class)) {
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
        }
    }
}
