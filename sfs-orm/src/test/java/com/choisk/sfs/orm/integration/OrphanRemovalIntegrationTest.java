package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ③ — orphanRemoval. 컬렉션에서 빠진 element가 flush 시 DELETE.
 * cascade(호출 전파)와 다른 트리거: 컬렉션 변화 감지(snapshot diff).
 */
class OrphanRemovalIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void 컬렉션에서_removeOrder한_order만_flush_시_DELETE() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);

        // given: user 1 + order 2
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            CasOrder o1 = new CasOrder(); o1.item = "book";
            CasOrder o2 = new CasOrder(); o2.item = "pen";
            u.addOrder(o1);
            u.addOrder(o2);
            em.persist(u);
            em.flush();
            return null;
        });

        // when: 컬렉션에서 한 건 제거 후 flush
        TransactionTemplate.execute(tm, () -> {
            CasUser u = em.find(CasUser.class, 1L);
            CasOrder first = u.getOrders().get(0);   // 초기화 → storedSnapshot=[o1,o2]
            u.removeOrder(first);                     // delegate=[o2], owning(first.user)=null
            em.flush();                               // orphan(first) DELETE
            return null;
        });

        // then: order 1건만 남음
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_orders", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_users", Long.class)).isEqualTo(1L);
    }
}
