package com.choisk.sfs.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 클래스 로드 없이 ASM으로 파싱한 메타데이터.
 * <p>Spring {@code AnnotationMetadata}에 대응하되 record로 불변 표현.
 */
public record AnnotationMetadata(
        String className,
        String superClassName,
        List<String> interfaceNames,
        Set<String> annotationTypeNames,
        Map<String, Map<String, Object>> annotationAttributes,
        boolean isAbstract,
        boolean isInterface,
        boolean isAnnotation
) {
    public boolean hasAnnotation(String annotationClassName) {
        return annotationTypeNames.contains(annotationClassName);
    }

    public Map<String, Object> attributesFor(String annotationClassName) {
        return annotationAttributes.getOrDefault(annotationClassName, Map.of());
    }
}
