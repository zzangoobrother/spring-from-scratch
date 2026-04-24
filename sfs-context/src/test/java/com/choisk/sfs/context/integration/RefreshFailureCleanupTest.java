package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DisposableBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.support.AbstractApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshFailureCleanupTest {

    /** destroy 호출을 외부 리스트에 기록. */
    static class Tracking implements DisposableBean {
        final List<String> log;
        Tracking(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    /** 5단계(BFPP) 실행 중에 throw하는 BFPP. */
    static class ExplodingBfpp implements BeanFactoryPostProcessor {
        @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
            throw new RuntimeException("boom in BFPP");
        }
    }

    static class TestContext extends AbstractApplicationContext {
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        @Override protected void refreshBeanFactory() {}
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
    }

    @Test
    void refreshFailureTriggersDestroyBeans() {
        var log = new ArrayList<String>();
        var ctx = new TestContext();
        // 7단계 도달 전 5단계에서 throw 시키되, 1단계 미만에 빈을 미리 등록해 destroy 검증
        ctx.getBeanFactory().registerSingleton("preset", new Tracking(log));
        ctx.addBeanFactoryPostProcessor(new ExplodingBfpp());

        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(RuntimeException.class)
            .hasMessage("boom in BFPP");

        assertThat(log).containsExactly("destroyed");  // destroyBeans()가 호출됐음을 입증
        assertThat(ctx.isActive()).isFalse();
    }
}
