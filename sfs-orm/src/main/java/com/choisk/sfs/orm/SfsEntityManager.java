package com.choisk.sfs.orm;

import java.util.List;

public interface SfsEntityManager {
    void persist(Object entity);
    <T> T find(Class<T> entityClass, Object primaryKey);   // null이면 not found
    void remove(Object entity);
    void flush();
    <T> T merge(T entity);
    boolean contains(Object entity);
    <T> List<T> findAll(Class<T> entityClass);   // 신설 — 전체 조회 (N+1 학습 시나리오 기반)
    // close()는 사용자가 호출하지 않음 — TSM 콜백에서 자동 (SfsEntityManagerFactoryBean)
}
