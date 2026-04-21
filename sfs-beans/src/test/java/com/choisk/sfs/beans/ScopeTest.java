package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {
    @Test
    void singletonByName() {
        assertThat(Scope.byName("singleton")).isEqualTo(Scope.Singleton.INSTANCE);
    }

    @Test
    void prototypeByName() {
        assertThat(Scope.byName("prototype")).isEqualTo(Scope.Prototype.INSTANCE);
    }

    @Test
    void unknownName_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Scope.byName("request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }
}
