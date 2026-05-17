package com.choisk.sfs.samples.orm.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.samples.orm.domain.Order;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.tx.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 서비스 — Phase 4 ORM 학습 정점 6개 시연.
 *
 * DA: IDENTITY 즉시 INSERT (SEQUENCE 정상과 대비)
 * DB: 더티 체킹 — status 변경만으로 UPDATE 자동 발견
 * DC: LAZY proxy 첫 호출 시 SELECT (tx 내부)
 * DD: 1 entity = 1 instance (== 보장)
 * DE/DF용: findOrderDetached — tx 종료 후 detached entity 반환
 * DF 정상: updateOrder — merge 반환값 사용으로 변경 반영
 * DF 함정: brokenUpdate — merge 반환 무시 → 변경 사라짐
 */
@Service
public class OrderService {

    @Autowired
    private SfsEntityManager em;

    /**
     * 주문 생성 — IDENTITY 전략: persist 즉시 INSERT, write-behind 불가.
     * SEQUENCE 전략(User)과 달리 INSERT가 즉시 실행됨을 보여주는 학습 정점 ① 대비 시나리오.
     */
    @Transactional
    public Long placeOrder(Long userId, BigDecimal amount) {
        // user_id FK 만족을 위해 User entity 로드
        User user = em.find(User.class, userId);
        Order order = new Order();
        order.setUser(user);
        order.setAmount(amount);
        order.setStatus("PENDING");
        order.setCreatedAt(LocalDateTime.now());
        em.persist(order);
        return order.getId();
    }

    /**
     * 주문 결제 처리 — 학습 정점 DB: 더티 체킹.
     * find 후 status만 변경하면, commit 시 스냅샷 비교로 UPDATE 자동 발견.
     * 명시적 persist/merge 호출 없음.
     */
    @Transactional
    public void payOrder(Long orderId) {
        Order order = em.find(Order.class, orderId);
        order.setStatus("PAID");
        // 메서드 종료 → commit → flush → dirty check → UPDATE 자동 발견
    }

    /**
     * 주문 설명 반환 — 학습 정점 DC: LAZY proxy 첫 호출.
     * tx 내부에서 order.getUser().getName() 호출 → LAZY proxy가 SELECT 실행.
     * tx 내부이므로 정상 동작 (DE 시나리오와 대비).
     */
    @Transactional
    public String describeOrder(Long orderId) {
        Order order = em.find(Order.class, orderId);
        // tx 내부에서 LAZY proxy 접근 → SELECT 발생하지만 정상
        return "Order #" + order.getId() + " by " + order.getUser().getName();
    }

    /**
     * 1 entity = 1 instance 검증 — 학습 정점 DD.
     * 같은 PC 내에서 동일 PK로 User를 두 번 find하면 동일 인스턴스 보장.
     * u1 == u2 가 true여야 함 (identityMap 캐시 hit).
     * 주의: order.getUser()는 byte-buddy LAZY proxy(subclass)이므로 == 비교 금지 — proxy != 원본.
     */
    @Transactional
    public boolean verifyIdentity(Long orderId, Long userId) {
        // 동일 PK로 두 번 find → identityMap 캐시 hit → 동일 인스턴스
        User u1 = em.find(User.class, userId);
        User u2 = em.find(User.class, userId);
        return u1 == u2;
    }

    /**
     * detached entity 반환 — DE/DF 시나리오용.
     * tx 내부에서 find 후 tx 종료 → PC close → caller에게 detached entity 반환.
     * 이 entity의 LAZY 필드(user)에 tx 밖에서 접근하면 SfsLazyInitializationException.
     */
    @Transactional
    public Order findOrderDetached(Long orderId) {
        return em.find(Order.class, orderId);
    }

    /**
     * merge 정상 사용 — 학습 정점 DF 정상.
     * merge() 반환값(managed entity)을 사용해야 변경이 반영됨.
     */
    @Transactional
    public Order updateOrder(Order detached) {
        // merge 반환값(managed entity)을 받아서 반환 — 변경 반영됨
        return em.merge(detached);
    }

    /**
     * merge 함정 — 학습 정점 DF 함정.
     * merge() 반환값을 무시하면 detached entity 변경이 DB에 반영되지 않음.
     */
    @Transactional
    public void brokenUpdate(Order detached) {
        em.merge(detached); // 반환값 무시 → detached는 여전히 detached → 변경 사라짐
    }
}
