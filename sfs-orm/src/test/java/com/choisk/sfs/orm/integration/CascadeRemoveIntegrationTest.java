package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ② — cascade REMOVE 전파 + 삭제 순서. remove(user) 1회로 order까지 DELETE.
 * cas_orders.user_id FK 제약이 있으므로 자식(order)이 부모(user)보다 먼저 DELETE되어야 위반 없이 통과.
 */
class CascadeRemoveIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void remove_user_1회로_order까지_cascade_DELETE_FK위반_없음() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);

        // given: user 1 + order 2 영속화
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

        // when: 새 트랜잭션에서 user 조회 후 remove (cascade REMOVE)
        TransactionTemplate.execute(tm, () -> {
            CasUser u = em.find(CasUser.class, 1L);
            em.remove(u);     // 자식 order들 DeleteAction 먼저, user 마지막
            em.flush();       // FK 순서 — order DELETE → user DELETE
            return null;
        });

        // then: 양쪽 테이블 모두 비어야 함 (FK 위반 없이)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_users", Long.class)).isZero();
    }
}
