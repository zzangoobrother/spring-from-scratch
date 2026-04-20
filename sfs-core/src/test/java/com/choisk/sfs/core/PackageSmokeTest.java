package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PackageSmokeTest {
    @Test
    void packageIsLoadable() {
        assertThat(getClass().getPackageName()).isEqualTo("com.choisk.sfs.core");
    }
}
