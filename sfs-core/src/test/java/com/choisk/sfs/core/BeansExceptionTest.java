package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BeansException sealed 계층의 기본 속성 검증.
 * <p>sealed 제약으로 익명 클래스 상속이 불가하므로, permit된 구체 서브타입
 * (BeanDefinitionStoreException)을 통해 간접 검증한다.
 */
class BeansExceptionTest {

    @Test
    void isRuntimeException() {
        var ex = new BeanDefinitionStoreException("boom");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex).isInstanceOf(BeansException.class);
    }

    @Test
    void preservesCauseChain() {
        var cause = new IllegalStateException("root cause");
        var ex = new BeanDefinitionStoreException("wrapper", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
