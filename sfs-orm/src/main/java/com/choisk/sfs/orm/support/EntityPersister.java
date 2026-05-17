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
     * @param context 영속성 컨텍스트 (J1에서 LAZY/EAGER 분기 처리 예정, 현재는 미사용)
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
     * <p>J1 통합: @SfsManyToOne 필드에 대해 LAZY/EAGER 분기를 수행한다.
     * <ul>
     *   <li>LAZY: {@link LazyProxyFactory}로 프록시 생성 — 실제 접근 시 DB fallback 로드</li>
     *   <li>EAGER: {@link EntityPersister#loadById}로 즉시 별도 SELECT (1차 캐시 경유)</li>
     * </ul>
     *
     * <p>context가 null이면 LAZY/EAGER 관계 채우기를 건너뜀 (fallback 로드 순환 회피).
     *
     * @param context 영속성 컨텍스트 (null이면 관계 채우기 생략)
     */
    private RowMapper<Object> buildRowMapper(PersistenceContext context) {
        return (rs, rowNum) -> {
            try {
                // 기본 생성자로 인스턴스 생성
                Object instance = md.entityClass().getDeclaredConstructor().newInstance();

                // SELECT 컬럼 순서: id → columns... → fk-columns...
                int idx = 1;
                // id 필드 매핑 (프리미티브 타입은 박싱 타입으로 변환 후 getObject 호출)
                md.idField().field().set(instance,
                        rs.getObject(idx++, toBoxedType(md.idField().javaType())));
                // 일반 컬럼 필드 매핑
                for (FieldMetadata col : md.columns()) {
                    col.field().set(instance, rs.getObject(idx++, toBoxedType(col.javaType())));
                }
                // @ManyToOne FK 컬럼 — LAZY/EAGER 분기
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
                return instance;
            } catch (Exception e) {
                throw new SfsPersistenceException(
                        "Row mapping 실패: " + md.entityClass().getName(), e);
            }
        };
    }
}
