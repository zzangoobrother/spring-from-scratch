package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ② — cascade PERSIST 전파. persist(user) 1회로 user + order들이 모두 INSERT.
 * MP-2 부채(add()+persist(user)만으론 order INSERT 안 됨)의 회수 박제.
 */
class CascadePersistIntegrationTest extends AbstractBidirectionalCascadeTest {

    private SqlCountingJdbcTemplate spy;

    @Override
    protected com.choisk.sfs.tx.jdbc.JdbcTemplate jdbcTemplateOverride() {
        if (spy == null) spy = new SqlCountingJdbcTemplate(ds, tsm);
        return spy;
    }

    @Test
    void persist_user_1회로_user와_order_2건이_cascade_INSERT() {
        TransactionTemplate.execute(tm, () -> {
            spy.reset();

            CasUser u = new CasUser();
            u.name = "alice";
            CasOrder o1 = new CasOrder(); o1.item = "book";
            CasOrder o2 = new CasOrder(); o2.item = "pen";
            u.addOrder(o1);
            u.addOrder(o2);

            em.persist(u);   // cascade PERSIST: user + o1 + o2 InsertAction 등록
            em.flush();      // 3건 INSERT 실행

            // INSERT INTO cas_users 1 + INSERT INTO cas_orders 2 = 3
            assertThat(spy.countMatching("INSERT INTO cas_users")).isEqualTo(1);
            assertThat(spy.countMatching("INSERT INTO cas_orders")).isEqualTo(2);
            return null;
        });
    }
}
