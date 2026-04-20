package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationMetadataReaderTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Sample {
        String value() default "default";
        int count() default 1;
    }

    @Sample(value = "hi", count = 42)
    static class Target {}

    @Test
    void readsClassName() throws Exception {
        var meta = readFor(Target.class);
        assertThat(meta.className()).isEqualTo(Target.class.getName());
    }

    @Test
    void detectsAnnotationPresence() throws Exception {
        var meta = readFor(Target.class);
        assertThat(meta.hasAnnotation(Sample.class.getName())).isTrue();
    }

    @Test
    void extractsAnnotationAttributes() throws Exception {
        var meta = readFor(Target.class);
        Object value = meta.attributesFor(Sample.class.getName()).get("value");
        Object count = meta.attributesFor(Sample.class.getName()).get("count");
        assertThat(value).isEqualTo("hi");
        assertThat(count).isEqualTo(42);
    }

    private AnnotationMetadata readFor(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return AnnotationMetadataReader.read(in.readAllBytes());
        }
    }
}
