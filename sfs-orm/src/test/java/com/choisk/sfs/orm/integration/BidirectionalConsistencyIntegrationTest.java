package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ① — 양방향 일관성은 application 책임.
 * inverse(컬렉션)만 건드리면 cascade가 INSERT해도 owning side(user) 미설정 → FK null.
 * helper(addOrder)로 양쪽 세팅하면 FK 정상.
 */
class BidirectionalConsistencyIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void inverse만_추가하면_cascade_INSERT되어도_FK가_null() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            em.persist(u);            // user INSERT 등록(SEQUENCE)

            CasOrder o = new CasOrder();
            o.item = "book";
            u.getOrders().add(o);     // ★ helper 미사용 — owning(o.user) 미설정
            em.persist(u);            // managed 재persist → cascade → o INSERT 등록

            em.flush();
            return null;
        });

        // o는 INSERT됐지만 user_id가 NULL (함정)
        Long nullFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cas_orders WHERE user_id IS NULL", Long.class);
        assertThat(nullFk).isEqualTo(1L);
    }

    @Test
    void helper로_양쪽_세팅하면_FK_정상() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            em.persist(u);

            CasOrder o = new CasOrder();
            o.item = "book";
            u.addOrder(o);            // ★ helper — orders.add + o.user=this
            em.persist(u);

            em.flush();
            return null;
        });

        Long correctFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cas_orders WHERE user_id = 1", Long.class);
        assertThat(correctFk).isEqualTo(1L);
    }
}
