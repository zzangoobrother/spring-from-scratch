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

    /**
     * DK — cascade PERSIST 회수: addOrder + persist(user)로 order 2건 자동 INSERT.
     * MP-3 학습 정점: 수동 persist(order) 없이 cascade가 자동 INSERT 발화.
     *
     * <p>id 전략 비대칭(SEQUENCE user / IDENTITY order) + FK 제약 때문에
     * 부모(user)를 먼저 flush로 DB에 반영한 뒤 자식(order) cascade INSERT를 실행해야 한다.
     * 이 flush는 cascade PERSIST 전의 선행 조건이며 별도의 ORM 함정(비대칭 전략)을 시연한다.
     *
     * @return 생성된 dk-user id (DN/DM 시나리오에서 재사용)
     */
    @Transactional
    public Long cascadePersistDemo() {
        User u = new User();
        u.setName("dk-user");
        u.setEmail("dk@example.com");
        em.persist(u);
        // SEQUENCE user를 먼저 DB에 반영 — IDENTITY 자식 cascade INSERT의 FK 선행 조건
        // (id 전략 비대칭 함정: SEQUENCE는 write-behind 큐에 대기, IDENTITY는 즉시 INSERT)
        em.flush();

        Order o1 = new Order();
        o1.setAmount(new BigDecimal("10.00"));
        o1.setStatus("NEW");
        o1.setCreatedAt(LocalDateTime.now());

        Order o2 = new Order();
        o2.setAmount(new BigDecimal("20.00"));
        o2.setStatus("NEW");
        o2.setCreatedAt(LocalDateTime.now());

        // 양방향 일관성 helper: inverse(orders 추가) + owning(order.user 세팅) 동시 처리
        u.addOrder(o1);
        u.addOrder(o2);
        // managed 재persist → cascade PERSIST → o1/o2 자동 INSERT (수동 persist(order) 불필요)
        em.persist(u);
        em.flush();

        System.out.println("[DK] addOrder + persist(user) → order 2건 자동 INSERT (cascade PERSIST — MP-2 부채 회수)");
        System.out.println("  INFO id 전략 비대칭(SEQUENCE user / IDENTITY order) + FK → 부모를 먼저 flush해야 자식 cascade INSERT 가능");
        return u.getId();
    }

    /**
     * DN — orphanRemoval: 컬렉션에서 제거한 order만 flush 시 DELETE.
     * MP-3 학습 정점: removeOrder → snapshot diff → orphan으로 분류 → flush 시 DELETE.
     *
     * @param userId DK가 반환한 dk-user id
     */
    @Transactional
    public void orphanRemovalDemo(Long userId) {
        User u = em.find(User.class, userId);
        if (!u.getOrders().isEmpty()) {
            // 첫 접근 시 lazy init(SELECT) 발화 → storedSnapshot 캡처
            Order first = u.getOrders().get(0);
            // removeOrder: 컬렉션에서 제거(inverse) + order.user=null(owning)
            u.removeOrder(first);
        }
        // flush 시 snapshot diff → first만 orphan → DELETE (나머지 order는 유지)
        em.flush();
        System.out.println("[DN] removeOrder → 컬렉션에서 빠진 order만 orphanRemoval DELETE");
    }

    /**
     * DM — cascade REMOVE: remove(user)로 자식 order까지 삭제(FK 순서: 자식 먼저).
     * MP-3 학습 정점: remove(user) 1회로 자식 order가 먼저 DELETE된 후 user DELETE.
     *
     * @param userId DN이 처리한 dk-user id (남은 order 1건 + user 삭제)
     */
    @Transactional
    public void cascadeRemoveDemo(Long userId) {
        User u = em.find(User.class, userId);
        em.remove(u);
        // FK 순서 보장: 자식 order DELETE → user DELETE
        em.flush();
        System.out.println("[DM] remove(user) → 자식 order까지 cascade DELETE (자식 먼저)");
    }
}
