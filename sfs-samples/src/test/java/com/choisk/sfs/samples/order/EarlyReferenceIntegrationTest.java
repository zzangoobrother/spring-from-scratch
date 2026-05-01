package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2B A-2 흡수 검증 (spec § 5.4): 순환 의존 빈에 transactional advice가 누락되지 않음.
 *
 * Phase 1A 3-level cache의 SIABPP getEarlyBeanReference 훅이 처음 의미 있게 사용됨.
 */
class EarlyReferenceIntegrationTest {

    @Test
    void circularDependencyAppliesTransactionalAdvice() {
        try (var ctx = new AnnotationConfigApplicationContext(CircularConfig.class)) {
            CircularA a = ctx.getBean(CircularA.class);
            CircularB b = ctx.getBean(CircularB.class);

            // 두 빈 모두 enhance되었는지 — getClass()가 원본과 다름
            assertThat(a.getClass()).isNotEqualTo(CircularA.class);
            assertThat(b.getClass()).isNotEqualTo(CircularB.class);

            // 실제 호출도 advice 적용 (BeanFactory 주입을 통한 호출에서도)
            a.callViaInjected();
            b.callViaInjected();
        }
    }

    @Test
    void earlyReferenceFromBSeesEnhancedA() {
        try (var ctx = new AnnotationConfigApplicationContext(CircularConfig.class)) {
            CircularB b = ctx.getBean(CircularB.class);

            // B 안에 주입된 A (early reference)도 enhance된 인스턴스여야 함
            CircularA injectedA = b.getInjectedA();
            assertThat(injectedA.getClass()).isNotEqualTo(CircularA.class);
        }
    }

    @Configuration
    static class CircularConfig {
        @Bean public TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
        @Bean public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }
        @Bean public TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
        @Bean public CircularA a() { return new CircularA(); }
        @Bean public CircularB b() { return new CircularB(); }
    }

    public static class CircularA {
        @Autowired CircularB b;
        @Transactional public void callViaInjected() { b.doSomething(); }
        @Transactional public void doSomething() { /* nothing */ }
    }

    public static class CircularB {
        @Autowired CircularA a;
        public CircularA getInjectedA() { return a; }
        @Transactional public void callViaInjected() { a.doSomething(); }
        @Transactional public void doSomething() { /* nothing */ }
    }
}
