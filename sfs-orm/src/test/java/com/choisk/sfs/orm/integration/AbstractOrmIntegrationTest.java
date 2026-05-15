package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStreamReader;
import java.math.BigDecimal;

/**
 * ORM 통합 테스트 공통 기반 클래스.
 *
 * <p>H2 in-memory DB + TransactionTemplate 인프라를 초기화하고
 * M1~M5 통합 테스트가 공통으로 사용하는 TestUser/TestOrder 엔티티를 정의한다.
 *
 * <p>각 하위 클래스는 자신만의 JDBC URL을 반환해 DB 격리를 보장한다.
 * 같은 URL을 공유하면 schema 충돌 + 데이터 오염이 발생한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractOrmIntegrationTest {

    // -------- 공유 엔티티 정의 --------

    /**
     * 통합 테스트용 사용자 엔티티.
     * SEQUENCE 전략(pre-insert, id 미리 확보).
     */
    @SfsEntity(name = "test_users")
    public static class TestUser {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "test_users_seq")
        Long id;
        @SfsColumn
        String name;
        @SfsColumn
        String email;

        public Long getId() { return id; }
        /**
         * LAZY proxy 초기화 trigger용 getter.
         * byte-buddy subclass proxy는 필드 직접 접근으로 lazy init이 발생하지 않는다.
         * 반드시 public 메서드를 통해야 LazyInterceptor가 invoke됨.
         */
        public String getName() { return name; }
    }

    /**
     * 통합 테스트용 주문 엔티티.
     * IDENTITY 전략(post-insert) + LAZY ManyToOne 연관.
     */
    @SfsEntity(name = "test_orders")
    public static class TestOrder {
        @SfsId
        @SfsGeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @SfsManyToOne(fetch = FetchType.LAZY)
        @SfsJoinColumn(name = "user_id")
        TestUser user;
        @SfsColumn
        BigDecimal amount;
        @SfsColumn
        String status;

        public Long getId() { return id; }
    }

    // -------- 공유 인프라 필드 --------

    protected JdbcDataSource ds;
    protected TransactionSynchronizationManager tsm;
    protected DataSourceTransactionManager tm;
    protected SfsEntityManagerFactory emf;
    protected SfsEntityManagerFactoryBean factoryBean;
    protected SfsTransactionalEntityManager em;
    protected JdbcTemplate jdbc;

    /**
     * 하위 클래스가 반환하는 JDBC URL로 H2 DB를 격리한다.
     * 예: {@code "jdbc:h2:mem:dirty;DB_CLOSE_DELAY=-1"}
     */
    protected abstract String jdbcUrl();

    /**
     * H2 DB + ORM 인프라 초기화.
     *
     * <p>schema는 테스트 클래스패스의 {@code orm-schema-test.sql}에서 로드한다.
     * 각 클래스가 자신만의 JDBC URL을 사용하므로 DB는 클래스마다 독립적이다.
     */
    @BeforeAll
    void setupAll() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(jdbcUrl());
        try (var conn = ds.getConnection();
             var reader = new InputStreamReader(
                     getClass().getResourceAsStream("/orm-schema-test.sql"))) {
            RunScript.execute(conn, reader);
        }
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);
        jdbc = new JdbcTemplate(ds, tsm);

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(TestUser.class)
                .addEntityClass(TestOrder.class)
                .build();
        factoryBean = new SfsEntityManagerFactoryBean(emf, tsm);
        em = new SfsTransactionalEntityManager(factoryBean, tsm);
    }

    /**
     * 각 테스트 전 데이터 정리.
     *
     * <p>test_orders → test_users 순서로 삭제해 FK 제약 위반을 피한다.
     */
    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM test_orders");
        jdbc.update("DELETE FROM test_users");
    }
}
