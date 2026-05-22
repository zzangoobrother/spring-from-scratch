package com.choisk.sfs.orm.support;

import java.util.List;

/**
 * 컬렉션 lazy init 시 호출되는 loader 인터페이스.
 *
 * <p>DefaultCollectionLoader가 실제 SQL 실행을 담당하며, 이 인터페이스는
 * SfsPersistentList가 구현체에 직접 의존하지 않도록 의존성 역전 경계를 제공한다.
 */
public interface CollectionLoader {
    <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                Object fkValue, PersistenceContext ctx);
}
