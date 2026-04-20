package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssertTest {

    @Test
    void notNull_throwsWhenNull() {
        assertThatThrownBy(() -> Assert.notNull(null, "target"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target must not be null");
    }

    @Test
    void notNull_returnsValueWhenPresent() {
        var result = Assert.notNull("hello", "target");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void hasText_throwsOnBlank() {
        assertThatThrownBy(() -> Assert.hasText("   ", "name"))
                .hasMessageContaining("must have text");
    }

    @Test
    void isAssignable_validatesSuperType() {
        Assert.isAssignable(Number.class, Integer.class); // no throw
        assertThatThrownBy(() -> Assert.isAssignable(String.class, Integer.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
