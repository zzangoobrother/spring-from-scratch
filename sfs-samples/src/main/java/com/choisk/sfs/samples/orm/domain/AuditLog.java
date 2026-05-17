package com.choisk.sfs.samples.orm.domain;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;

import java.time.LocalDateTime;

/**
 * ORM demo 감사 로그 엔티티 — F1.5 EAGER fetch 시연 박제.
 * order 필드는 EAGER fetch — AuditLog 조회 시 JOIN 없이 즉시 Order를 함께 로드.
 */
@SfsEntity(name = "audit_log")
public class AuditLog {

    @SfsId
    @SfsGeneratedValue(strategy = SfsGeneratedValue.GenerationType.IDENTITY)
    private Long id;

    @SfsManyToOne(fetch = SfsManyToOne.FetchType.EAGER)
    @SfsJoinColumn(name = "order_id")
    private Order order;

    @SfsColumn
    private String action;

    @SfsColumn
    private String message;

    @SfsColumn(name = "created_at")
    private LocalDateTime createdAt;

    /** ORM reflection 접근용 기본 생성자 */
    public AuditLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
