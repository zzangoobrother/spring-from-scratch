package com.choisk.sfs.orm;

public interface SfsEntityManager {
    void persist(Object entity);
    <T> T find(Class<T> entityClass, Object primaryKey);   // null이면 not found
    void remove(Object entity);
    void flush();
    <T> T merge(T entity);
    boolean contains(Object entity);
    // close()는 사용자가 호출하지 않음 — TSM 콜백에서 자동 (SfsEntityManagerFactoryBean)
}
