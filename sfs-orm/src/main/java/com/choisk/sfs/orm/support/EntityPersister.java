package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.exception.SfsPersistenceException;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.jdbc.RowMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
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

    public EntityPersister(EntityMetadata md, IdentifierGenerator idGenerator, JdbcTemplate jdbc) {
        this.md = md;
        this.idGenerator = idGenerator;
        this.jdbc = jdbc;
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
                params.add(related == null ? null : extractFk(related, rel.targetEntity()));
            }
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("엔티티 필드 읽기 실패 — INSERT 파라미터 구성 불가", e);
        }
        return params.toArray();
    }

    /**
     * 연관 엔티티의 @SfsId 필드 값을 추출한다 (FK 값 추출).
     *
     * @param related    연관 엔티티 인스턴스
     * @param targetType 연관 엔티티 클래스
     * @return FK 값 (연관 엔티티의 @SfsId 필드 값)
     * @throws SfsPersistenceException @SfsId 필드가 없거나 접근 불가 시
     */
    private Object extractFk(Object related, Class<?> targetType) {
        for (Field f : targetType.getDeclaredFields()) {
            if (f.isAnnotationPresent(SfsId.class)) {
                f.setAccessible(true);
                try {
                    return f.get(related);
                } catch (IllegalAccessException e) {
                    throw new SfsPersistenceException("FK 추출 실패: " + targetType.getName(), e);
                }
            }
        }
        throw new SfsPersistenceException(
                "@SfsId 누락 — FK 추출 불가: " + targetType.getName());
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
     * <p><b>forward stub 주석:</b> J1(다음 task)에서 @SfsManyToOne 필드에 대해
     * LAZY/EAGER 분기 + lazy proxy 채우기 로직이 추가될 자리.
     * 현재는 FK 컬럼 값을 ResultSet에서 읽되 엔티티 필드에 반영하지 않는다.
     *
     * @param context 영속성 컨텍스트 (J1에서 lazy proxy 구성 시 사용 예정)
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
                // @ManyToOne FK 컬럼 — J1에서 LAZY/EAGER 분기 + lazy proxy 채우기 추가
                // 현재는 fk 값을 읽어 건너뜀 (Task J1까지 lazy/eager 처리 미구현)
                for (RelationMetadata rel : md.manyToOnes()) {
                    rs.getObject(idx++); // fk 값 읽기만 하고 미사용
                    // TODO(J1): fetch == LAZY 이면 LazyProxyFactory로 프록시 생성,
                    //           fetch == EAGER 이면 persister.loadById(fk, context) 재귀 호출
                    // rel은 J1 분기 추가 시 사용 예정
                    @SuppressWarnings("unused") RelationMetadata unusedRel = rel;
                }
                return instance;
            } catch (Exception e) {
                throw new SfsPersistenceException(
                        "Row mapping 실패: " + md.entityClass().getName(), e);
            }
        };
    }
}
