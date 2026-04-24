package com.choisk.sfs.context.annotation;

import com.choisk.sfs.core.AnnotationUtils;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Service/@Repository/@Controller/@Configuration이 @Component를 메타로 보유하는지 검증.
 * 스캐너가 정확히 이 동작에 의존하므로 단독 회귀 테스트.
 */
class StereotypeMetaTest {

    @Service static class S {}
    @Repository static class R {}
    @Controller static class C {}
    @Configuration static class Cfg {}
    static class Plain {}

    @Test
    void serviceIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(S.class, Component.class)).isTrue();
    }

    @Test
    void repositoryIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(R.class, Component.class)).isTrue();
    }

    @Test
    void controllerIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(C.class, Component.class)).isTrue();
    }

    @Test
    void configurationIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(Cfg.class, Component.class)).isTrue();
    }

    @Test
    void plainClassIsNotComponent() {
        assertThat(AnnotationUtils.isAnnotated(Plain.class, Component.class)).isFalse();
    }
}
