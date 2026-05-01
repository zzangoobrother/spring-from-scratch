package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class MockTransactionManagerTest {

    private final ThreadLocalTsm tsm = new ThreadLocalTsm();
    private MockTransactionManager tm;
    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void captureStdout() {
        tm = new MockTransactionManager(tsm);
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(original);
        tsm.clearAll();
    }

    @Test
    void beginThenCommitProducesBeginAndCommitLines() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        tm.commit(status);

        String log = stdout.toString();
        assertThat(log).contains("[TX] BEGIN");
        assertThat(log).contains("[TX] COMMIT");
        assertThat(log).doesNotContain("[TX] ROLLBACK");
    }

    @Test
    void beginThenRollbackProducesBeginAndRollbackLines() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        tm.rollback(status);

        String log = stdout.toString();
        assertThat(log).contains("[TX] BEGIN");
        assertThat(log).contains("[TX] ROLLBACK");
        assertThat(log).doesNotContain("[TX] COMMIT");
    }

    @Test
    void requiresNewWhileExistingProducesSuspendAndResume() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.requiresNew());
        tm.commit(inner);
        tm.commit(outer);

        String log = stdout.toString();
        assertThat(log).contains("[TX] SUSPEND");
        assertThat(log).contains("[TX] RESUME");
        // 순서 확인
        assertThat(log.indexOf("[TX] SUSPEND"))
                .isLessThan(log.indexOf("[TX] RESUME"));
    }

    @Test
    void requiredJoinDoesNotProduceNewBegin() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.required());

        assertThat(inner.isNewTransaction()).isFalse();
        tm.commit(inner);
        tm.commit(outer);

        // BEGIN은 outer 1회만 — 콘솔에 BEGIN 라인이 정확히 1번
        long beginCount = stdout.toString().lines().filter(l -> l.contains("[TX] BEGIN")).count();
        assertThat(beginCount).isEqualTo(1L);
    }
}
