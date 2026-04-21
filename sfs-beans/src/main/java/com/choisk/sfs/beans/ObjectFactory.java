package com.choisk.sfs.beans;

/**
 * 3-level cache의 3차 팩토리가 구현하는 인터페이스.
 * <p>Spring 원본: {@code ObjectFactory<T>}.
 */
@FunctionalInterface
public interface ObjectFactory<T> {
    T getObject();
}
