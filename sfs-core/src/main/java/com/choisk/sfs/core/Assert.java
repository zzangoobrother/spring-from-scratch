package com.choisk.sfs.core;

/**
 * Spring의 {@code org.springframework.util.Assert}와 동등한 조건 검증 유틸.
 * <p>모든 메서드는 실패 시 {@link IllegalArgumentException}을 던진다.
 */
public final class Assert {

    private Assert() {}

    public static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    public static String hasText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must have text");
        }
        return value;
    }

    public static void isAssignable(Class<?> superType, Class<?> subType) {
        notNull(superType, "superType");
        notNull(subType, "subType");
        if (!superType.isAssignableFrom(subType)) {
            throw new IllegalArgumentException(
                    subType.getName() + " is not assignable to " + superType.getName());
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
