package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType.LAZY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultCollectionLoader 단위 테스트.
 *
 * <p>WHY: emf.close()가 SfsEntityManagerFactory에 없으므로 (기존 통합 테스트 패턴 참조),
 * nanoTime URL로 테스트마다 완전히 독립된 H2 in-memory DB를 생성해 격리한다.
 */
class DefaultCollectionLoaderTest {

    private DataSource dataSource;
    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // nanoTime으로 테스트마다 독립된 DB 생성 — emf.close() 부재를 DB URL 격리로 보완
        ds.setURL("jdbc:h2:mem:dcl-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        TransactionSynchronizationManager tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource, tsm);

        jdbc.update("CREATE TABLE parents (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        jdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "parent_id BIGINT, label VARCHAR(50))");
        jdbc.update("INSERT INTO parents (name) VALUES ('p1')");
        jdbc.update("INSERT INTO children (parent_id, label) VALUES (1, 'c1')");
        jdbc.update("INSERT INTO children (parent_id, label) VALUES (1, 'c2')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(dataSource)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(ParentEntity.class)
                .addEntityClass(ChildEntity.class)
                .build();
    }

    @Test
    void loadCollection_SELECT_WHERE_fk_실행_후_element들이_identityMap_등재() {
        // given: DefaultCollectionLoader + 빈 PersistenceContext
        CollectionLoader loader = new DefaultCollectionLoader(emf);
        PersistenceContext ctx = new PersistenceContext();

        // when: parent_id = 1인 children 로드
        List<ChildEntity> result = loader.loadCollection(ChildEntity.class, "parent_id", 1L, ctx);

        // then: 2건 반환
        assertThat(result).hasSize(2);
        assertThat(result).extracting(c -> c.label).containsExactlyInAnyOrder("c1", "c2");
        // then: buildRowMapper가 identityMap에 등재했음을 ctx.contains로 검증 (정점 ②)
        assertThat(ctx.contains(new EntityKey(ChildEntity.class, result.get(0).id))).isTrue();
        assertThat(ctx.contains(new EntityKey(ChildEntity.class, result.get(1).id))).isTrue();
    }

    // -------- 테스트 전용 엔티티 --------

    @SfsEntity(name = "parents")
    static class ParentEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
    }

    @SfsEntity(name = "children")
    static class ChildEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsManyToOne(fetch = LAZY)
        @SfsJoinColumn(name = "parent_id")
        ParentEntity parent;
        @SfsColumn String label;
    }
}
