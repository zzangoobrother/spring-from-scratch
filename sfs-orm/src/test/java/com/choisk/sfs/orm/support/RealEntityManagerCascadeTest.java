package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
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
 * RealEntityManager cascade PERSIST — persist(parent)가 cascade 컬렉션을 그래프 순회하며
 * 자식까지 InsertAction에 등록하는지 검증. managed 재persist도 cascade 발화(MP-2 부채 회수).
 */
class RealEntityManagerCascadeTest {

    @SfsEntity(name = "cp_parent")
    static class CpParent {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.PERSIST})
        List<CpChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "cp_parent_nocascade")
    static class CpParentNoCascade {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent2")   // cascade 없음
        List<CpChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "cp_child")
    static class CpChild {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_child_seq")
        Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        CpParent parent;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent2_id")
        CpParentNoCascade parent2;
        @SfsColumn String label;
    }

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cascadepersist-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("CREATE SEQUENCE cp_parent_seq START WITH 1");
        jdbc.update("CREATE SEQUENCE cp_child_seq START WITH 1");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(CpParent.class)
                .addEntityClass(CpParentNoCascade.class)
                .addEntityClass(CpChild.class)
                .build();
    }

    @Test
    void persist_parent_cascade_PERSIST_시_자식도_InsertAction_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParent p = new CpParent();
        p.name = "p1";
        CpChild c = new CpChild();
        c.label = "c1";
        c.parent = p;
        p.children.add(c);

        em.persist(p);

        // parent + child = InsertAction 2건 (SEQUENCE write-behind)
        assertThat(em.context().actionQueue()).hasSize(2);
        assertThat(em.context().actionQueue()).allMatch(a -> a instanceof InsertAction);
    }

    @Test
    void cascade_없으면_parent만_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParentNoCascade p = new CpParentNoCascade();
        p.name = "p1";
        CpChild c = new CpChild();
        c.label = "c1";
        c.parent2 = p;
        p.children.add(c);

        em.persist(p);

        assertThat(em.context().actionQueue()).hasSize(1);   // parent만
    }

    @Test
    void managed_parent_재persist_시_새_자식만_cascade_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParent p = new CpParent();
        p.name = "p1";
        CpChild c1 = new CpChild();
        c1.label = "c1";
        c1.parent = p;
        p.children.add(c1);
        em.persist(p);   // queue: [Insert(p), Insert(c1)]

        // managed 상태에서 새 자식 추가 후 재persist
        CpChild c2 = new CpChild();
        c2.label = "c2";
        c2.parent = p;
        p.children.add(c2);
        em.persist(p);   // p, c1 managed → skip self-insert / c2만 신규

        // queue: [Insert(p), Insert(c1), Insert(c2)] — p/c1 중복 등록 없음
        assertThat(em.context().actionQueue()).hasSize(3);
        long parentInserts = em.context().actionQueue().stream()
                .filter(a -> a instanceof InsertAction ia && ia.entity() == p).count();
        assertThat(parentInserts).isEqualTo(1);
    }
}
