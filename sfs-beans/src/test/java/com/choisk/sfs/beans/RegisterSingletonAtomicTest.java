package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * registerSingleton의 DisposableBean 감지가 singletonObjects.put과 같은
 * critical section 안에서 atomic하게 처리됨을 검증.
 * <p>
 * singletonObjects.put과 disposableBeans.put이 다른 lock에 있으면
 * 그 사이에 destroySingletons이 끼어들어 destroy 콜백이 누락될 수 있다.
 */
class RegisterSingletonAtomicTest {

    static class TestDisposable implements DisposableBean {
        boolean destroyed = false;

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    @Test
    void disposableRegisteredAtomically() {
        DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
        TestDisposable bean = new TestDisposable();

        registry.registerSingleton("d", bean);
        registry.destroySingletons();

        assertThat(bean.destroyed)
                .as("registerSingleton 직후 destroySingletons 호출 시 destroy가 반드시 실행되어야 함")
                .isTrue();
    }
}
