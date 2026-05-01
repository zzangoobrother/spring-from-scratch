package com.choisk.sfs.samples.todo.config;

import com.choisk.sfs.aop.support.AspectEnhancingBeanPostProcessor;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.samples.todo.support.IdGenerator;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;

@Configuration
@ComponentScan(basePackages = {
        "com.choisk.sfs.samples.todo",
        "com.choisk.sfs.samples.order"
})
public class AppConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new IdGenerator(clock);
    }

    @Bean
    public AspectEnhancingBeanPostProcessor aspectBpp() {
        return new AspectEnhancingBeanPostProcessor();
    }

    @Bean
    public DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sfs-demo;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        // schema.sql 직접 실행 — 학습 박제 (마이그레이션 도구 비목표, spec § 7)
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String schema = new String(getClass().getClassLoader()
                    .getResourceAsStream("schema.sql").readAllBytes());
            for (String stmt : schema.split(";")) {
                if (!stmt.trim().isEmpty()) s.execute(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException("schema.sql load failed", e);
        }
        return ds;
    }

    @Bean
    public TransactionSynchronizationManager tsm() {
        return new ThreadLocalTsm();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource ds, TransactionSynchronizationManager tsm) {
        return new DataSourceTransactionManager(ds, tsm);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) {
        return new JdbcTemplate(ds, tsm);
    }

    @Bean
    public TransactionalBeanPostProcessor transactionalBeanPostProcessor() {
        return new TransactionalBeanPostProcessor();
    }
}
