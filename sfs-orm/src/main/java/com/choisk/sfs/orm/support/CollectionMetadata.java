package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * @SfsOneToMany 필드 분석 결과를 담는 불변 record.
 *
 * <p>spec § 3.2 정합. RelationMetadata와 분리 유지 — joinColumnName이 ManyToOne(내 테이블의 FK)과
 * OneToMany(대상 테이블의 FK)에서 의미가 다르므로 한 record로 묶으면 의미 오버로드 발생.
 *
 * @param field          컬렉션 필드 (예: User.orders)
 * @param elementType    컬렉션 element 타입 (ParameterizedType 추출 결과, 예: Order.class)
 * @param joinColumnName 대상 테이블의 FK 컬럼명 — 단방향=직접 / 양방향=owning @SfsJoinColumn에서 도출
 * @param mappedBy       양방향 owning @SfsManyToOne 필드명 ("" = 단방향)
 * @param cascadeTypes   cascade 전파 종류 집합
 * @param orphanRemoval  컬렉션에서 빠진 element를 DELETE할지 여부
 */
public record CollectionMetadata(
        Field field,
        Class<?> elementType,
        String joinColumnName,
        String mappedBy,
        Set<SfsCascadeType> cascadeTypes,
        boolean orphanRemoval
) {
    /** PERSIST 또는 ALL 포함 시 persist 전파. */
    public boolean cascadesPersist() {
        return cascadeTypes.contains(SfsCascadeType.PERSIST) || cascadeTypes.contains(SfsCascadeType.ALL);
    }

    /** REMOVE 또는 ALL 포함 시 remove 전파. */
    public boolean cascadesRemove() {
        return cascadeTypes.contains(SfsCascadeType.REMOVE) || cascadeTypes.contains(SfsCascadeType.ALL);
    }
}
