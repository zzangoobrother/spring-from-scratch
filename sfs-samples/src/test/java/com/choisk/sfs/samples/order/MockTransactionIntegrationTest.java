package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock TM end-to-end 통합 테스트 — vertical slice 1 첫 살아있는 시연.
 *
 * <p>AnnotationConfigApplicationContext + TransactionalBeanPostProcessor 자동 advice 적용 검증.
 * 3 시나리오: 단순 commit / RuntimeException rollback / REQUIRES_NEW suspend+resume.
 *
 * <p>편차: plan fixture의 final 필드 + 명시 생성자가 BPP no-arg 한계와 부정합
 * → package-private 필드 + @Bean 팩토리 직접 할당 패턴으로 수정.
 */
class MockTransactionIntegrationTest {

    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void captureStdout() {
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    @Test
    void singleTransactionalMethodTriggersBeginAndCommit() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            demo.doSimple();

            String log = stdout.toString();
            assertThat(log).contains("[TX] BEGIN");
            assertThat(log).contains("[TX] COMMIT");
        }
    }

    @Test
    void runtimeExceptionTriggersRollback() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            try { demo.doFailing(); } catch (RuntimeException ignored) {}

            String log = stdout.toString();
            assertThat(log).contains("[TX] BEGIN");
            assertThat(log).contains("[TX] ROLLBACK");
            assertThat(log).doesNotContain("[TX] COMMIT");
        }
    }

    @Test
    void requiresNewProducesSuspendAndResume() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            demo.doOuter(); // outer(REQUIRED) → inner(REQUIRES_NEW)

            String log = stdout.toString();
            assertThat(log).contains("[TX] SUSPEND");
            assertThat(log).contains("[TX] RESUME");
            // 최소 BEGIN 2회 (outer + inner)
            long beginCount = log.lines().filter(l -> l.contains("[TX] BEGIN")).count();
            assertThat(beginCount).isEqualTo(2L);
        }
    }

    // ===== 테스트 전용 설정 =====

    /**
     * 격리된 테스트 전용 AppConfig.
     * 메인 AppConfig 수정 없이 BPP + MockTM을 등록.
     * Phase 3 Task C5에서 DataSource + 진짜 TM 등록 시 본 config는 그대로 유지.
     */
    @Configuration
    static class MockTxAppConfig {
        @Bean
        public TransactionSynchronizationManager tsm() {
            return new ThreadLocalTsm();
        }

        @Bean
        public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }

        @Bean
        public TransactionalBeanPostProcessor txBpp() {
            return new TransactionalBeanPostProcessor();
        }

        /**
         * BPP 한계(no-arg 생성자 + non-final 필드) 정합 패턴.
         * Phase 1/2B TodoService.java와 동일 방식 — package-private 필드 직접 할당.
         */
        @Bean
        public DemoService demoService(InnerService inner) {
            DemoService svc = new DemoService();
            svc.inner = inner;
            return svc;
        }

        @Bean
        public InnerService innerService() {
            return new InnerService();
        }
    }

    /**
     * 테스트용 서비스. BPP 정합 조건:
     * - 명시 생성자 없음 (default no-arg 사용)
     * - 필드 non-final, package-private (copyFields reflection 복사 가능)
     * - @Component 없음 (component scan 미사용)
     */
    static class DemoService {
        InnerService inner; // package-private, non-final — BPP copyFields 정합

        @Transactional
        public void doSimple() { /* nothing */ }

        @Transactional
        public void doFailing() {
            throw new RuntimeException("biz");
        }

        @Transactional
        public void doOuter() {
            inner.doInner();
        }
    }

    /**
     * 테스트용 내부 서비스. REQUIRES_NEW propagation 시연용.
     */
    static class InnerService {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void doInner() { /* nothing */ }
    }
}
