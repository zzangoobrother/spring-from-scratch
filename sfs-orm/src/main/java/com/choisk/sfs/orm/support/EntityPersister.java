package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.exception.SfsPersistenceException;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.jdbc.RowMapper;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * 엔티티 INSERT/SELECT/DELETE SQL 실행 담당.
 *
 * <p>B2에서 {@link EntityMetadataAnalyzer}가 미리 생성해둔 캐싱된 SQL({@code insertSql},
 * {@code selectByIdSql}, {@code deleteSql})을 이 클래스에서 처음 회수한다.
 *
 * <p>학습 정점 — {@code buildInsertParams}에서 {@code idGenerator.isPostInsert()} 분기:
 * <ul>
 *   <li>IDENTITY(post-insert): DB가 id를 자동 생성 → id 파라미터 제외</li>
 *   <li>SEQUENCE(pre-insert): INSERT 전에 id가 이미 결정됨 → id 파라미터 포함</li>
 * </ul>
 */
public class EntityPersister {

    /**
     * H2가 rs.getObject(idx, primitiveType.class)를 지원하지 않으므로
     * 프리미티브 타입을 대응 박싱 타입으로 매핑한다.
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_BOXED = Map.of(
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            byte.class, Byte.class,
            double.class, Double.class,
            float.class, Float.class,
            boolean.class, Boolean.class,
            char.class, Character.class
    );

    private final EntityMetadata md;
    private final IdentifierGenerator idGenerator;
    private final JdbcTemplate jdbc;
    // LAZY/EAGER 분기에서 연관 persister 조회 + LazyProxyFactory 접근용
    private SfsEntityManagerFactory emf;

    public EntityPersister(EntityMetadata md, IdentifierGenerator idGenerator, JdbcTemplate jdbc) {
        this.md = md;
        this.idGenerator = idGenerator;
        this.jdbc = jdbc;
    }

    /**
     * emf 역참조를 주입한다. 모든 persister 생성 완료 후 factory가 호출한다.
     * 생성자 시점에는 persisterByClass 맵이 완성되지 않아 생성자 주입 불가 — 2단계 초기화.
     *
     * @param emf EntityManagerFactory 인스턴스
     */
    public void setEmf(SfsEntityManagerFactory emf) {
        this.emf = emf;
    }

    /** 이 persister가 사용하는 식별자 생성기를 반환한다. */
    public IdentifierGenerator idGenerator() {
        return idGenerator;
    }

    /** 이 persister가 관리하는 엔티티 메타데이터를 반환한다. */
    public EntityMetadata metadata() {
        return md;
    }

    /**
     * PK로 엔티티 행을 조회한다.
     *
     * @param pk      조회할 기본 키 값
     * @param context 영속성 컨텍스트 (LAZY/EAGER 분기 + identityMap 등재)
     * @return 매핑된 엔티티 인스턴스, 없으면 null
     */
    public Object loadById(Object pk, PersistenceContext context) {
        List<Object> rows = jdbc.query(md.selectByIdSql(), buildRowMapper(context), pk);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    /**
     * 대상 테이블의 FK 컬럼이 일치하는 모든 행을 SELECT한다.
     * OneToMany 컬렉션 lazy init이 호출하는 진입점.
     *
     * <p>SQL 캡슐화는 persister가 담당, DefaultCollectionLoader는 persister lookup + 위임만(책임 분담).
     * 결과 row들은 buildRowMapper를 거치며 identityMap에 등재(정점 ②).
     *
     * @param fkColumn FK 컬럼명 (예: "user_id")
     * @param fkValue  FK 값 (부모 entity의 PK)
     * @param context  영속성 컨텍스트
     * @return 조회된 entity 리스트 (빈 리스트 가능)
     */
    public List<Object> findByForeignKey(String fkColumn, Object fkValue, PersistenceContext context) {
        // selectAllSql("SELECT id, ... FROM <table>")에 WHERE 절만 덧붙임 — Phase 4 SQL 캐싱 일관성
        String sql = md.selectAllSql() + " WHERE " + fkColumn + " = ?";
        return jdbc.query(sql, buildRowMapper(context), fkValue);
    }

    /**
     * 엔티티를 DB에 INSERT한다.
     * {@code buildInsertParams}에서 {@code isPostInsert()} 분기로 IDENTITY/SEQUENCE 처리를 구분한다.
     *
     * @param entity 삽입할 엔티티 인스턴스
     */
    public void executeInsert(Object entity) {
        Object[] params = buildInsertParams(entity);
        jdbc.update(md.insertSql(), params);
    }

    /**
     * 엔티티의 dirty 컬럼만 SET 절에 포함한 동적 UPDATE SQL을 실행한다.
     *
     * <p>Hibernate {@code @DynamicUpdate} 패턴 — BitSet 한 비트가 "이 컬럼은 변경됐다"를 표현.
     * 인덱스 레이아웃: 0..N-1 은 {@code md.columns()}, N..M-1 은 {@code md.manyToOnes()}.
     *
     * <p>빈 BitSet no-op 가드: 변경 없는 엔티티가 flush에 흘러들어도
     * 빈 SET 절 syntax error를 만들지 않는 방어적 설계.
     *
     * @param entity       업데이트할 엔티티 인스턴스
     * @param dirtyColumns dirty 여부를 나타내는 BitSet (비어있으면 no-op)
     * @throws SfsPersistenceException 필드 reflection 접근 실패 시
     */
    public void executeUpdate(Object entity, BitSet dirtyColumns) {
        // 빈 BitSet no-op 가드 — 빈 SET 절 syntax error 방지
        if (dirtyColumns.isEmpty()) return;

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        int colIdx = 0;
        try {
            // 일반 컬럼: BitSet 인덱스 0..N-1
            for (FieldMetadata col : md.columns()) {
                if (dirtyColumns.get(colIdx)) {
                    setClauses.add(col.columnName() + " = ?");
                    params.add(col.field().get(entity));
                }
                colIdx++;
            }
            // @ManyToOne FK 컬럼: BitSet 인덱스 N..M-1 (columns와 같은 공간 공유)
            for (RelationMetadata rel : md.manyToOnes()) {
                if (dirtyColumns.get(colIdx)) {
                    setClauses.add(rel.joinColumnName() + " = ?");
                    Object related = rel.field().get(entity);
                    params.add(related == null ? null : EntityMetadataAnalyzer.extractIdFieldValue(related, rel.targetEntity()));
                }
                colIdx++;
            }
            // WHERE 조건용 PK 파라미터를 마지막에 추가
            params.add(md.idField().field().get(entity));
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("엔티티 필드 읽기 실패 — UPDATE 파라미터 구성 불가", e);
        }

        String sql = "UPDATE " + md.tableName()
                + " SET " + String.join(", ", setClauses)
                + " WHERE " + md.idField().columnName() + " = ?";
        jdbc.update(sql, params.toArray());
    }

    /**
     * 엔티티를 DB에서 DELETE한다.
     *
     * @param entity 삭제할 엔티티 인스턴스 (@SfsId 필드가 PK 조건에 사용됨)
     * @throws SfsPersistenceException @SfsId 필드 접근 불가 시
     */
    public void executeDelete(Object entity) {
        try {
            Object pk = md.idField().field().get(entity);
            jdbc.update(md.deleteSql(), pk);
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("@SfsId 읽기 실패 — DELETE 불가", e);
        }
    }

    // -------- private 헬퍼 메서드 --------

    /**
     * INSERT SQL 바인딩 파라미터 배열을 구성한다.
     *
     * <p>IDENTITY(post-insert): id 제외 → [columns..., fk-columns...]
     * <p>SEQUENCE(pre-insert) : id 포함 → [id, columns..., fk-columns...]
     */
    private Object[] buildInsertParams(Object entity) {
        List<Object> params = new ArrayList<>();
        try {
            // IDENTITY 전략은 DB가 id를 자동 생성하므로 id 파라미터 제외
            if (!idGenerator.isPostInsert()) {
                params.add(md.idField().field().get(entity));
            }
            // 일반 컬럼 파라미터 추가
            for (FieldMetadata col : md.columns()) {
                params.add(col.field().get(entity));
            }
            // @ManyToOne FK 파라미터 추가 (관련 엔티티의 @SfsId 값 추출)
            for (RelationMetadata rel : md.manyToOnes()) {
                Object related = rel.field().get(entity);
                params.add(related == null ? null : EntityMetadataAnalyzer.extractIdFieldValue(related, rel.targetEntity()));
            }
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("엔티티 필드 읽기 실패 — INSERT 파라미터 구성 불가", e);
        }
        return params.toArray();
    }


    /**
     * 프리미티브 타입을 대응 박싱 타입으로 변환한다.
     * H2는 rs.getObject(idx, int.class)를 지원하지 않으므로 Integer.class 등으로 변환이 필요하다.
     * 프리미티브 타입이 아니면 그대로 반환한다.
     */
    @SuppressWarnings("unchecked")
    private <T> Class<T> toBoxedType(Class<T> type) {
        Class<?> boxed = PRIMITIVE_TO_BOXED.get(type);
        return (Class<T>) (boxed != null ? boxed : type);
    }

    /**
     * ResultSet 행을 엔티티 인스턴스로 변환하는 RowMapper를 반환한다.
     *
     * <p>D1 rewrite — 변경점 3가지:
     * <ol>
     *   <li>id를 idx=1에서 미리 추출 → cache-hit read (정점 ②: 같은 PK = 같은 인스턴스)</li>
     *   <li>일반 컬럼 idx를 2부터 시작 (id는 위에서 처리했으므로)</li>
     *   <li>끝에 putEntity 등록 — findByForeignKey/findAll/loadById/find 모두 이 한 곳으로 일관 등록</li>
     * </ol>
     *
     * <p>@SfsManyToOne LAZY/EAGER 분기 로직은 그대로 보존.
     * context가 null이면 LAZY/EAGER 관계 채우기 + 등록을 건너뜀 (fallback 로드 순환 회피).
     *
     * @param context 영속성 컨텍스트 (null이면 관계 채우기 및 identityMap 등재 생략)
     */
    private RowMapper<Object> buildRowMapper(PersistenceContext context) {
        return (rs, rowNum) -> {
            try {
                // id를 먼저 추출 — cache-hit read의 키 (정점 ②)
                Object pkValue = rs.getObject(1, toBoxedType(md.idField().javaType()));
                if (context != null) {
                    Object existing = context.getEntity(new EntityKey(md.entityClass(), pkValue));
                    // 같은 PK = 같은 인스턴스: identityMap에 이미 있으면 즉시 반환
                    if (existing != null) return existing;
                }
                // 기본 생성자로 인스턴스 생성
                Object instance = md.entityClass().getDeclaredConstructor().newInstance();
                md.idField().field().set(instance, pkValue);

                // id는 위에서 처리했으므로 일반 컬럼은 idx=2부터 시작
                int idx = 2;
                // 일반 컬럼 필드 매핑
                for (FieldMetadata col : md.columns()) {
                    col.field().set(instance, rs.getObject(idx++, toBoxedType(col.javaType())));
                }
                // @ManyToOne FK 컬럼 — LAZY/EAGER 분기 (기존 로직 그대로 보존)
                for (RelationMetadata rel : md.manyToOnes()) {
                    Object fk = rs.getObject(idx++);
                    // fk null: 연관 엔티티 없음 / context null: fallback 로드(순환 참조 회피)
                    if (fk == null || context == null) continue;
                    if (rel.fetch() == SfsManyToOne.FetchType.LAZY) {
                        // LAZY: byte-buddy 프록시 생성 — 실제 접근 시 DB fallback 로드
                        Object proxy = emf.lazyProxyFactory().createProxy(
                                rel.targetEntity(), fk, context);
                        rel.field().set(instance, proxy);
                    } else {
                        // EAGER: 즉시 별도 SELECT (1차 캐시 경유)
                        EntityKey relKey = new EntityKey(rel.targetEntity(), fk);
                        Object related = context.getEntity(relKey);
                        if (related == null) {
                            EntityPersister relPersister = emf.persisterOf(rel.targetEntity());
                            related = relPersister.loadById(fk, context);
                            if (related != null) {
                                context.putEntity(relKey, related);
                            }
                        }
                        rel.field().set(instance, related);
                    }
                }
                // 정점 ② 등록 + @SfsOneToMany 컬렉션 stub 주입 (context 있는 정상 로드 경로만)
                if (context != null) {
                    // @SfsOneToMany 필드 — SfsPersistentList stub 주입
                    // WHY: context 가드 안에 두는 이유 — SfsPersistentList가 context.isClosed()를 호출하므로
                    //      context==null fallback 경로에서 주입하면 NPE. manyToOnes 처럼 fallback 엔티티는
                    //      컬렉션도 null로 degrade하는 것이 일관성 있는 설계.
                    // WHY: DB 호출 0 — stub 생성만, 첫 List 메서드 호출 시점에 lazy 발화 (학습 정점 ①)
                    for (CollectionMetadata col : md.oneToManies()) {
                        @SuppressWarnings("unchecked")
                        SfsPersistentList<Object> proxy = new SfsPersistentList<>(
                                (Class<Object>) col.elementType(), pkValue, col.joinColumnName(),
                                emf.collectionLoader(), context);
                        col.field().set(instance, proxy);
                    }
                    context.putEntity(new EntityKey(md.entityClass(), pkValue), instance);
                }
                return instance;
            } catch (Exception e) {
                throw new SfsPersistenceException(
                        "Row mapping 실패: " + md.entityClass().getName(), e);
            }
        };
    }
}
