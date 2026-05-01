package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.controller.OrderController;
import com.choisk.sfs.samples.todo.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionDemoApplicationTest {

    private AnnotationConfigApplicationContext ctx;
    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM audit_log");
        }
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void tearDown() {
        System.setOut(original);
        if (ctx != null) ctx.close();
    }

    @Test
    void successScenarioOutputsOrderPlacedLine() {
        OrderController controller = ctx.getBean(OrderController.class);

        controller.placeOrder("Book", 5_000);

        String log = stdout.toString();
        assertThat(log).contains("[OrderController] order placed: Book");
        assertThat(log).doesNotContain("order failed");
    }

    @Test
    void failureScenarioOutputsOrderFailedLineAndAuditSurvives() {
        OrderController controller = ctx.getBean(OrderController.class);

        controller.placeOrder("Yacht", 200_000);

        String log = stdout.toString();
        assertThat(log).contains("[OrderController] order failed");
        assertThat(log).contains("amount limit exceeded");

        // audit는 살아남음 (REQUIRES_NEW)
        var auditRepo = ctx.getBean(com.choisk.sfs.samples.order.repository.AuditRepository.class);
        assertThat(auditRepo.count()).isEqualTo(1L);
    }
}
