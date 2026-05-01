package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import com.choisk.sfs.context.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2B C-3 흡수 검증 (spec § 5.4): enhanced(byte-buddy 서브클래스) 트랜잭셔널 빈도
 * close() 시 @PreDestroy 콜백이 정상 호출됨.
 *
 * <p>Phase 2B AOP 인프라가 enhanced 빈에 destroy 트리거를 잃지 않음을 확인 — Phase 1B-β destroy
 * 인프라와 Phase 2B/3 enhance 경로의 교차 검증.
 */
class EnhancedBeanDestroyTest {

    @Test
    void preDestroyOnEnhancedTransactionalBean() {
        EnhancedDestroyConfig config;
        try (var ctx = new AnnotationConfigApplicationContext(EnhancedDestroyConfig.class)) {
            DestroyTrackingService bean = ctx.getBean(DestroyTrackingService.class);

            // enhance 확인
            assertThat(bean.getClass()).isNotEqualTo(DestroyTrackingService.class);

            // ctx.close()가 try-with-resources 종료 시 호출됨 → @PreDestroy 트리거
            config = ctx.getBean(EnhancedDestroyConfig.class);
        }

        // 다른 ctx가 같은 config를 공유하지 않으므로, 직접 destroyed 플래그 확인은 어렵다.
        // 대신: 다음 test infra 패턴으로 검증 — 정적 카운터 사용
        assertThat(DestroyTrackingService.destroyCount).isGreaterThan(0);
    }

    @Configuration
    static class EnhancedDestroyConfig {
        @Bean public TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
        @Bean public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }
        @Bean public TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
        @Bean public DestroyTrackingService destroyTracking() { return new DestroyTrackingService(); }
    }

    public static class DestroyTrackingService {
        public static int destroyCount = 0;

        @Transactional public void doWork() { /* nothing */ }

        @PreDestroy
        public void cleanup() {
            destroyCount++;
        }
    }
}
