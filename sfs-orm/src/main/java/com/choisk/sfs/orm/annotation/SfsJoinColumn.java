package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsJoinColumn {
    // FK 컬럼명 필수 — @SfsColumn과 달리 필드명 규칙이 없어 default 없음
    String name();
}
