package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.exception.SfsPersistenceException;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * IDENTITY 전략 식별자 생성기.
 *
 * <p><b>학습 정점 ① 깨짐 함정 박제:</b>
 * {@code PersistenceContext.actionQueue}(D1)은 write-behind 패턴의 자료구조로,
 * persist 시점에 INSERT를 지연시킨다. 그러나 IDENTITY 전략은 DB가 INSERT 후 키를 생성하므로
 * flush 전까지 ID를 알 수 없다. 따라서 {@code generate()}가 호출되는 순간 바로 INSERT를
 * 실행해야 하며, 이는 write-behind의 "지연 실행" 원칙을 깨는 함정이다.
 * 결론: IDENTITY 엔티티는 {@code persist() == INSERT} — write-behind 우회.
 */
public class IdentityGenerator implements IdentifierGenerator {

    private final JdbcTemplate jdbc;

    public IdentityGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isPostInsert() {
        return true;
    }

    /**
     * INSERT를 즉시 실행하고 DB 생성 키를 반환한다.
     * 반환된 키를 엔티티의 @SfsId 필드에 reflection으로 주입한다.
     *
     * @param entity 삽입할 엔티티 인스턴스
     * @param md     엔티티 메타데이터 (테이블명, 컬럼 순서, SQL 포함)
     * @return DB가 생성한 generated key (Number 타입)
     */
    @Override
    public Object generate(Object entity, EntityMetadata md) {
        // 학습 정점 ① 깨짐 함정: persist == INSERT (write-behind 우회)
        Object[] params = boundParams(entity, md);
        Number key = jdbc.updateAndReturnKey(md.insertSql(), params);
        try {
            md.idField().field().set(entity, convertKey(key, md.idField().javaType()));
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Cannot set @SfsId field after INSERT", e);
        }
        return key;
    }

    /**
     * generated key를 @SfsId 필드의 Java 타입으로 변환한다.
     * 학습 ORM 범위: Long / Integer / 기타(그대로 반환) 세 가지만 지원.
     */
    private Object convertKey(Number key, Class<?> idType) {
        if (idType == Long.class || idType == long.class) return key.longValue();
        if (idType == Integer.class || idType == int.class) return key.intValue();
        return key;
    }

    /**
     * INSERT SQL 바인딩 파라미터 배열 생성.
     * IDENTITY 전략은 id 컬럼이 INSERT SQL에 포함되지 않으므로 columns + manyToOnes FK 순서로만 바인딩.
     */
    private Object[] boundParams(Object entity, EntityMetadata md) {
        List<Object> params = new ArrayList<>();
        try {
            for (FieldMetadata col : md.columns()) {
                params.add(col.field().get(entity));
            }
            for (RelationMetadata rel : md.manyToOnes()) {
                Object related = rel.field().get(entity);
                params.add(related == null ? null : EntityMetadataAnalyzer.extractIdFieldValue(related, rel.targetEntity()));
            }
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Cannot read entity fields", e);
        }
        return params.toArray();
    }

}
