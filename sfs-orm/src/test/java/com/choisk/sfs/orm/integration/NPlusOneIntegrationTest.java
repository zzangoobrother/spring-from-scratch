package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
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

/**
 * N+1 문제 자연 노출 — 학습 정점 ② 박제.
 *
 * <p>findAll(Owner) 1회 SELECT + for-loop에서 각 owner.children.size() 호출 시
 * owner 수만큼 추가 SELECT가 발생함(N+1)을 정확한 카운트로 증명한다.
 * 두 번째 테스트는 SfsPersistentList 캐시 hit으로 재실행 시 추가 SELECT 0을 검증.
 *
 * <p>각 @BeforeEach마다 nanoTime URL로 독립 H2 DB를 생성해 테스트 간 격리 보장.
 * (SfsEntityManagerFactory에 close() 없으므로 @AfterEach 없음 — M1 발견 반영)
 */
class NPlusOneIntegrationTest {

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
        ds.setURL("jdbc:h2:mem:nplus1-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);
        spyJdbc = new SqlCountingJdbcTemplate(ds, tsm);

        // DDL + 사전 데이터: Owner 3명 + 각 1 Child (setup SQL은 reset() 전이므로 측정 제외)
        spyJdbc.update("CREATE TABLE owners (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        spyJdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "owner_id BIGINT, label VARCHAR(50))");

        // Owner 3명 + 각각 1 Child — N+1 시나리오: findAll 1 + children SELECT 3 = 총 4
        for (int i = 1; i <= 3; i++) {
            spyJdbc.update("INSERT INTO owners (name) VALUES (?)", "owner" + i);
            spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (?, ?)", i, "c" + i);
        }

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
     * 학습 정점 ②-a: findAll(Owner) + for-loop children.size() = N+1 SELECT.
     * findAll 1 SELECT + owner 3명 × children 각 1 SELECT = 총 4 SELECT.
     */
    @Test
    void findAll_owner_3명_for_loop_children_size_시_정확히_N_plus_1_SELECT() {
        TransactionTemplate.execute(tm, () -> {
            // 사전 setup INSERT SQL은 카운트 제외 — 이 시점부터 측정
            spyJdbc.reset();

            List<TestOwner> owners = em.findAll(TestOwner.class);
            for (TestOwner o : owners) {
                // 각 owner마다 children 컬렉션 첫 접근 → lazy SELECT 1회 발생
                o.children.size();
            }

            // N+1 정점: findAll 1 SELECT + owner 수만큼 children 각 1 SELECT = 총 1+N SELECT
            assertThat(owners).hasSize(3);
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(1 + owners.size());
            return null;
        });
    }

    /**
     * 학습 정점 ②-b: for-loop 재실행 시 SfsPersistentList delegate 캐시 hit — 추가 SELECT 0.
     * 이미 로드된 컬렉션은 재쿼리 없이 캐시에서 반환됨을 증명.
     */
    @Test
    void for_loop_재실행_시_추가_SELECT_0_캐시_hit() {
        TransactionTemplate.execute(tm, () -> {
            List<TestOwner> owners = em.findAll(TestOwner.class);
            // 첫 N+1 실행 — 각 owner의 children 컬렉션 초기 로드
            for (TestOwner o : owners) o.children.size();
            // 첫 실행 SELECT 카운트 제외 — 재실행 구간만 측정
            spyJdbc.reset();

            // 재실행 — SfsPersistentList.delegate 캐시 hit으로 추가 SELECT 없어야 함
            for (TestOwner o : owners) o.children.size();

            assertThat(spyJdbc.countMatching("SELECT")).isZero();
            return null;
        });
    }

    // ─── 자체 fixture (★ public static class — M1 발견: EntityPersister가 reflection으로 newInstance() 호출) ───

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
        // 단방향 @SfsOneToMany — owner_id는 DB에 있지만 엔티티 매핑 없음
        @SfsColumn
        public String label;
    }
}
