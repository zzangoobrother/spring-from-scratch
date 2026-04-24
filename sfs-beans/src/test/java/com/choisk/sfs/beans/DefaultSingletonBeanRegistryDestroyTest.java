package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * registerSingleton으로 직접 등록된 DisposableBean도 destroySingletons 호출 시
 * destroy 콜백이 실행되어야 한다. 1B-α의 RefreshFailureCleanupTest/CloseAndShutdownHookTest가 이 동작에 의존.
 */
class DefaultSingletonBeanRegistryDestroyTest {

    static class TrackingDisposable implements DisposableBean {
        final List<String> log;
        TrackingDisposable(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    static class TrackingThrowingDisposable implements DisposableBean {
        @Override public void destroy() throws Exception {
            throw new Exception("boom from destroy");
        }
    }

    @Test
    void registerSingletonWithDisposableTriggersDestroyCallback() {
        var registry = new DefaultSingletonBeanRegistry();
        var log = new ArrayList<String>();
        registry.registerSingleton("a", new TrackingDisposable(log));

        registry.destroySingletons();

        assertThat(log).containsExactly("destroyed");
    }

    @Test
    void registerSingletonWithNonDisposableDoesNotRegisterCallback() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.registerSingleton("plain", "just-a-string");

        // 예외 없이 통과해야 함 (콜백이 등록되지 않았는지는 간접 확인)
        registry.destroySingletons();
    }

    @Test
    void destroyIsExecutedInReverseRegistrationOrder() {
        var registry = new DefaultSingletonBeanRegistry();
        var log = new ArrayList<String>();
        registry.registerSingleton("a", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("a"); }
        });
        registry.registerSingleton("b", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("b"); }
        });

        registry.destroySingletons();

        assertThat(log).containsExactly("b", "a");  // LIFO
    }

    @Test
    void checkedExceptionFromDestroyIsSwallowedAndOthersStillRun() {
        var registry = new DefaultSingletonBeanRegistry();
        var log = new ArrayList<String>();
        registry.registerSingleton("throwing", new TrackingThrowingDisposable());
        registry.registerSingleton("ok", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("ok-destroyed"); }
        });

        registry.destroySingletons();  // 예외 새어나오면 안 됨

        // ok가 먼저 등록 역순으로 호출되어 완료되었는지 확인
        assertThat(log).containsExactly("ok-destroyed");
    }
}
