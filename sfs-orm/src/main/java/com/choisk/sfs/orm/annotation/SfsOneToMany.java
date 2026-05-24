package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OneToMany 관계 — 컬렉션 필드에 적용.
 *
 * <p>매핑 모델은 {@code mappedBy} XOR {@code joinColumn} (정확히 하나).
 * <ul>
 *   <li>단방향(MP-2): {@code joinColumn} — 대상 테이블의 FK 컬럼명 직접 지정.</li>
 *   <li>양방향(MP-3): {@code mappedBy} — owning {@code @SfsManyToOne} 필드명. FK는 owning side가 보유.</li>
 * </ul>
 *
 * <p>spec § 3.1 정합: FetchType.LAZY only — EAGER collection은 MP-2-α 별도 mini-phase로 이월.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsOneToMany {

    /** lazy fetch 전략 — LAZY만 지원. */
    FetchType fetch() default FetchType.LAZY;

    /** 단방향: 대상 테이블의 FK 컬럼명 (예: "user_id"). mappedBy와 XOR. */
    String joinColumn() default "";

    /** 양방향: owning {@code @SfsManyToOne} 필드명 (예: "user"). joinColumn과 XOR. */
    String mappedBy() default "";

    /** cascade 전파 종류 — 기본 없음(JPA 기본값 정합). */
    SfsCascadeType[] cascade() default {};

    /** true이면 컬렉션에서 빠진 element를 DELETE (snapshot diff). */
    boolean orphanRemoval() default false;

    /** Fetch type — LAZY만 정의. EAGER는 MP-2-α 이월 박제. */
    enum FetchType { LAZY }
}
