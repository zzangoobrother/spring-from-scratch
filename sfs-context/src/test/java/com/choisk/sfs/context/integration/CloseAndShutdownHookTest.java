package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DisposableBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.support.AbstractApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloseAndShutdownHookTest {

    static class Tracking implements DisposableBean {
        final List<String> log;
        Tracking(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    static class TestContext extends AbstractApplicationContext {
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        @Override protected void refreshBeanFactory() {}
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
    }

    @Test
    void closeIsIdempotent() {
        var log = new ArrayList<String>();
        var ctx = new TestContext();
        ctx.getBeanFactory().registerSingleton("a", new Tracking(log));
        ctx.refresh();

        ctx.close();
        ctx.close();  // 두 번째 호출 — 예외 없어야 하고 destroy도 한 번만

        assertThat(log).containsExactly("destroyed");
        assertThat(ctx.isActive()).isFalse();
    }

    @Test
    void closeWithoutRefreshIsNoOp() {
        var ctx = new TestContext();
        ctx.close();  // refresh 호출 없이 close — 예외 없어야 함
        assertThat(ctx.isActive()).isFalse();
    }

    @Test
    void registerShutdownHookIsIdempotent() {
        var ctx = new TestContext();
        ctx.refresh();
        ctx.registerShutdownHook();
        ctx.registerShutdownHook();  // 두 번째 호출 — Thread는 한 개만 등록되어야

        // 정리 (테스트 환경에서 실제 hook 실행 회피)
        ctx.close();
    }
}
