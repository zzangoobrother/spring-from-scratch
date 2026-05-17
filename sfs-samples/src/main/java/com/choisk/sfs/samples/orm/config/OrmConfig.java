package com.choisk.sfs.samples.orm.config;

import com.choisk.sfs.aop.support.AspectEnhancingBeanPostProcessor;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.samples.orm.domain.AuditLog;
import com.choisk.sfs.samples.orm.domain.Order;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * ORM demo 전용 스프링 설정 — spec § 2.7 정합.
 * AppConfig와 독립 실행되도록 orm-schema.sql 전용 DataSource를 사용한다.
 */
@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.orm")
public class OrmConfig {

    @Bean
    public AspectEnhancingBeanPostProcessor aspectBpp() {
        return new AspectEnhancingBeanPostProcessor();
    }

    @Bean
    public TransactionalBeanPostProcessor transactionalBeanPostProcessor() {
        return new TransactionalBeanPostProcessor();
    }

    /**
     * ORM demo 전용 DataSource — orm-schema.sql 로딩.
     * 기존 schema.sql(sfs-tx demo)과 완전히 분리된 in-memory DB 사용.
     */
    @Bean
    public DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sfs-orm-demo;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        // orm-schema.sql 직접 실행 — AppConfig.dataSource() 패턴 복사
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String schema = new String(getClass().getClassLoader()
                    .getResourceAsStream("orm-schema.sql").readAllBytes());
            for (String stmt : schema.split(";")) {
                if (!stmt.trim().isEmpty()) s.execute(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException("orm-schema.sql load failed", e);
        }
        return ds;
    }

    @Bean
    public TransactionSynchronizationManager tsm() {
        return new ThreadLocalTsm();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource,
                                                         TransactionSynchronizationManager tsm) {
        return new DataSourceTransactionManager(dataSource, tsm);
    }

    /**
     * SfsEntityManagerFactory 빌더 — User/Order/AuditLog 3종 엔티티 클래스 등록.
     * tsm은 빌더 인자 필수 (JdbcTemplate 내부 생성 시 필요).
     */
    @Bean
    public SfsEntityManagerFactory entityManagerFactory(DataSource dataSource,
                                                         TransactionSynchronizationManager tsm) {
        return SfsEntityManagerFactory.builder()
                .dataSource(dataSource)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(User.class)
                .addEntityClass(Order.class)
                .addEntityClass(AuditLog.class)
                .build();
    }

    /**
     * SfsEntityManagerFactoryBean — 트랜잭션 생명주기에 EM을 연결하는 어댑터.
     * 실제 생성자: SfsEntityManagerFactoryBean(SfsEntityManagerFactory, TransactionSynchronizationManager)
     */
    @Bean
    public SfsEntityManagerFactoryBean emfLifecycle(SfsEntityManagerFactory entityManagerFactory,
                                                     TransactionSynchronizationManager tsm) {
        return new SfsEntityManagerFactoryBean(entityManagerFactory, tsm);
    }

    /**
     * 트랜잭션-범위 EntityManager 프록시.
     * 실제 생성자: SfsTransactionalEntityManager(SfsEntityManagerFactoryBean, TransactionSynchronizationManager)
     */
    @Bean
    public SfsEntityManager entityManager(SfsEntityManagerFactoryBean emfLifecycle,
                                           TransactionSynchronizationManager tsm) {
        return new SfsTransactionalEntityManager(emfLifecycle, tsm);
    }
}
