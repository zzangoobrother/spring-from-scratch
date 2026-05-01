package com.choisk.sfs.tx.support;

import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionInterceptorTest {

    private DefaultListableBeanFactory beanFactory;
    private RecordingTm primaryTm;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        primaryTm = new RecordingTm("primary");
        beanFactory.registerSingleton("primaryTm", primaryTm);
    }

    @Test
    void normalReturnTriggersCommit() throws Throwable {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void runtimeExceptionTriggersRollback() throws Throwable {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        assertThatThrownBy(() ->
                interceptor.invoke(new SampleService(), m, () -> { throw new IllegalStateException("biz"); })
        ).isInstanceOf(IllegalStateException.class);

        assertThat(primaryTm.events).containsExactly("getTransaction", "rollback");
    }

    @Test
    void checkedExceptionTriggersCommit() throws Throwable {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        assertThatThrownBy(() ->
                interceptor.invoke(new SampleService(), m, () -> { throw new java.io.IOException("io"); })
        ).isInstanceOf(java.io.IOException.class);

        // checked는 commit (Spring 본가 정합)
        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void namedTransactionManagerRouting() throws Throwable {
        RecordingTm secondary = new RecordingTm("secondary");
        beanFactory.registerSingleton("secondaryTm", secondary);

        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWorkWithSecondary");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(secondary.events).containsExactly("getTransaction", "commit");
        assertThat(primaryTm.events).isEmpty();
    }

    @Test
    void typeBasedFallbackWhenTmNameNotSpecified() throws Throwable {
        // primary만 등록되어 있고, @Transactional은 transactionManager 미지정
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void requiresNewPropagationPassedToTm() throws Throwable {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doRequiresNew");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.lastDefinition.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ===== test fixtures =====

    static class SampleService {
        @Transactional public String doWork() { return "ok"; }
        @Transactional(transactionManager = "secondaryTm") public String doWorkWithSecondary() { return "ok"; }
        @Transactional(propagation = Propagation.REQUIRES_NEW) public String doRequiresNew() { return "ok"; }
    }

    static class RecordingTm implements PlatformTransactionManager {
        final String name;
        final List<String> events = new ArrayList<>();
        com.choisk.sfs.tx.TransactionDefinition lastDefinition;

        RecordingTm(String name) { this.name = name; }

        @Override public com.choisk.sfs.tx.TransactionStatus getTransaction(com.choisk.sfs.tx.TransactionDefinition def) {
            events.add("getTransaction");
            this.lastDefinition = def;
            return new DefaultTransactionStatus(name + "-tx", true, null);
        }
        @Override public void commit(com.choisk.sfs.tx.TransactionStatus status) { events.add("commit"); }
        @Override public void rollback(com.choisk.sfs.tx.TransactionStatus status) { events.add("rollback"); }
    }
}
