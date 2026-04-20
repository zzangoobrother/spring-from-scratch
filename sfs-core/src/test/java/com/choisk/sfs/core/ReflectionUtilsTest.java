package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReflectionUtilsTest {

    static class Parent {
        private String parentField = "p";
        void parentMethod() {}
    }

    static class Child extends Parent {
        private int childField = 1;
    }

    @Test
    void findField_walksInheritance() {
        var field = ReflectionUtils.findField(Child.class, "parentField");
        assertThat(field).isNotNull();
        assertThat(field.getDeclaringClass()).isEqualTo(Parent.class);
    }

    @Test
    void findField_returnsNullForMissing() {
        assertThat(ReflectionUtils.findField(Child.class, "missing")).isNull();
    }

    @Test
    void doWithFields_visitsInheritedFields() {
        var names = new java.util.ArrayList<String>();
        ReflectionUtils.doWithFields(Child.class, f -> names.add(f.getName()));
        assertThat(names).contains("parentField", "childField");
    }

    @Test
    void findMethod_walksInheritance() {
        var method = ReflectionUtils.findMethod(Child.class, "parentMethod");
        assertThat(method).isNotNull();
    }
}
