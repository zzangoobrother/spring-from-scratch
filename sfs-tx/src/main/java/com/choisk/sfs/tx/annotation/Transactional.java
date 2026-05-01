package com.choisk.sfs.tx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 또는 클래스에 트랜잭션 경계를 부여한다.
 *
 * <p>{@code transactionManager}로 다중 TM 빈 환경에서 라우팅 가능.
 * {@code isolation}, {@code rollbackFor}는 시그니처만 박제 — 동작 검증은
 * 본 phase 비목표 (spec § 7 한계).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Transactional {

    /** 빈 이름. 비어있으면 type 기반 lookup으로 fallback. */
    String transactionManager() default "";

    Propagation propagation() default Propagation.REQUIRED;

    /** 시그니처만, 동작 미검증. */
    int isolation() default -1;

    /** 시그니처만, 동작은 default(RuntimeException rollback)와 동일. */
    Class<? extends Throwable>[] rollbackFor() default {};
}
