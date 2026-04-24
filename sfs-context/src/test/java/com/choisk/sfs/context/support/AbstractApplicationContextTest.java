package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractApplicationContextTest {

    /** 단계 호출을 기록하는 추적 컨텍스트. */
    static class TracingContext extends AbstractApplicationContext {
        final List<String> trace = new ArrayList<>();
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        boolean refreshed = false;

        @Override protected void refreshBeanFactory() {
            if (refreshed) throw new IllegalStateException("single-shot violated");
            refreshed = true;
            trace.add("refreshBeanFactory");
        }
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
        @Override protected void prepareRefresh()                                      { trace.add("prepareRefresh");        super.prepareRefresh(); }
        @Override protected ConfigurableListableBeanFactory obtainFreshBeanFactory()   { trace.add("obtainFreshBeanFactory");return super.obtainFreshBeanFactory(); }
        @Override protected void prepareBeanFactory(ConfigurableListableBeanFactory b) { trace.add("prepareBeanFactory"); }
        @Override protected void postProcessBeanFactory(ConfigurableListableBeanFactory b) { trace.add("postProcessBeanFactory"); }
        @Override protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory b) { trace.add("invokeBfpps"); super.invokeBeanFactoryPostProcessors(b); }
        @Override protected void registerBeanPostProcessors(ConfigurableListableBeanFactory b)      { trace.add("registerBpps"); }
        @Override protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory b) { trace.add("finishBfInit"); super.finishBeanFactoryInitialization(b); }
        @Override protected void finishRefresh()                                                    { trace.add("finishRefresh"); }
    }

    @Test
    void refreshExecutesEightStepsInOrder() {
        var ctx = new TracingContext();
        ctx.refresh();
        assertThat(ctx.trace).containsExactly(
            "prepareRefresh",
            "obtainFreshBeanFactory",
            "refreshBeanFactory",            // obtainFreshBeanFactory가 호출
            "prepareBeanFactory",
            "postProcessBeanFactory",
            "invokeBfpps",
            "registerBpps",
            "finishBfInit",
            "finishRefresh"
        );
        assertThat(ctx.isActive()).isTrue();
    }

    @Test
    void refreshIsSingleShot() {
        var ctx = new TracingContext();
        ctx.refresh();
        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("single-shot");
    }
}
