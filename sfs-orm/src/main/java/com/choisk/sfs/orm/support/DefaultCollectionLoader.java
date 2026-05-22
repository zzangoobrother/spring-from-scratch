package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;

import java.util.List;

/**
 * production CollectionLoader 구현.
 *
 * <p>책임 분담: SQL 실행은 {@link EntityPersister#findByForeignKey}가 캡슐화,
 * loader는 *대상 persister lookup + 위임*만. jdbc 의존 없음.
 *
 * <p>SfsEntityManagerFactory에서 setEmf 루프 완료 후 생성되므로
 * persisterOf(elementType) 조회가 안전하게 동작한다.
 */
public class DefaultCollectionLoader implements CollectionLoader {

    private final SfsEntityManagerFactory emf;

    public DefaultCollectionLoader(SfsEntityManagerFactory emf) {
        this.emf = emf;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                       Object fkValue, PersistenceContext ctx) {
        // SQL 실행은 EntityPersister에 위임 — loader는 persister lookup + 캐스팅만
        EntityPersister persister = emf.persisterOf(elementType);
        return (List<T>) persister.findByForeignKey(fkColumn, fkValue, ctx);
    }
}
