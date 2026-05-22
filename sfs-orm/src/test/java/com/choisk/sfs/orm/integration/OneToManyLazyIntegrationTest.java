package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @SfsOneToMany 컬렉션 lazy 발화 시점 + PC closed 예외 통합 검증 — 학습 정점 ① 박제.
 *
 * <p>H2 in-memory DB + SqlCountingJdbcTemplate spy로 SELECT 횟수를 정확히 측정.
 * 각 {@code @BeforeEach}마다 nanoTime URL로 독립 DB를 생성해 격리를 보장한다.
 * (SfsEntityManagerFactory에 close() 없으므로 @AfterEach 없음)
 */
class OneToManyLazyIntegrationTest {

    private JdbcDataSource ds;
    private SqlCountingJdbcTemplate spyJdbc;
    private TransactionSynchronizationManager tsm;
    private DataSourceTransactionManager tm;
    private SfsEntityManagerFactory emf;
    private SfsTransactionalEntityManager em;

    @BeforeEach
    void setUp() {
        // 각 테스트마다 고유 URL — H2 in-memory DB 격리
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:o2mlazy-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);
        spyJdbc = new SqlCountingJdbcTemplate(ds, tsm);

        // DDL + 초기 데이터 삽입 (카운터 초기화 전이므로 setup SQL은 무시)
        spyJdbc.update("CREATE TABLE owners (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        spyJdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "owner_id BIGINT, label VARCHAR(50))");
        spyJdbc.update("INSERT INTO owners (name) VALUES ('alice')");
        spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (1, 'c1')");
        spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (1, 'c2')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                // spy JdbcTemplate 주입 — 모든 persister가 이 인스턴스를 공유하므로 컬렉션 SELECT도 잡힘
                .jdbcTemplate(spyJdbc)
                .addEntityClass(TestOwner.class)
                .addEntityClass(TestChild.class)
                .build();
        em = new SfsTransactionalEntityManager(new SfsEntityManagerFactoryBean(emf, tsm), tsm);
    }

    /**
     * 학습 정점 ①-a: 컬렉션 첫 size() 호출 시 정확히 1 SELECT 발생,
     * 두 번째 호출은 캐시 hit — 추가 SELECT 0.
     */
    @Test
    void getChildren_size_첫_호출_시_정확히_1_SELECT_발생() {
        TransactionTemplate.execute(tm, () -> {
            TestOwner owner = em.find(TestOwner.class, 1L);
            // find SELECT + setup SQL을 제외하고 컬렉션 lazy 구간만 측정
            spyJdbc.reset();

            int count = owner.children.size();
            assertThat(count).isEqualTo(2);
            // 첫 size() 호출 시 컬렉션 로드 SELECT 1회만 발생해야 함
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(1);

            // 두 번째 size() 호출 — delegate 캐시 hit, 추가 SELECT 없어야 함
            owner.children.size();
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(1);

            return null;
        });
    }

    /**
     * 학습 정점 ①-b: 트랜잭션 경계 종료 후 PersistenceContext가 close되고,
     * 이후 children.size() 호출 시 SfsLazyInitializationException이 발생해야 함.
     */
    @Test
    void TSM_경계_종료_후_getChildren_size_호출_시_SfsLazyInitializationException() {
        // 트랜잭션 내에서 owner 로드 — children은 SfsPersistentList stub(미초기화)
        TestOwner owner = TransactionTemplate.execute(tm, () -> em.find(TestOwner.class, 1L));
        // 트랜잭션 commit 완료 → afterCompletion 콜백으로 PC close

        // PC closed 상태에서 컬렉션 접근 → SfsLazyInitializationException
        assertThatThrownBy(() -> owner.children.size())
                .isInstanceOf(SfsLazyInitializationException.class)
                .hasMessageContaining("TestChild#1");
    }

    // ─── 자체 fixture (sfs-orm 내부 — sfs-samples 의존 회피) ───────────

    // public: EntityPersister가 reflection으로 newInstance() 호출 — 다른 패키지에서 접근 가능해야 함
    @SfsEntity(name = "owners")
    public static class TestOwner {
        @SfsId
        @SfsGeneratedValue(strategy = IDENTITY)
        public Long id;
        @SfsColumn
        public String name;
        @SfsOneToMany(joinColumn = "owner_id")
        public List<TestChild> children;
    }

    @SfsEntity(name = "children")
    public static class TestChild {
        @SfsId
        @SfsGeneratedValue(strategy = IDENTITY)
        public Long id;
        // 단방향 @SfsOneToMany — owner_id는 DB 컬럼에 있지만 엔티티 매핑 없음
        // SELECT id, label FROM children WHERE owner_id = ? 로 조회 가능
        @SfsColumn
        public String label;
    }
}
