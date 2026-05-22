package com.choisk.sfs.orm.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SfsPersistentListTest {

    private PersistenceContext context;
    private FakeCollectionLoader loader;
    private SfsPersistentList<String> list;

    @BeforeEach
    void setUp() {
        context = new PersistenceContext();
        loader = new FakeCollectionLoader();
        list = new SfsPersistentList<>(String.class, 1L, "user_id", loader, context);
    }

    @Test
    void 첫_메서드_호출_전에는_isInitialized_false() {
        assertThat(list.isInitialized()).isFalse();
        assertThat(loader.callCount.get()).isZero();
    }

    @Test
    void size_첫_호출_시_loader_1회_호출되고_캐시() {
        // when: size() 두 번 호출
        list.size();
        list.size();

        // then: loader는 1회만 호출 (캐시 hit)
        assertThat(loader.callCount.get()).isEqualTo(1);
        assertThat(list.isInitialized()).isTrue();
    }

    @Test
    void add_write_메서드도_lazy_init_trigger() {
        // when: add() 호출 (write 메서드)
        list.add("new");

        // then: loader 호출됨 (모든 메서드가 trigger — write-only optim 미도입)
        assertThat(loader.callCount.get()).isEqualTo(1);
        assertThat(list.isInitialized()).isTrue();
    }

    @Test
    void context_closed_후_호출_시_SfsLazyInitializationException() {
        context.close();

        assertThatThrownBy(() -> list.size())
                .isInstanceOf(com.choisk.sfs.orm.exception.SfsLazyInitializationException.class)
                .hasMessageContaining("String#1");
    }

    // ─── fake loader ──────────────────────────────────────────
    static class FakeCollectionLoader implements CollectionLoader {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                            Object fkValue, PersistenceContext ctx) {
            callCount.incrementAndGet();
            // WHY: ArrayList로 반환해야 add() 같은 write 메서드가 UnsupportedOperationException 없이 동작
            return (List<T>) new ArrayList<>(List.of("a", "b", "c"));
        }
    }
}
