package com.choisk.sfs.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationUtilsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Marker {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Marker
    @interface MetaMarker {}  // Marker를 메타-애노테이션으로 가짐

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @MetaMarker
    @interface DeepMeta {}  // MetaMarker를 통해 Marker를 간접 보유

    // 사이클 방지 검증용: A→B, B→A (TYPE도 허용해야 CycleMarked 클래스에 붙일 수 있음)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
    @interface CycleA {}
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
    @CycleA
    @interface CycleB {}

    @Marker static class DirectlyMarked {}
    @MetaMarker static class MetaMarked {}
    @DeepMeta static class DeepMetaMarked {}
    @CycleB static class CycleMarked {}
    static class NotMarked {}

    @Test
    void directAnnotationIsDetected() {
        assertThat(AnnotationUtils.isAnnotated(DirectlyMarked.class, Marker.class)).isTrue();
    }

    @Test
    void metaAnnotationIsDetectedRecursively() {
        assertThat(AnnotationUtils.isAnnotated(MetaMarked.class, Marker.class)).isTrue();
    }

    @Test
    void deepMetaAnnotationIsDetectedTwoLevelsDown() {
        assertThat(AnnotationUtils.isAnnotated(DeepMetaMarked.class, Marker.class)).isTrue();
    }

    @Test
    void notAnnotatedReturnsFalse() {
        assertThat(AnnotationUtils.isAnnotated(NotMarked.class, Marker.class)).isFalse();
    }

    @Test
    void cyclicMetaAnnotationsDoNotCauseInfiniteLoop() {
        // Marker는 CycleA/B 어디에도 없으므로 false. 무한루프 없이 종료가 핵심.
        assertThat(AnnotationUtils.isAnnotated(CycleMarked.class, Marker.class)).isFalse();
    }
}
