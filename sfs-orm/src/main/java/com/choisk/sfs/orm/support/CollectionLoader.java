package com.choisk.sfs.orm.support;

import java.util.List;

/**
 * 컬렉션 lazy init 시 호출되는 loader 인터페이스.
 *
 * WHY: D1에서 DefaultCollectionLoader가 실제 SQL을 실행하도록 구현할 예정.
 * 지금은 stub interface만 정의해 SfsPersistentList의 의존성 역전을 확보.
 */
public interface CollectionLoader {
    <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                Object fkValue, PersistenceContext ctx);
}
