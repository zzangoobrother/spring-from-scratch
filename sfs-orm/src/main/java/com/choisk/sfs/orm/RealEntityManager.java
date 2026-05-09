package com.choisk.sfs.orm;

/**
 * SfsEntityManager 구현체 stub — D1 이후 본격 구현.
 * Factory와 같은 패키지에 위치하여 package-private getter 접근 가능.
 */
public class RealEntityManager implements SfsEntityManager {

    public RealEntityManager(SfsEntityManagerFactory emf) {
        /* D1+ */
    }

    @Override
    public void persist(Object entity) {
        throw new UnsupportedOperationException("G1+");
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        throw new UnsupportedOperationException("H1+");
    }

    @Override
    public void remove(Object entity) {
        throw new UnsupportedOperationException("K1");
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("K2");
    }

    @Override
    public <T> T merge(T entity) {
        throw new UnsupportedOperationException("K3");
    }

    @Override
    public boolean contains(Object entity) {
        throw new UnsupportedOperationException("D1+");
    }
}
