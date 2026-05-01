package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.repository.AuditRepository;
import com.choisk.sfs.samples.order.repository.OrderRepository;
import com.choisk.sfs.samples.order.service.BusinessException;
import com.choisk.sfs.samples.order.service.OrderService;
import com.choisk.sfs.samples.todo.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionPropagationIntegrationTest {

    private AnnotationConfigApplicationContext ctx;
    private OrderService orderService;
    private OrderRepository orderRepo;
    private AuditRepository auditRepo;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        orderService = ctx.getBean(OrderService.class);
        orderRepo = ctx.getBean(OrderRepository.class);
        auditRepo = ctx.getBean(AuditRepository.class);

        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM audit_log");
        }
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Test
    void successfulOrderInsertsBothOrderAndAudit() {
        orderService.placeOrder("Book", 5_000);

        assertThat(orderRepo.count()).isEqualTo(1L);
        assertThat(auditRepo.count()).isEqualTo(1L);
    }

    @Test
    void failingOrderRollsBackOrderButAuditSurvives() {
        assertThatThrownBy(() -> orderService.placeOrder("Yacht", 200_000))
                .isInstanceOf(BusinessException.class);

        // outer rollback: orders 0행
        assertThat(orderRepo.count()).isEqualTo(0L);
        // inner REQUIRES_NEW commit: audit_log 1행 (정점)
        assertThat(auditRepo.count()).isEqualTo(1L);
    }

    @Test
    void multipleOrdersAccumulate() {
        orderService.placeOrder("Book", 5_000);
        orderService.placeOrder("Pen", 1_000);

        assertThat(orderRepo.count()).isEqualTo(2L);
        assertThat(auditRepo.count()).isEqualTo(2L);
    }

    @Test
    void mixedSuccessAndFailure() {
        orderService.placeOrder("Book", 5_000);
        try { orderService.placeOrder("Yacht", 200_000); } catch (BusinessException ignored) {}
        orderService.placeOrder("Pen", 1_000);

        assertThat(orderRepo.count()).isEqualTo(2L);  // Book, Pen만
        assertThat(auditRepo.count()).isEqualTo(3L);  // 3건 모두 audit
    }

    @Test
    void auditMessageContainsItemName() {
        orderService.placeOrder("Book", 5_000);

        assertThat(auditRepo.findAll().get(0).message()).contains("Book");
    }

    @Test
    void rolledBackOrderIsNotPersistedEvenAcrossNewConnection() throws Exception {
        try { orderService.placeOrder("Yacht", 200_000); } catch (BusinessException ignored) {}

        // 새 connection으로 직접 조회 — TM이 정말 rollback했는지 검증
        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }
}
