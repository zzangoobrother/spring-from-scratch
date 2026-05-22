package com.choisk.sfs.samples.orm.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.samples.orm.domain.Order;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.tx.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 서비스 — 학습 정점 ① SEQUENCE 전략 시연 + MP-2 @SfsOneToMany 시연.
 * persist 시점에 SEQUENCE로 id를 미리 채번하고, 실제 INSERT는 commit(flush) 시점에 발생.
 */
@Service
public class UserService {

    @Autowired
    private SfsEntityManager em;

    /**
     * 사용자 생성 — SEQUENCE 전략으로 persist 시 id 즉시 채번.
     * INSERT는 트랜잭션 commit 시 write-behind 큐에서 실행.
     */
    @Transactional
    public User createUser(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        em.persist(user);
        // SEQUENCE 전략: persist 시점에 id 할당됨, INSERT는 commit 시점 (write-behind)
        return user;
    }

    /**
     * DH: findAll(User) + for-loop u.getOrders().size() → N+1 자연 노출.
     * 학습 정점 ②: User N명 조회(1 SELECT) + 각 user의 orders 로딩(N SELECT) = N+1 SELECT.
     * M2 NPlusOneIntegrationTest에서 spy로 회귀 검증됨.
     */
    @Transactional
    public void dumpAllUserOrders() {
        List<User> users = em.findAll(User.class);
        System.out.println("[DH] findAll(User) → " + users.size() + "명 조회 (1 SELECT)");
        for (User u : users) {
            // 각 lazy collection 첫 접근 = 1 SELECT (N번 반복 → N SELECT 추가 발생)
            int orderCount = u.getOrders().size();
            System.out.println("  user=" + u.getName() + ", orders=" + orderCount);
        }
        System.out.println("[DH] 총 N+1 = " + (users.size() + 1) + " SELECT 발생 (spy로 회귀 검증됨)");
    }

    /**
     * DI: getOrders().isEmpty() 첫 호출 시점에 정확히 1 SELECT 발생.
     * 학습 정점 ①: lazy collection은 실제 접근 전까지 SELECT를 지연함.
     * getOrders() getter 자체가 아닌, isEmpty() 같은 List 메서드 호출 시점에 lazy init이 발화된다.
     */
    @Transactional
    public void describeUserOrders(Long userId) {
        User user = em.find(User.class, userId);
        // getOrders() getter 호출 자체는 SELECT를 발화하지 않음 — SfsPersistentList stub 반환만
        System.out.println("[DI] getOrders() 첫 메서드 접근 직전 — 아직 SELECT 없음");
        if (!user.getOrders().isEmpty()) {
            // isEmpty()가 첫 접근 → 여기서 정확히 1 SELECT 발화
            Order first = user.getOrders().iterator().next();
            System.out.println("[DI] isEmpty() 호출 시점에 1 SELECT 발생 — firstOrderId=" + first.getId());
        } else {
            System.out.println("[DI] orders 없음 (사전 데이터 확인 필요)");
        }
    }

    /**
     * DJ: 이미 managed인 user의 컬렉션에 add + flush → newOrder는 INSERT되지 않음.
     * 학습 짝패: 단방향 @SfsOneToMany + cascade 미도입 자연 노출.
     *
     * <p>WHY persist(user) 제거: user는 find()로 로드된 managed 엔티티로, persist()를 재호출하면
     * SEQUENCE 전략이 새 시퀀스 id를 채번·덮어쓰고 중복 INSERT를 큐에 넣는다(managed 가드 부재).
     * add() + flush()만으로도 cascade 미도입 → newOrder INSERT 안 됨을 동일하게 시연한다.
     *
     * <p>해결 방법: em.persist(newOrder) 별도 호출 or MP-3에서 cascade=PERSIST 도입 예정.
     */
    @Transactional
    public void tryAddOrderWithoutCascade(Long userId) {
        User user = em.find(User.class, userId);
        Order newOrder = new Order();
        newOrder.setAmount(new BigDecimal("999.99"));
        newOrder.setStatus("ATTEMPT");
        newOrder.setCreatedAt(LocalDateTime.now());
        newOrder.setUser(user);

        // 이미 managed인 user의 컬렉션에 add → 내부적으로 lazy init 발화 후 메모리 목록에만 추가
        // cascade 미도입이라 newOrder는 managed 상태가 되지 않음 — flush에서 INSERT 발생 안 함
        user.getOrders().add(newOrder);

        em.flush();
        System.out.println("[DJ] add() + flush() → newOrder는 INSERT 안 됨 (단방향 + cascade 미도입)");
        System.out.println("[DJ] 해결: em.persist(newOrder) 별도 호출 필요 — MP-3에서 cascade=PERSIST로 자동화");
    }
}
