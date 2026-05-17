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

        EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();
        this.metadataByClass = new HashMap<>();
        this.persisterByClass = new HashMap<>();
        // 메타데이터 분석 + persister 생성 단일 순회 — 순서 의존 없음 (generator는 md에서 즉시 생성 가능)
        for (Class<?> ec : b.entityClasses) {
            EntityMetadata md = analyzer.analyze(ec);
            metadataByClass.put(ec, md);
            IdentifierGenerator gen = createGenerator(md.idGeneratorSpec(), jdbcTemplate);
            persisterByClass.put(ec, new EntityPersister(md, gen, jdbcTemplate));
        }

        // LazyProxyFactory에 loader 람다 주입
        // loader의 context는 null 고정 — fallback 로드는 관계를 재귀 채우지 않음 (순환 참조 회피)
        this.lazyProxyFactory = new LazyProxyFactory(
                (targetClass, pk) -> {
                    EntityPersister p = persisterByClass.get(targetClass);
                    return p == null ? null : p.loadById(pk, null);
                });

        // 모든 persister 생성 완료 후 emf 역참조 주입 (LAZY/EAGER 분기에서 persisterOf 접근용)
        for (EntityPersister persister : persisterByClass.values()) {
            persister.setEmf(this);
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

    // EntityPersister(support 패키지)에서 EAGER 재귀 로드 시 접근하므로 public
    public EntityPersister persisterOf(Class<?> ec) { return persisterByClass.get(ec); }

    // EntityPersister(support 패키지)에서 LAZY proxy 생성 시 접근하므로 public
    public LazyProxyFactory lazyProxyFactory() { return lazyProxyFactory; }

    DataSource dataSource() { return dataSource; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private DataSource dataSource;
        private TransactionSynchronizationManager tsm;
        private final List<Class<?>> entityClasses = new ArrayList<>();

        public Builder dataSource(DataSource ds) {
            this.dataSource = Objects.requireNonNull(ds, "dataSource");
            return this;
        }

        public Builder transactionSynchronizationManager(TransactionSynchronizationManager tsm) {
            this.tsm = Objects.requireNonNull(tsm, "transactionSynchronizationManager");
            return this;
        }

        public Builder addEntityClass(Class<?> ec) {
            entityClasses.add(ec);
            return this;
        }

        public SfsEntityManagerFactory build() {
            return new SfsEntityManagerFactory(this);
        }
    }
}
