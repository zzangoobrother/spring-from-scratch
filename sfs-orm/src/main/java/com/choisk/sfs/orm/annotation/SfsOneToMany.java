package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 단방향 OneToMany 관계 — 컬렉션 필드에 적용.
 *
 * <p>spec § 3.1 정합: FetchType.LAZY only — EAGER collection은 MP-2-α 별도 mini-phase로 이월.
 * targetEntity는 generic erasure 자동 추출({@code ParameterizedType.getActualTypeArguments[0]}).
 *
 * <p>예시:
 * <pre>{@code
 * @SfsOneToMany(joinColumn = "user_id")
 * private List<Order> orders;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsOneToMany {

    /** lazy fetch 전략 — MP-2는 LAZY만 지원. */
    FetchType fetch() default FetchType.LAZY;

    /** 대상 테이블의 FK 컬럼명 (예: "user_id"). */
    String joinColumn();

    /** Fetch type — MP-2는 LAZY만 정의. EAGER는 MP-2-α 이월 박제. */
    enum FetchType { LAZY }
}
