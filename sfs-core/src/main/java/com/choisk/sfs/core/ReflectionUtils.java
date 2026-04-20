package com.choisk.sfs.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * 리플렉션 래퍼 유틸. 상속 체인을 따라 필드/메서드를 탐색하고,
 * private 접근을 강제 개방한다.
 * <p>Spring 원본: {@code org.springframework.util.ReflectionUtils}.
 */
public final class ReflectionUtils {

    private ReflectionUtils() {}

    public static Field findField(Class<?> clazz, String name) {
        Assert.notNull(clazz, "clazz");
        Assert.hasText(name, "name");
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Assert.notNull(clazz, "clazz");
        Assert.hasText(name, "name");
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && (paramTypes.length == 0 || Arrays.equals(m.getParameterTypes(), paramTypes))) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static void doWithFields(Class<?> clazz, Consumer<Field> action) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                action.accept(f);
            }
            current = current.getSuperclass();
        }
    }

    public static void doWithMethods(Class<?> clazz, Consumer<Method> action) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                action.accept(m);
            }
            current = current.getSuperclass();
        }
    }

    public static void makeAccessible(Field field) {
        if (!field.canAccess(null) && Modifier.isPrivate(field.getModifiers())) {
            field.setAccessible(true);
        } else {
            field.setAccessible(true);
        }
    }

    public static void makeAccessible(Method method) {
        method.setAccessible(true);
    }

    public static Object invokeMethod(Method method, Object target, Object... args) {
        try {
            makeAccessible(method);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke method: " + method, e);
        }
    }

    public static Object getField(Field field, Object target) {
        try {
            makeAccessible(field);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read field: " + field, e);
        }
    }

    public static void setField(Field field, Object target, Object value) {
        try {
            makeAccessible(field);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set field: " + field, e);
        }
    }
}
