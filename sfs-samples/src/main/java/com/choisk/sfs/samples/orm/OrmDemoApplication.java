package com.choisk.sfs.samples.orm;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.orm.exception.SfsTransactionRequiredException;
import com.choisk.sfs.samples.orm.config.OrmConfig;
import com.choisk.sfs.samples.orm.domain.Order;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.samples.orm.service.OrderService;
import com.choisk.sfs.samples.orm.service.UserService;

import java.math.BigDecimal;

/**
 * Phase 4 + MP-2 ORM 학습 정점 시연 애플리케이션.
 *
 * DA: SEQUENCE 정상 vs IDENTITY 즉시 INSERT
 * DB: 더티 체킹 — status 변경만으로 UPDATE 자동 발견
 * DC: LAZY proxy — tx 내부 첫 접근 시 SELECT
 * DD: 1 entity = 1 instance (proxy == 원본 보장)
 * DE: LazyInitializationException — tx 종료 후 lazy 접근
 * DF: merge return value 함정
 * DG: @Transactional 누락 — SfsTransactionRequiredException
 * DH: findAll(User) + for-loop getOrders().size() → N+1 자연 노출 (MP-2 학습 정점 ②)
 * DI: user.getOrders().iterator() 첫 호출 시점에 1 SELECT 발화 (MP-2 학습 정점 ①)
 * DJ: add(newOrder) + persist(user)만 → newOrder INSERT 안 됨 (cascade 부재 자연 노출, MP-3 회수)
 */
public class OrmDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(OrmConfig.class)) {
            UserService userSvc = ctx.getBean(UserService.class);
            OrderService orderSvc = ctx.getBean(OrderService.class);
            SfsEntityManager em = ctx.getBean(SfsEntityManager.class);

            System.out.println("=== Phase 4 ORM Demo ===\n");

            // [DA] SEQUENCE 정상 vs IDENTITY 즉시 INSERT
            System.out.println("[DA] Creating user (SEQUENCE) and placing order (IDENTITY)");
            User alice = userSvc.createUser("Alice", "alice@example.com");
            Long orderId = orderSvc.placeOrder(alice.getId(), new BigDecimal("99.99"));
            System.out.printf("  OK Created user id=%d, order id=%d%n%n", alice.getId(), orderId);

            // [DB] 더티 체킹
            System.out.println("[DB] payOrder -- dirty check UPDATE");
            orderSvc.payOrder(orderId);
            System.out.println("  OK status PAID -- flush 시 snapshot 비교로 UPDATE 자동 발견\n");

            // [DC] LAZY proxy 첫 호출
            System.out.println("[DC] describeOrder -- lazy proxy 시연");
            String desc = orderSvc.describeOrder(orderId);
            System.out.printf("  OK Description = \"%s\"%n%n", desc);

            // [DD] 동일 PK 두 번 find → 동일 인스턴스 (identityMap 캐시 hit)
            System.out.println("[DD] verifyIdentity -- 1 entity = 1 instance");
            boolean same = orderSvc.verifyIdentity(orderId, alice.getId());
            System.out.printf("  OK Identity guaranteed: %s%n  INFO 학습 정점 2: 같은 PC 내 동일 PK find → identityMap hit → 동일 인스턴스%n%n", same);

            // [DE] LazyInitializationException -- tx 종료 후 lazy 접근
            System.out.println("[DE] LazyInitializationException 박제 -- tx 밖 lazy 접근");
            Order detached = orderSvc.findOrderDetached(orderId);
            try {
                detached.getUser().getName();   // PC 닫힘 → SfsLazyInitializationException
                System.out.println("  FAIL 예상치 못한 통과 (회귀)");
            } catch (SfsLazyInitializationException e) {
                System.out.println("  OK Caught: " + e.getClass().getSimpleName());
                System.out.println("  INFO 영속성 컨텍스트 close 후 lazy 접근 -- @Transactional 경계 박제\n");
            }

            // [DF] merge return value 함정
            System.out.println("[DF] merge return value 함정");
            Order toUpdate = orderSvc.findOrderDetached(orderId);
            toUpdate.setStatus("REFUNDED");
            Order merged = orderSvc.updateOrder(toUpdate);
            System.out.println("  OK updateOrder(detached) -- return 사용 → status=" + merged.getStatus());

            Order toBreak = orderSvc.findOrderDetached(orderId);
            toBreak.setStatus("BROKEN");
            orderSvc.brokenUpdate(toBreak);    // return 무시
            Order recheck = orderSvc.findOrderDetached(orderId);
            System.out.println("  FAIL brokenUpdate(detached) -- return 무시 → status=" + recheck.getStatus() + " (변경 사라짐)");
            System.out.println("  INFO T merge(T) 시그니처가 return value 사용 강제하는 이유\n");

            // [DG] @Transactional 누락
            System.out.println("[DG] @Transactional 누락");
            try {
                User ghost = new User();
                ghost.setName("Ghost");
                ghost.setEmail("ghost@example.com");
                em.persist(ghost);   // tx 없이 직접 호출 → SfsTransactionRequiredException
                System.out.println("  FAIL 예상치 못한 통과 (회귀)");
            } catch (SfsTransactionRequiredException e) {
                System.out.println("  OK Caught: " + e.getClass().getSimpleName());
                System.out.println("  INFO EM은 트랜잭션 바운드 -- TSM이 resource lookup 실패\n");
            }

            // ─── MP-2 @SfsOneToMany 시연 ───────────────────────────────────────
            System.out.println("\n=== MP-2: @SfsOneToMany 시연 ===\n");

            // 사전 데이터 준비: User 2명 추가 (alice는 DA에서 생성됨) + 각 주문 1건씩 추가
            // alice에게 주문 2건 (orderId는 DA에서 이미 1건 생성됨)
            orderSvc.placeOrder(alice.getId(), new BigDecimal("50.00"));  // alice 주문 2번째

            User bob = userSvc.createUser("Bob", "bob@example.com");
            orderSvc.placeOrder(bob.getId(), new BigDecimal("150.00"));

            User carol = userSvc.createUser("Carol", "carol@example.com");
            orderSvc.placeOrder(carol.getId(), new BigDecimal("250.00"));

            // [DH] findAll + for-loop getOrders().size() → N+1 자연 노출
            System.out.println("[DH] N+1 문제 자연 노출 — findAll(User) + for-loop lazy init");
            userSvc.dumpAllUserOrders();
            System.out.println();

            // [DI] lazy collection 발화 시점 박제 — iterator() 첫 호출 시 1 SELECT
            System.out.println("[DI] lazy collection 발화 시점 — alice 주문 조회");
            userSvc.describeUserOrders(alice.getId());
            System.out.println();

            // [DJ] cascade 부재 자연 노출 — add(newOrder) + persist(user)만으로는 INSERT 미발생
            System.out.println("[DJ] cascade 부재 자연 노출 — bob에게 신규 주문 add 시도");
            userSvc.tryAddOrderWithoutCascade(bob.getId());
            System.out.println();

            System.out.println("=== Demo 완료 ===");
        }
    }
}
