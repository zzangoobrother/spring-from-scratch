package com.choisk.sfs.orm.support;

import java.lang.reflect.Field;

/**
 * @SfsOneToMany 필드 분석 결과를 담는 불변 record.
 *
 * <p>spec § 3.2 정합. RelationMetadata와 분리 유지 — joinColumnName이 ManyToOne(내 테이블의 FK)과
 * OneToMany(대상 테이블의 FK)에서 의미가 다르므로 한 record로 묶으면 의미 오버로드 발생.
 *
 * @param field             컬렉션 필드 (예: User.orders)
 * @param elementType       컬렉션 element 타입 (ParameterizedType 추출 결과, 예: Order.class)
 * @param joinColumnName    대상 테이블의 FK 컬럼명 (예: "user_id")
 */
public record CollectionMetadata(
        Field field,
        Class<?> elementType,
        String joinColumnName
) { }
