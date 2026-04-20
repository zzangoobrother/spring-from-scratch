package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassUtilsTest {

    @Test
    void getDefaultClassLoader_returnsContextOrSystem() {
        assertThat(ClassUtils.getDefaultClassLoader()).isNotNull();
    }

    @Test
    void forName_loadsClassByName() throws ClassNotFoundException {
        assertThat(ClassUtils.forName("java.lang.String", null)).isSameAs(String.class);
    }

    @Test
    void forName_throwsForMissing() {
        assertThatThrownBy(() -> ClassUtils.forName("com.nonexistent.Foo", null))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void isAssignableValue_handlesPrimitiveBoxing() {
        assertThat(ClassUtils.isAssignableValue(int.class, Integer.valueOf(42))).isTrue();
        assertThat(ClassUtils.isAssignableValue(String.class, 42)).isFalse();
    }
}
