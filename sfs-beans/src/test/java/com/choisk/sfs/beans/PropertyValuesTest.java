package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PropertyValuesTest {
    @Test
    void addAndLookup() {
        var pvs = new PropertyValues()
                .add(new PropertyValue("name", "Alice"))
                .add(new PropertyValue("friend", new BeanReference("userBean")));
        assertThat(pvs.get("name").value()).isEqualTo("Alice");
        assertThat(pvs.get("friend").value()).isInstanceOf(BeanReference.class);
    }

    @Test
    void duplicateAddReplaces() {
        var pvs = new PropertyValues()
                .add(new PropertyValue("x", 1))
                .add(new PropertyValue("x", 2));
        assertThat(pvs.get("x").value()).isEqualTo(2);
    }
}
