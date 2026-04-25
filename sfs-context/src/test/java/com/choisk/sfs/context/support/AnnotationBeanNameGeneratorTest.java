package com.choisk.sfs.context.support;

import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationBeanNameGeneratorTest {

    @Component static class MyService {}
    @Component("customName") static class HasCustom {}
    @Service("svc") static class StereotypeWithName {}
    @Service static class StereotypeNoName {}
    @Component static class A {}  // 한 글자 → "a"

    private final AnnotationBeanNameGenerator gen = new AnnotationBeanNameGenerator();

    @Test
    void plainComponentReturnsClassNameLowerFirst() {
        assertThat(gen.generate(MyService.class)).isEqualTo("myService");
    }

    @Test
    void explicitValueOnComponentWins() {
        assertThat(gen.generate(HasCustom.class)).isEqualTo("customName");
    }

    @Test
    void explicitValueOnStereotypeWins() {
        assertThat(gen.generate(StereotypeWithName.class)).isEqualTo("svc");
    }

    @Test
    void stereotypeWithoutValueFallsBackToClassName() {
        assertThat(gen.generate(StereotypeNoName.class)).isEqualTo("stereotypeNoName");
    }

    @Test
    void singleCharClassNameLowercased() {
        assertThat(gen.generate(A.class)).isEqualTo("a");
    }
}
