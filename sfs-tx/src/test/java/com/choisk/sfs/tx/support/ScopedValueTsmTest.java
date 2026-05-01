package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedValueTsmTest {

    private final ScopedValueTsm tsm = new ScopedValueTsm();

    @Test
    void bindAndGetWithinScope() {
        Object key = new Object();

        tsm.runInScope(() -> {
            tsm.bindResource(key, "conn#1");
            assertThat(tsm.getResource(key)).isEqualTo("conn#1");
        });
    }

    @Test
    void bindOutsideScopeThrows() {
        assertThatThrownBy(() -> tsm.bindResource(new Object(), "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in scope");
    }

    @Test
    void scopesAreNestedAndIsolated() {
        Object key = new Object();

        tsm.runInScope(() -> {
            tsm.bindResource(key, "outer-conn");

            tsm.runInScope(() -> {
                // nested scope — 새 map. outer 값은 보이지 않음 (immutable scope)
                assertThat(tsm.getResource(key)).isNull();
                tsm.bindResource(key, "inner-conn");
                assertThat(tsm.getResource(key)).isEqualTo("inner-conn");
            });

            // outer 복원 (자동 — scope 종료 시)
            assertThat(tsm.getResource(key)).isEqualTo("outer-conn");
        });
    }
}
