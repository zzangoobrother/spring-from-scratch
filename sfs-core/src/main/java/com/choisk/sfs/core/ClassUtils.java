package com.choisk.sfs.core;

import java.util.Map;

/**
 * 클래스 로딩과 타입 호환성 관련 유틸리티.
 * <p>Spring 원본: {@code org.springframework.util.ClassUtils}.
 */
public final class ClassUtils {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            double.class, Double.class,
            float.class, Float.class,
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            void.class, Void.class
    );

    private ClassUtils() {}

    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) return cl;
        cl = ClassUtils.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    public static Class<?> forName(String name, ClassLoader loader) throws ClassNotFoundException {
        Assert.hasText(name, "class name");
        ClassLoader cl = loader != null ? loader : getDefaultClassLoader();
        return Class.forName(name, false, cl);
    }

    public static boolean isAssignableValue(Class<?> type, Object value) {
        Assert.notNull(type, "type");
        if (value == null) return !type.isPrimitive();
        if (type.isPrimitive()) {
            return PRIMITIVE_WRAPPERS.get(type).isInstance(value);
        }
        return type.isInstance(value);
    }
}
