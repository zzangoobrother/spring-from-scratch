package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSingletonBeanRegistryTest {

    @Test
    void registerAndGetCompletesHit() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.registerSingleton("foo", "hello");
        assertThat(registry.getSingleton("foo")).isEqualTo("hello");
    }

    @Test
    void missingSingletonReturnsNull() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.getSingleton("missing")).isNull();
    }

    @Test
    void containsSingleton() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.containsSingleton("x")).isFalse();
        registry.registerSingleton("x", 42);
        assertThat(registry.containsSingleton("x")).isTrue();
    }

    @Test
    void factoryLevelCacheExecutesExactlyOnce() {
        var registry = new DefaultSingletonBeanRegistry();
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerSingletonFactory("early", () -> {
            counter.incrementAndGet();
            return "producedOnce";
        });

        // 처음 조회: 3차에 factory 존재 → DeferredFactory
        assertThat(registry.lookup("early")).isInstanceOf(CacheLookup.DeferredFactory.class);

        // 명시적 승격: factory 실행 → 2차로 이동
        var produced = registry.promoteToEarlyReference("early");
        assertThat(produced).isEqualTo("producedOnce");

        // 승격 후 조회: 2차에서 hit → EarlyReference, factory 재실행 X
        var first = registry.lookup("early");
        assertThat(first).isInstanceOf(CacheLookup.EarlyReference.class);
        assertThat(((CacheLookup.EarlyReference) first).bean()).isEqualTo("producedOnce");

        // 두 번째 조회: 2차에서 hit, factory 재실행 X
        var second = registry.lookup("early");
        assertThat(second).isInstanceOf(CacheLookup.EarlyReference.class);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void lookupOrderIs_CompleteThenEarlyThenFactory() {
        var registry = new DefaultSingletonBeanRegistry();

        registry.registerSingletonFactory("name", () -> "fromFactory");
        assertThat(registry.lookup("name")).isInstanceOf(CacheLookup.DeferredFactory.class);

        // promote 요청 → 2차로 이동
        registry.promoteToEarlyReference("name");
        assertThat(registry.lookup("name")).isInstanceOf(CacheLookup.EarlyReference.class);

        // 완성 빈 등록 시 1차가 우선
        registry.registerSingleton("name2", "complete");
        assertThat(registry.lookup("name2")).isInstanceOf(CacheLookup.Complete.class);
    }

    @Test
    void missReturnsMiss() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.lookup("nope")).isInstanceOf(CacheLookup.Miss.class);
    }

    @Test
    void creationTrackingDetectsRecursion() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.beforeSingletonCreation("a");
        assertThat(registry.isCurrentlyInCreation("a")).isTrue();
        registry.afterSingletonCreation("a");
        assertThat(registry.isCurrentlyInCreation("a")).isFalse();
    }

    @Test
    void creationChainReturnsOrdered() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.beforeSingletonCreation("a");
        registry.beforeSingletonCreation("b");
        assertThat(registry.getCurrentCreationChain()).containsExactly("a", "b");
        registry.afterSingletonCreation("b");
        registry.afterSingletonCreation("a");
    }

    @Test
    void destroySingletonsInvokesCallbacksInReverseOrder() {
        var registry = new DefaultSingletonBeanRegistry();
        var order = new java.util.ArrayList<String>();

        registry.registerSingleton("first", "bean1");
        registry.registerDisposableBean("first", () -> order.add("first"));
        registry.registerSingleton("second", "bean2");
        registry.registerDisposableBean("second", () -> order.add("second"));

        registry.destroySingletons();

        assertThat(order).containsExactly("second", "first");
        assertThat(registry.containsSingleton("first")).isFalse();
    }

    @Test
    void destroyContinuesOnFailure() {
        var registry = new DefaultSingletonBeanRegistry();
        var invoked = new java.util.ArrayList<String>();
        registry.registerSingleton("a", "a");
        registry.registerDisposableBean("a", () -> invoked.add("a"));
        registry.registerSingleton("b", "b");
        registry.registerDisposableBean("b", () -> { throw new RuntimeException("boom"); });
        registry.registerSingleton("c", "c");
        registry.registerDisposableBean("c", () -> invoked.add("c"));

        registry.destroySingletons();

        // b는 실패했지만 a와 c는 역순으로 실행됨 (c → [b 실패] → a)
        assertThat(invoked).containsExactly("c", "a");
    }
}
