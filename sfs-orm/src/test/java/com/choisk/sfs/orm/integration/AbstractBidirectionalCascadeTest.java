package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.SEQUENCE;

/**
 * MP-3 양방향 cascade/orphan 통합 테스트 공통 베이스.
 *
 * <p>양방향 fixture(CasUser ←→ CasOrder) + FK 제약(user_id REFERENCES cas_users)으로
 * cascade 삭제 순서가 DB 레벨에서 강제된다. 각 @BeforeEach마다 nanoTime URL로 격리
 * (SfsEntityManagerFactory에 close() 없음 — MP-2 발견 반영).
 */
abstract class AbstractBidirectionalCascadeTest {

    // public static + public 필드: EntityPersister가 다른 패키지에서 reflection newInstance/필드 접근(MP-2 I-1)
    @SfsEntity(name = "cas_users")
    public static class CasUser {
        @SfsId @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "cas_users_seq")
        public Long id;
        @SfsColumn public String name;
        @SfsOneToMany(mappedBy = "user",
                cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
        public List<CasOrder> orders = new ArrayList<>();

        /** 양방향 일관성 helper — 양쪽 세팅(application 책임 박제). */
        public void addOrder(CasOrder o) { orders.add(o); o.user = this; }
        public void removeOrder(CasOrder o) { orders.remove(o); o.user = null; }
        public List<CasOrder> getOrders() { return orders; }
    }

    @SfsEntity(name = "cas_orders")
    public static class CasOrder {
        @SfsId @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "cas_orders_seq")
        public Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "user_id")
        public CasUser user;
        @SfsColumn public String item;

        public CasUser getUser() { return user; }
    }

    protected JdbcDataSource ds;
    protected ThreadLocalTsm tsm;
    protected DataSourceTransactionManager tm;
    protected SfsEntityManagerFactory emf;
    protected SfsTransactionalEntityManager em;

    /** 하위 클래스가 jdbcTemplate 주입(spy 필요 시 override). 기본은 null = 빌더 기본 JdbcTemplate. */
    protected com.choisk.sfs.tx.jdbc.JdbcTemplate jdbcTemplateOverride() { return null; }

    @BeforeEach
    void setupBidirectional() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cas-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);

        com.choisk.sfs.tx.jdbc.JdbcTemplate ddl =
                new com.choisk.sfs.tx.jdbc.JdbcTemplate(ds, tsm);
        ddl.update("CREATE SEQUENCE cas_users_seq START WITH 1");
        ddl.update("CREATE SEQUENCE cas_orders_seq START WITH 1");
        ddl.update("CREATE TABLE cas_users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        // FK 제약: cascade 삭제 순서가 틀리면 H2가 위반 예외 → "자식 먼저"가 통과 필요조건
        ddl.update("CREATE TABLE cas_orders (id BIGINT PRIMARY KEY, user_id BIGINT, item VARCHAR(50), "
                + "FOREIGN KEY (user_id) REFERENCES cas_users(id))");

        SfsEntityManagerFactory.Builder b = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(CasUser.class)
                .addEntityClass(CasOrder.class);
        if (jdbcTemplateOverride() != null) b.jdbcTemplate(jdbcTemplateOverride());
        emf = b.build();
        em = new SfsTransactionalEntityManager(new SfsEntityManagerFactoryBean(emf, tsm), tsm);
    }
}
