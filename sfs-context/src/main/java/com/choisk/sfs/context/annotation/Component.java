package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Component {
    /** 빈 이름 명시. 비어 있으면 BeanNameGenerator의 기본값 사용. */
    String value() default "";
}
