package com.choisk.sfs.samples.orm.domain;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ORM demo 주문 엔티티 — 학습 정점 ① IDENTITY 깨짐 함정 + 정점 ② LAZY proxy 박제.
 * IDENTITY 전략은 INSERT 실행 후에야 DB가 id를 반환하므로 write-behind 큐에 올리지 못하고 즉시 flush.
 * user 필드는 LAZY fetch — 첫 접근 시점에 SELECT가 발생함을 시연.
 */
@SfsEntity(name = "orders")
public class Order {

    @SfsId
    @SfsGeneratedValue(strategy = SfsGeneratedValue.GenerationType.IDENTITY)
    private Long id;

    @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY)
    @SfsJoinColumn(name = "user_id")
    private User user;

    @SfsColumn
    private BigDecimal amount;

    @SfsColumn
    private String status;

    @SfsColumn(name = "created_at")
    private LocalDateTime createdAt;

    /** ORM reflection 접근용 기본 생성자 */
    public Order() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
