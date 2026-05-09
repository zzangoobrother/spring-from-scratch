package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsManyToOne {
    FetchType fetch() default FetchType.LAZY;   // F1.5 default LAZY

    enum FetchType { LAZY, EAGER }
}
