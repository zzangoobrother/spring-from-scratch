package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 엔티티 클래스를 리플렉션으로 분석해 EntityMetadata를 생성하는 분석기.
 * - fail-fast: @SfsEntity 미존재, @SfsId 0개/복수, SEQUENCE sequenceName 누락,
 *   @SfsManyToOne 대상 타입 비엔티티, @SfsJoinColumn 누락 등 6종 즉시 예외
 * - 결과는 ConcurrentHashMap으로 클래스 단위 캐싱 (race-safe)
 */
public class EntityMetadataAnalyzer {

    /** 클래스 단위 메타데이터 캐시 — 동일 클래스 2회 이상 analyze() 호출 시 동일 인스턴스 반환 */
    private final Map<Class<?>, EntityMetadata> cache = new ConcurrentHashMap<>();

    /**
     * 엔티티 클래스를 분석하여 EntityMetadata를 반환한다.
     * 이미 분석된 클래스는 캐시에서 반환한다.
     *
     * @param entityClass 분석 대상 엔티티 클래스
     * @return 분석 결과 EntityMetadata
     * @throws SfsEntityMappingException fail-fast 검증 실패 시
     */
    public EntityMetadata analyze(Class<?> entityClass) {
        return cache.computeIfAbsent(entityClass, this::doAnalyze);
    }

    // -------- 내부 분석 로직 --------

    private EntityMetadata doAnalyze(Class<?> entityClass) {
        // 1) @SfsEntity 존재 검증
        SfsEntity entityAnno = entityClass.getAnnotation(SfsEntity.class);
        if (entityAnno == null) {
            throw new SfsEntityMappingException(
                    "Class " + entityClass.getName() + " is not annotated with @SfsEntity");
        }
        String tableName = entityAnno.name().isEmpty()
                ? entityClass.getSimpleName()
                : entityAnno.name();

        // 2) @SfsId 필드 검증 (0개 / 복수 모두 fail-fast)
        List<Field> idFields = Arrays.stream(entityClass.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(SfsId.class))
                .toList();
        if (idFields.isEmpty()) {
            throw new SfsEntityMappingException(
                    "Entity " + entityClass.getName() + " has no @SfsId field");
        }
        if (idFields.size() > 1) {
            throw new SfsEntityMappingException(
                    "Entity " + entityClass.getName() + " has multiple @SfsId fields (only one allowed)");
        }
        Field idField = idFields.get(0);
        idField.setAccessible(true);

        // 3) @SfsGeneratedValue 검증 + IdGeneratorSpec 생성
        IdGeneratorSpec idGeneratorSpec = createIdGeneratorSpec(idField);
        FieldMetadata idMeta = new FieldMetadata(idField, columnNameOf(idField), idField.getType());

        // 4) 일반 컬럼 + 연관 관계 필드 수집
        List<FieldMetadata> columns = new ArrayList<>();
        List<RelationMetadata> manyToOnes = new ArrayList<>();
        for (Field f : entityClass.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.equals(idField)) continue;
            if (f.isAnnotationPresent(SfsManyToOne.class)) {
                validateManyToOne(f);
                SfsManyToOne rel = f.getAnnotation(SfsManyToOne.class);
                SfsJoinColumn joinCol = f.getAnnotation(SfsJoinColumn.class);
                manyToOnes.add(new RelationMetadata(f, rel.fetch(), f.getType(), joinCol.name()));
            } else if (f.isAnnotationPresent(SfsColumn.class)) {
                columns.add(new FieldMetadata(f, columnNameOf(f), f.getType()));
            }
        }

        // 5) SQL 미리 생성
        String insertSql = buildInsertSql(tableName, idField, columns, manyToOnes, idGeneratorSpec);
        String selectSql = buildSelectByIdSql(tableName, idField, columns, manyToOnes);
        String deleteSql = buildDeleteSql(tableName, idField);

        return new EntityMetadata(entityClass, tableName, idMeta, idGeneratorSpec,
                columns, manyToOnes, insertSql, selectSql, deleteSql);
    }

    /**
     * @SfsManyToOne 필드 fail-fast 검증:
     * - 대상 타입이 @SfsEntity 미소유 → 예외
     * - @SfsJoinColumn 누락 → 예외
     */
    private void validateManyToOne(Field f) {
        Class<?> targetType = f.getType();
        if (!targetType.isAnnotationPresent(SfsEntity.class)) {
            throw new SfsEntityMappingException(
                    "@SfsManyToOne field '" + f.getName()
                    + "' target type " + targetType.getName()
                    + " is not annotated with @SfsEntity");
        }
        if (!f.isAnnotationPresent(SfsJoinColumn.class)) {
            throw new SfsEntityMappingException(
                    "@SfsManyToOne field '" + f.getName() + "' missing @SfsJoinColumn");
        }
    }

    /**
     * @SfsGeneratedValue 어노테이션으로부터 IdGeneratorSpec을 생성한다.
     * - @SfsGeneratedValue 미존재 → 예외
     * - SEQUENCE 전략에서 sequenceName 누락 → 예외
     */
    private IdGeneratorSpec createIdGeneratorSpec(Field idField) {
        SfsGeneratedValue gv = idField.getAnnotation(SfsGeneratedValue.class);
        if (gv == null) {
            throw new SfsEntityMappingException(
                    "@SfsId field '" + idField.getName() + "' missing @SfsGeneratedValue");
        }
        if (gv.strategy() == GenerationType.SEQUENCE && gv.sequenceName().isEmpty()) {
            throw new SfsEntityMappingException(
                    "@SfsGeneratedValue(SEQUENCE) on '" + idField.getName() + "' missing sequenceName");
        }
        return new IdGeneratorSpec(gv.strategy(), gv.sequenceName());
    }

    /** @SfsColumn name 속성 우선, 없으면 필드명 사용 */
    private String columnNameOf(Field f) {
        SfsColumn col = f.getAnnotation(SfsColumn.class);
        if (col != null && !col.name().isEmpty()) return col.name();
        return f.getName();
    }

    // -------- SQL 빌더 --------

    /**
     * INSERT SQL 생성.
     * IDENTITY 전략이면 id 컬럼은 포함하지 않는다 (DB가 자동 생성).
     */
    private String buildInsertSql(String table, Field idField,
                                  List<FieldMetadata> cols,
                                  List<RelationMetadata> rels,
                                  IdGeneratorSpec spec) {
        boolean includeId = spec.strategy() != GenerationType.IDENTITY;
        List<String> colNames = new ArrayList<>();
        if (includeId) colNames.add(columnNameOf(idField));
        cols.forEach(c -> colNames.add(c.columnName()));
        rels.forEach(r -> colNames.add(r.joinColumnName()));
        String placeholders = colNames.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", colNames)
                + ") VALUES (" + placeholders + ")";
    }

    /** SELECT … WHERE id = ? SQL 생성 */
    private String buildSelectByIdSql(String table, Field idField,
                                      List<FieldMetadata> cols,
                                      List<RelationMetadata> rels) {
        List<String> colNames = new ArrayList<>();
        colNames.add(columnNameOf(idField));
        cols.forEach(c -> colNames.add(c.columnName()));
        rels.forEach(r -> colNames.add(r.joinColumnName()));
        return "SELECT " + String.join(", ", colNames) + " FROM " + table
                + " WHERE " + columnNameOf(idField) + " = ?";
    }

    /** DELETE WHERE id = ? SQL 생성 */
    private String buildDeleteSql(String table, Field idField) {
        return "DELETE FROM " + table + " WHERE " + columnNameOf(idField) + " = ?";
    }
}
