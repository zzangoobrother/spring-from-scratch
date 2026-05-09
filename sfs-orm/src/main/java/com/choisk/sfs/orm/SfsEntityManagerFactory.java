package com.choisk.sfs.orm;

import com.choisk.sfs.orm.support.EntityMetadata;
import com.choisk.sfs.orm.support.EntityMetadataAnalyzer;
import com.choisk.sfs.orm.support.EntityPersister;
import com.choisk.sfs.orm.support.IdGeneratorSpec;
import com.choisk.sfs.orm.support.IdentifierGenerator;
import com.choisk.sfs.orm.support.IdentityGenerator;
import com.choisk.sfs.orm.support.LazyProxyFactory;
import com.choisk.sfs.orm.support.SequenceGenerator;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SfsEntityManager 인스턴스를 생성하는 팩토리 — fluent Builder 패턴.
 * D1 이전이라 RealEntityManager/EntityPersister 등은 stub 상태.
 */
public class SfsEntityManagerFactory {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionSynchronizationManager tsm;
    private final Map<Class<?>, EntityMetadata> metadataByClass;
    private final Map<Class<?>, EntityPersister> persisterByClass;
    private final LazyProxyFactory lazyProxyFactory;

    private SfsEntityManagerFactory(Builder b) {
        this.dataSource = b.dataSource;
        this.tsm = b.tsm;
        this.jdbcTemplate = new JdbcTemplate(dataSource, tsm);
        this.lazyProxyFactory = new LazyProxyFactory();

        EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();
        this.metadataByClass = new HashMap<>();
        this.persisterByClass = new HashMap<>();
        for (Class<?> ec : b.entityClasses) {
            EntityMetadata md = analyzer.analyze(ec);
            metadataByClass.put(ec, md);
        }
        // EntityPersister는 generator를 필요로 하므로 generator 생성 후 persister 생성
        for (var entry : metadataByClass.entrySet()) {
            IdentifierGenerator gen = createGenerator(entry.getValue().idGeneratorSpec(), jdbcTemplate);
            EntityPersister persister = new EntityPersister(entry.getValue(), gen, jdbcTemplate);
            persisterByClass.put(entry.getKey(), persister);
        }
    }

    private static IdentifierGenerator createGenerator(IdGeneratorSpec spec, JdbcTemplate jdbc) {
        return switch (spec.strategy()) {
            case IDENTITY -> new IdentityGenerator(jdbc);
            case SEQUENCE -> new SequenceGenerator(spec.sequenceName(), jdbc);
        };
    }

    public SfsEntityManager createEntityManager() {
        return new RealEntityManager(this);
    }

    // package-private getters — RealEntityManager(같은 패키지)에서 접근
    EntityMetadata metadataOf(Class<?> ec) { return metadataByClass.get(ec); }

    EntityPersister persisterOf(Class<?> ec) { return persisterByClass.get(ec); }

    LazyProxyFactory lazyProxyFactory() { return lazyProxyFactory; }

    DataSource dataSource() { return dataSource; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DataSource dataSource;
        private TransactionSynchronizationManager tsm;
        private final List<Class<?>> entityClasses = new ArrayList<>();

        public Builder dataSource(DataSource ds) {
            this.dataSource = ds;
            return this;
        }

        public Builder transactionSynchronizationManager(TransactionSynchronizationManager tsm) {
            this.tsm = tsm;
            return this;
        }

        public Builder addEntityClass(Class<?> ec) {
            entityClasses.add(ec);
            return this;
        }

        public SfsEntityManagerFactory build() {
            Objects.requireNonNull(dataSource, "dataSource");
            Objects.requireNonNull(tsm, "transactionSynchronizationManager");
            return new SfsEntityManagerFactory(this);
        }
    }
}
