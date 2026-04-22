package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanNameAware;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.InitializingBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 32b: BeanPostProcessor 호출 순서 통합 테스트.
 * Aware → BPP before → afterPropertiesSet → customInit → BPP after 순서를 검증한다.
 */
class BeanPostProcessorOrderTest {

    static class Widget implements BeanNameAware, InitializingBean {
        List<String> calls = new ArrayList<>();
        @Override public void setBeanName(String name) { calls.add("awareName:" + name); }
        @Override public void afterPropertiesSet() { calls.add("afterProps"); }
        public void customInit() { calls.add("customInit"); }
    }

    static class TracingBPP implements BeanPostProcessor {
        final List<String> log;
        TracingBPP(List<String> log) { this.log = log; }
        public Object postProcessBeforeInitialization(Object bean, String n) {
            if (bean instanceof Widget w) w.calls.add("bpp:before");
            return bean;
        }
        public Object postProcessAfterInitialization(Object bean, String n) {
            if (bean instanceof Widget w) w.calls.add("bpp:after");
            return bean;
        }
    }

    @Test
    void callbackOrderMatchesSpringSpec() {
        var factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new TracingBPP(new ArrayList<>()));
        factory.registerBeanDefinition("w",
                new BeanDefinition(Widget.class).setInitMethodName("customInit"));
        var w = factory.getBean("w", Widget.class);
        assertThat(w.calls).containsExactly(
                "awareName:w",
                "bpp:before",
                "afterProps",
                "customInit",
                "bpp:after"
        );
    }
}
