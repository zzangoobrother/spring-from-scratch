package com.choisk.sfs.orm;

public interface SfsEntityManager {
    void persist(Object entity);              // G1+에서 구현
    <T> T find(Class<T> entityClass, Object primaryKey);   // H1+에서 구현 (null이면 not found)
    void remove(Object entity);               // K1에서 구현
    void flush();                             // K2에서 구현
    <T> T merge(T entity);                    // K3에서 구현
    boolean contains(Object entity);          // D1+에서 구현
    // close()는 사용자가 호출하지 않음 — TSM 콜백에서 자동 (SfsEntityManagerFactoryBean)
}
