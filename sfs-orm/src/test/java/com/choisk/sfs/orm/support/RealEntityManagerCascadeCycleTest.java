package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cascade REMOVE visited 사이클 가드 검증.
 *
 * <p>양방향 cascade=ALL 사이클 fixture (CycA ←→ CycB):
 * a.bs=[b], b.as=[a], a.ownerB=b, b.ownerA=a
 * 가드 없는 remove(a) → a→b→a→... StackOverflowError 재현.
 * 가드 추가 후: 각 엔티티 정확히 DeleteAction 1건씩, 총 2건.
 */
class RealEntityManagerCascadeCycleTest {

    // CycA: @SfsManyToOne → CycB,  @SfsOneToMany(cascade=ALL) → List<CycB>
    @SfsEntity(name = "cyc_a")
    public static class CycA {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cyc_a_seq")
        public Long id;

        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY)
        @SfsJoinColumn(name = "b_id")
        public CycB ownerB;

        @SfsOneToMany(mappedBy = "ownerA", cascade = {SfsCascadeType.ALL})
        public List<CycB> bs = new ArrayList<>();
    }

    // CycB: @SfsManyToOne → CycA,  @SfsOneToMany(cascade=ALL) → List<CycA>
    @SfsEntity(name = "cyc_b")
    public static class CycB {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cyc_b_seq")
        public Long id;

        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY)
        @SfsJoinColumn(name = "a_id")
        public CycA ownerA;

        @SfsOneToMany(mappedBy = "ownerB", cascade = {SfsCascadeType.ALL})
        public List<CycA> as = new ArrayList<>();
    }

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cycletest-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);

        jdbc.update("CREATE SEQUENCE cyc_a_seq START WITH 1");
        jdbc.update("CREATE SEQUENCE cyc_b_seq START WITH 1");
        // FK 없이 단순 테이블 — remove는 DB flush 없이 actionQueue 레벨에서 검증
        jdbc.update("CREATE TABLE cyc_a (id BIGINT PRIMARY KEY, b_id BIGINT)");
        jdbc.update("CREATE TABLE cyc_b (id BIGINT PRIMARY KEY, a_id BIGINT)");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(CycA.class)
                .addEntityClass(CycB.class)
                .build();
    }

    /**
     * 양방향 cascade=ALL 사이클에서 remove(a) 호출 시:
     * - 가드 없음: remove(a)→remove(b)→remove(a)→... StackOverflowError
     * - 가드 있음: DeleteAction 정확히 2건 (a 1건, b 1건), 중복 없음
     */
    @Test
    void remove_양방향_cascade_ALL_사이클_StackOverflow_없이_DeleteAction_2건() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();

        // given: a, b 생성 후 양방향 참조 설정
        CycA a = new CycA();
        CycB b = new CycB();
        // 양방향 일관성 — application 책임
        a.ownerB = b;
        a.bs.add(b);        // a의 컬렉션에 b 포함 → remove(a) 시 cascade로 remove(b) 시도
        b.ownerA = a;
        b.as.add(a);        // b의 컬렉션에 a 포함 → remove(b) 시 cascade로 remove(a) 재시도 (사이클)

        // persist로 양쪽 managed 등록 (doPersist visited 가드가 PERSIST 사이클도 차단)
        em.persist(a);
        em.context().clearActionQueue();   // InsertAction 제거 — remove 검증에 집중

        // when: remove(a) — 가드 없으면 a→b→a→... StackOverflowError
        em.remove(a);

        // then: DeleteAction 정확히 2건 (a, b 각 1건)
        List<EntityAction> q = em.context().actionQueue();
        assertThat(q).hasSize(2)
                .as("양방향 사이클에서 DeleteAction이 중복 없이 정확히 2건이어야 한다");
        long deleteCount = q.stream().filter(action -> action instanceof DeleteAction).count();
        assertThat(deleteCount).isEqualTo(2)
                .as("모든 액션이 DeleteAction이어야 한다");

        // 각 엔티티 1건씩만 enqueue (중복 검증)
        long aDeleteCount = q.stream()
                .filter(action -> action instanceof DeleteAction da && da.entity() == a)
                .count();
        long bDeleteCount = q.stream()
                .filter(action -> action instanceof DeleteAction da && da.entity() == b)
                .count();
        assertThat(aDeleteCount).isEqualTo(1).as("CycA DeleteAction은 정확히 1건");
        assertThat(bDeleteCount).isEqualTo(1).as("CycB DeleteAction은 정확히 1건");
    }
}
