package com.choisk.sfs.samples.orm.domain;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;

import java.util.List;

/**
 * ORM demo 사용자 엔티티 — 학습 정점 ① SEQUENCE 정상 박제.
 * users_seq SEQUENCE로 INSERT 전 id를 미리 채번하므로 write-behind 큐에 올바르게 적재됨.
 */
@SfsEntity(name = "users")
public class User {

    @SfsId
    @SfsGeneratedValue(strategy = SfsGeneratedValue.GenerationType.SEQUENCE, sequenceName = "users_seq")
    private Long id;

    @SfsColumn
    private String name;

    @SfsColumn
    private String email;

    @SfsOneToMany(joinColumn = "user_id")   // default LAZY
    private List<Order> orders;

    /** ORM reflection 접근용 기본 생성자 */
    public User() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public List<Order> getOrders() { return orders; }
    public void setOrders(List<Order> orders) { this.orders = orders; }
}
