package com.choisk.sfs.samples.orm.domain;

import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;

import java.util.ArrayList;
import java.util.List;

/**
 * ORM demo 사용자 엔티티 — 학습 정점 ① SEQUENCE 정상 박제.
 * users_seq SEQUENCE로 INSERT 전 id를 미리 채번하므로 write-behind 큐에 올바르게 적재됨.
 *
 * <p>MP-3 양방향 마이그레이션: orders 필드가 mappedBy="user"(양방향) + cascade={PERSIST,REMOVE} +
 * orphanRemoval=true로 변경됨. FK 소유권은 Order.user(@SfsManyToOne)이 가짐.
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

    // MP-3: 단방향(joinColumn) → 양방향(mappedBy) 마이그레이션.
    // cascade={PERSIST,REMOVE}: persist(user)/remove(user) 시 order 자동 전파.
    // orphanRemoval=true: 컬렉션에서 빠진 order는 flush 시 DELETE.
    @SfsOneToMany(mappedBy = "user",
            cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();

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

    /**
     * 양방향 일관성 helper — 양쪽 동시 세팅(application 책임 박제).
     * inverse side(orders)와 owning side(order.user)를 모두 갱신해야
     * FK null 함정(학습 정점 ①)을 피할 수 있음.
     */
    public void addOrder(Order o) { orders.add(o); o.setUser(this); }

    /**
     * 양방향 일관성 helper — 컬렉션 제거 + owning side null 세팅.
     * orphanRemoval과 짝을 이뤄 flush 시 DELETE 발화.
     */
    public void removeOrder(Order o) { orders.remove(o); o.setUser(null); }
}
