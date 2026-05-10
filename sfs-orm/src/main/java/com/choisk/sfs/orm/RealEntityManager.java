package com.choisk.sfs.orm;

import com.choisk.sfs.orm.exception.SfsPersistenceException;
import com.choisk.sfs.orm.support.EntityKey;
import com.choisk.sfs.orm.support.EntityMetadata;
import com.choisk.sfs.orm.support.EntityPersister;
import com.choisk.sfs.orm.support.FieldMetadata;
import com.choisk.sfs.orm.support.IdentifierGenerator;
import com.choisk.sfs.orm.support.InsertAction;
import com.choisk.sfs.orm.support.PersistenceContext;
import com.choisk.sfs.orm.support.RelationMetadata;

/**
 * SfsEntityManager 구현체.
 *
 * <p>Factory와 같은 패키지에 위치하여 package-private getter(metadataOf/persisterOf) 접근 가능.
 *
 * <p>학습 정점 ① write-behind 박제:
 * persist() 호출 시 SEQUENCE 전략은 id를 미리 채우고 1차 캐시 등재 + snapshot 캡처 +
 * actionQueue InsertAction 등록만 수행한다. DB INSERT는 flush(K2)에서 발생한다.
 *
 * <p>설계 균열선: {@code gen.isPostInsert()} 한 줄이 IDENTITY(post-insert)와
 * SEQUENCE(pre-insert)를 분기하며, 두 전략의 생명주기가 완전히 다름을 표현한다.
 */
public class RealEntityManager implements SfsEntityManager {

    private final SfsEntityManagerFactory emf;
    // 1차 캐시 + write-behind 큐 + snapshot — persist/flush/find의 공유 상태
    private final PersistenceContext context = new PersistenceContext();

    public RealEntityManager(SfsEntityManagerFactory emf) {
        this.emf = emf;
    }

    /** 영속성 컨텍스트 getter — 테스트 및 flush(K2)에서 직접 접근. */
    public PersistenceContext context() {
        return context;
    }

    /**
     * 엔티티를 영속 상태로 전환한다.
     *
     * <p>SEQUENCE 전략(pre-insert):
     * <ol>
     *   <li>시퀀스 값으로 @SfsId 필드를 채운다.</li>
     *   <li>1차 캐시에 등재한다.</li>
     *   <li>snapshot을 캡처한다 (K2 dirty 체크 기준선).</li>
     *   <li>actionQueue에 InsertAction을 등록한다 (write-behind).</li>
     * </ol>
     *
     * <p>IDENTITY 전략(post-insert): G2에서 구현. id는 INSERT 후 generated key에서 받는다.
     *
     * @param entity 영속화할 엔티티 인스턴스
     * @throws SfsPersistenceException 알 수 없는 엔티티 클래스이거나 reflection 접근 실패 시
     */
    @Override
    public void persist(Object entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) {
            throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        }

        EntityPersister persister = emf.persisterOf(entity.getClass());
        IdentifierGenerator gen = persister.idGenerator();

        if (gen.isPostInsert()) {
            // IDENTITY: 즉시 INSERT (학습 정점 ① 깨짐 함정 박제)
            // generate()가 INSERT를 수행하고 entity.id를 DB generated key로 채움
            // actionQueue에 등재하지 않는다 — flush 시 재실행하면 중복 INSERT → UNIQUE 위반
            gen.generate(entity, md);
            // entity.id는 IdentityGenerator 내부에서 이미 normalize + set 완료
            // EntityKey 구성 시 raw Number(반환값)가 아닌 entity 필드 값을 읽어 타입 일관성 확보
            Object idValue;
            try {
                idValue = md.idField().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 읽기 실패 — EntityKey 구성 불가", e);
            }
            EntityKey key = new EntityKey(entity.getClass(), idValue);
            // 1차 캐시 등재 + snapshot 캡처 (SEQUENCE 분기와 공통 — find() cache hit 보장의 전제 조건)
            context.putEntity(key, entity);
            context.putSnapshot(key, captureSnapshot(entity, md));
            // actionQueue 추가 X — 이미 INSERT됨 (write-behind 우회)
        } else {
            // SEQUENCE: pre-insert — id를 먼저 받아 엔티티에 세팅
            Object id = gen.generate(entity, md);
            try {
                md.idField().field().set(entity, convertId(id, md.idField().javaType()));
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 세팅 실패", e);
            }
            // id를 세팅한 뒤 get()으로 읽어 EntityKey 구성 — getLong()은 Long wrapper에 실패
            Object idValue;
            try {
                idValue = md.idField().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 읽기 실패 — EntityKey 구성 불가", e);
            }
            EntityKey key = new EntityKey(entity.getClass(), idValue);
            // 1차 캐시 등재 + snapshot 캡처(dirty 체크 기준선) + write-behind 큐 등록
            context.putEntity(key, entity);
            context.putSnapshot(key, captureSnapshot(entity, md));
            context.enqueueAction(new InsertAction(entity, md));
        }
    }

    /**
     * id 값을 엔티티 @SfsId 필드의 Java 타입으로 변환한다.
     * SequenceGenerator는 Long을 반환하지만 필드 타입이 Integer일 수 있으므로 안전 변환.
     */
    private Object convertId(Object id, Class<?> idType) {
        if (id instanceof Number n) {
            if (idType == Long.class || idType == long.class) return n.longValue();
            if (idType == Integer.class || idType == int.class) return n.intValue();
        }
        return id;
    }

    /**
     * 엔티티의 현재 상태를 Object[] 배열로 캡처한다 (dirty 체크 기준선).
     *
     * <p>배열 레이아웃: [columns..., manyToOnes...] — K2 flush의 dirty 비교에서 동일한 순서 사용.
     * snapshot 누락 시 dirty 체크 자체가 불가능 → ORM 의미 붕괴.
     */
    private Object[] captureSnapshot(Object entity, EntityMetadata md) {
        Object[] snap = new Object[md.columns().size() + md.manyToOnes().size()];
        int idx = 0;
        try {
            for (FieldMetadata col : md.columns()) {
                snap[idx++] = col.field().get(entity);
            }
            for (RelationMetadata rel : md.manyToOnes()) {
                snap[idx++] = rel.field().get(entity);
            }
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("snapshot 캡처 실패", e);
        }
        return snap;
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        throw new UnsupportedOperationException("H1");
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

    /**
     * 엔티티가 1차 캐시에 존재하는지 확인한다.
     *
     * @param entity 확인할 엔티티 인스턴스
     * @return 1차 캐시에 등재되어 있으면 true, reflection 접근 실패 시 false
     */
    @Override
    public boolean contains(Object entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) return false;
        try {
            Object idValue = md.idField().field().get(entity);
            return context.contains(new EntityKey(entity.getClass(), idValue));
        } catch (IllegalAccessException e) {
            return false;
        }
    }
}
