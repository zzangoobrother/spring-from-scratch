package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DependencyDescriptorTest {
    @Test
    void capturesTypeAndRequired() {
        DependencyDescriptor d = new DependencyDescriptor(String.class, true, "myField");
        assertThat(d.getDependencyType()).isEqualTo(String.class);
        assertThat(d.isRequired()).isTrue();
        assertThat(d.getDependencyName()).isEqualTo("myField");
    }
}
