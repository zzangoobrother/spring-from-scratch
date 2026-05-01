package com.choisk.sfs.tx.boot;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionalBeanPostProcessorTest {

    private DefaultListableBeanFactory beanFactory;
    private TransactionalBeanPostProcessor bpp;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tm", new MockTransactionManager(new ThreadLocalTsm()));
        bpp = new TransactionalBeanPostProcessor();
        bpp.setBeanFactory(beanFactory);
    }

    @Test
    void enhanceTransactionalBean() {
        SampleTransactionalService bean = new SampleTransactionalService();

        Object enhanced = bpp.postProcessAfterInitialization(bean, "sample");

        assertThat(enhanced.getClass()).isNotEqualTo(SampleTransactionalService.class);
        assertThat(enhanced).isInstanceOf(SampleTransactionalService.class); // 서브클래스
    }

    @Test
    void skipBeansWithoutTransactionalAnnotation() {
        PlainService bean = new PlainService();

        Object result = bpp.postProcessAfterInitialization(bean, "plain");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void selfIsolationOfBeanPostProcessor() {
        // BPP는 자신을 enhance하지 않음
        Object result = bpp.postProcessAfterInitialization(bpp, "bpp");

        assertThat(result).isSameAs(bpp);
    }

    @Test
    void warnOnFinalTransactionalMethod() {
        // A-1 흡수: final @Transactional 메서드는 silent skip 대신 WARN
        ServiceWithFinalTransactional bean = new ServiceWithFinalTransactional();

        // 시스템 출력 캡처는 본 단위 테스트의 부수가 아님 — WARN 메시지가 로그/예외로 박제됨을 검증
        // 본 phase는 WARN 우선 (의사결정 #12)
        Object enhanced = bpp.postProcessAfterInitialization(bean, "withFinal");

        // 통합 검증: enhance는 진행되지만 final 메서드는 advice 비적용
        assertThat(enhanced).isNotNull();
        assertThat(bpp.getLastFinalMethodWarnings()).isNotEmpty();
    }

    // ===== test fixtures =====

    static class SampleTransactionalService {
        @Transactional public String doWork() { return "ok"; }
    }

    static class PlainService {
        public String doWork() { return "ok"; }
    }

    static class ServiceWithFinalTransactional {
        @Transactional public final String doFinalWork() { return "ok"; }
    }
}
