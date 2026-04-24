package com.choisk.sfs.core;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

/**
 * 애노테이션 메타-탐색 유틸.
 * <p>클래스에 직접 붙은 애노테이션뿐 아니라, 그 애노테이션이 다시
 * 메타-애노테이션으로 보유한 것들까지 재귀 탐색한다.
 * <p>예: {@code @Service}는 {@code @Component}를 메타-애노테이션으로 가지므로
 * {@code isAnnotated(SomeService.class, Component.class)}는 {@code true}를 반환한다.
 *
 * <p>Spring 원본: {@code org.springframework.core.annotation.AnnotationUtils}.
 */
public final class AnnotationUtils {

    private AnnotationUtils() {}

    /**
     * {@code clazz}가 {@code target} 애노테이션을 직접 또는 메타로 보유하는지 검사한다.
     *
     * @param clazz  검사 대상 클래스 (null 불가)
     * @param target 찾을 애노테이션 타입 (null 불가)
     * @return 직접 또는 메타-애노테이션 체인을 통해 보유하면 {@code true}
     */
    public static boolean isAnnotated(Class<?> clazz, Class<? extends Annotation> target) {
        Assert.notNull(clazz, "clazz");
        Assert.notNull(target, "target");
        return isAnnotatedRecursive(clazz.getAnnotations(), target, new HashSet<>());
    }

    private static boolean isAnnotatedRecursive(
            Annotation[] annotations,
            Class<? extends Annotation> target,
            Set<Class<? extends Annotation>> visited) {
        for (Annotation a : annotations) {
            Class<? extends Annotation> type = a.annotationType();
            if (type.equals(target)) return true;
            // 이미 방문한 애노테이션 타입은 건너뜀 — 사용자 정의 사이클(A↔B) 방어
            if (!visited.add(type)) continue;
            // JDK 내장 메타-애노테이션(@Retention, @Target 등)은 더 이상 탐색하지 않음
            if (type.getName().startsWith("java.lang.annotation.")) continue;
            if (isAnnotatedRecursive(type.getAnnotations(), target, visited)) return true;
        }
        return false;
    }
}
