package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectionUtilsTest {

    static class Parent {
        private String parentField = "p";
        void parentMethod() {}
    }

    static class Child extends Parent {
        private int childField = 1;
        void childMethod() {}
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
    void findMethod_walksInheritance() {
        var method = ReflectionUtils.findMethod(Child.class, "parentMethod");
        assertThat(method).isNotNull();
    }

    // ── doWithFields 단위 테스트 ──────────────────────────────────────────

    @Test
    void doWithFields_includesParentClassFields() {
        // 자식·부모 클래스의 필드를 모두 방문해야 함
        List<String> names = new ArrayList<>();
        ReflectionUtils.doWithFields(Child.class, f -> names.add(f.getName()));
        assertThat(names).contains("childField", "parentField");
    }

    @Test
    void doWithFields_excludesObjectClassFields() {
        // Object까지 올라가지 않아야 함 (Object는 traversal 종료점)
        List<String> names = new ArrayList<>();
        ReflectionUtils.doWithFields(Child.class, f -> names.add(f.getDeclaringClass().getName()));
        assertThat(names).doesNotContain(Object.class.getName());
    }

    // ── doWithMethods 단위 테스트 ─────────────────────────────────────────

    @Test
    void doWithMethods_includesParentClassMethods() {
        // 자식·부모 클래스의 메서드를 모두 방문해야 함
        List<String> names = new ArrayList<>();
        ReflectionUtils.doWithMethods(Child.class, m -> names.add(m.getName()));
        assertThat(names).contains("childMethod", "parentMethod");
    }

    @Test
    void doWithMethods_excludesObjectClassMethods() {
        // Object 메서드(equals, hashCode 등)는 traversal에서 제외되어야 함
        List<String> declaringClasses = new ArrayList<>();
        ReflectionUtils.doWithMethods(Child.class, m -> declaringClasses.add(m.getDeclaringClass().getName()));
        assertThat(declaringClasses).doesNotContain(Object.class.getName());
    }
}
