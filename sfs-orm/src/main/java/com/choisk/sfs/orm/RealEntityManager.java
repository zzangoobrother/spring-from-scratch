package com.choisk.sfs.orm;

import com.choisk.sfs.orm.exception.SfsPersistenceException;
import com.choisk.sfs.orm.support.DeleteAction;
import com.choisk.sfs.orm.support.EntityAction;
import com.choisk.sfs.orm.support.EntityKey;
import com.choisk.sfs.orm.support.EntityMetadata;
import com.choisk.sfs.orm.support.EntityPersister;
import com.choisk.sfs.orm.support.FieldMetadata;
import com.choisk.sfs.orm.support.IdentifierGenerator;
import com.choisk.sfs.orm.support.InsertAction;
import com.choisk.sfs.orm.support.PersistenceContext;
import com.choisk.sfs.orm.support.RelationMetadata;
import com.choisk.sfs.orm.support.UpdateAction;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

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
     * <p>IDENTITY 전략(post-insert): id는 INSERT 후 DB가 생성한 generated key에서 받는다.
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

    /**
     * 1차 캐시 우선 조회, miss 시 DB에서 SELECT.
     *
     * <p>학습 정점 ② 1 entity = 1 instance: identityMap에 등재된 인스턴스를 그대로 반환하므로
     * 같은 PK에 대해 여러 번 find()를 호출해도 항상 동일한 객체 참조를 반환한다.
     *
     * <p>identityMap 등재는 {@code buildRowMapper}가 수행하므로 find()는 dirty 체크 기준선인
     * snapshot만 추가로 등재한다. loadById 내부에서 buildRowMapper가 putEntity를 수행한 뒤
     * find()가 putSnapshot을 추가하는 2단계 구조.
     *
     * @param entityClass 조회할 엔티티 클래스 (@SfsEntity 붙은 클래스)
     * @param primaryKey  조회할 PK 값
     * @return 엔티티 인스턴스, 없으면 null
     * @throws SfsPersistenceException 등록되지 않은 엔티티 클래스인 경우
     */
    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        EntityMetadata md = emf.metadataOf(entityClass);
        if (md == null) throw new SfsPersistenceException("Unknown entity class: " + entityClass);

        EntityKey key = new EntityKey(entityClass, primaryKey);

        // 1차 캐시 hit — identityMap에 등재된 동일 인스턴스 반환 (학습 정점 ②)
        Object cached = context.getEntity(key);
        if (cached != null) return entityClass.cast(cached);

        // cache miss → DB SELECT (buildRowMapper 내부에서 identityMap putEntity 수행)
        EntityPersister persister = emf.persisterOf(entityClass);
        Object loaded = persister.loadById(primaryKey, context);
        if (loaded == null) return null;  // 행 없음

        // dirty 체크 기준선 snapshot 등재 — buildRowMapper는 putEntity만 수행, snapshot은 여기서 추가
        context.putSnapshot(key, captureSnapshot(loaded, md));
        return entityClass.cast(loaded);
    }

    /**
     * 엔티티 클래스에 해당하는 전체 행을 DB에서 SELECT해 List로 반환한다.
     *
     * <p>N+1 학습 시나리오의 진입점 — findAll() 후 each entity의 @SfsOneToMany 컬렉션 접근 시
     * 컬렉션 건수만큼 추가 SELECT가 발생하는 구조를 학습(M1+에서 박제).
     *
     * <p>1차 캐시를 거치지 않는다 — SELECT * 결과는 EntityPersister.findAll(context)에서
     * buildRowMapper를 통해 로드되며 context에 직접 등재하지 않음 (단순화).
     *
     * @param entityClass 조회할 엔티티 클래스 (@SfsEntity 붙은 클래스)
     * @return 전체 엔티티 목록 (없으면 빈 List)
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(Class<T> entityClass) {
        EntityPersister persister = emf.persisterOf(entityClass);
        return (List<T>) persister.findAll(context);
    }

    /**
     * 관리 엔티티를 삭제 대기 상태로 전환한다.
     *
     * <p>write-behind 패턴: 즉시 DELETE하지 않고 actionQueue에 DeleteAction을 등록한다.
     * 실제 DELETE SQL은 flush(K2)에서 실행된다.
     *
     * @param entity 삭제할 엔티티 인스턴스 (반드시 1차 캐시에 있는 관리 상태여야 함)
     * @throws IllegalArgumentException  미관리(detached/new) 엔티티를 넘긴 경우
     * @throws SfsPersistenceException   알 수 없는 엔티티 클래스이거나 @SfsId 필드 접근 실패 시
     */
    @Override
    public void remove(Object entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) {
            throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        }
        try {
            Object pk = md.idField().field().get(entity);
            EntityKey key = new EntityKey(entity.getClass(), pk);
            if (!context.contains(key)) {
                throw new IllegalArgumentException("Entity not managed: " + entity);
            }
            context.enqueueAction(new DeleteAction(entity, md));
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Cannot read @SfsId for remove", e);
        }
    }

    /**
     * 영속성 컨텍스트의 변경 사항을 DB에 반영한다.
     *
     * <p>Phase 1 — dirty check:
     * identityMap의 모든 관리 엔티티에 대해 snapshot과 현재 상태를 비교한다.
     * 변경된 컬럼이 있으면 UpdateAction을 actionQueue에 등록하고 snapshot을 갱신한다.
     *
     * <p>Phase 2 — action 실행:
     * actionQueue를 INSERT → UPDATE → DELETE 순으로 정렬 후 순차 실행한다.
     * 실행 완료 후 actionQueue를 비운다.
     *
     * <p>학습 정점 ③ 완성: flush()가 write-behind 큐의 실제 DB 반영 지점.
     */
    @Override
    public void flush() {
        // Phase 1: dirty check — identityMap의 모든 관리 엔티티를 순회
        for (var entry : context.identityMap().entrySet()) {
            EntityKey key = entry.getKey();
            Object entity = entry.getValue();
            EntityMetadata md = emf.metadataOf(key.entityClass());
            Object[] current = captureSnapshot(entity, md);
            Object[] original = context.getSnapshot(key);
            if (original == null) {
                // snapshot 없이 identityMap에 등록된 엔티티(LAZY fallback / EAGER 관계 로드 경로).
                // 방금 DB에서 읽은 상태가 기준선 — 현재 상태를 snapshot으로 등록하고 dirty 없음으로 처리.
                context.putSnapshot(key, current);
                continue;
            }
            BitSet dirty = computeDirty(current, original);
            if (!dirty.isEmpty()) {
                // 변경된 컬럼이 있으면 UpdateAction을 큐에 등록하고 snapshot 기준선 갱신
                context.enqueueAction(new UpdateAction(entity, md, dirty));
                context.putSnapshot(key, current);
            }
        }

        // Phase 2: action 실행 (INSERT → UPDATE → DELETE 순)
        var actions = new ArrayList<>(context.actionQueue());
        actions.sort((a, b) -> typeOrder(a) - typeOrder(b));
        for (EntityAction action : actions) {
            EntityPersister p = emf.persisterOf(action.entity().getClass());
            switch (action) {
                case InsertAction ia -> p.executeInsert(ia.entity());
                case UpdateAction ua -> p.executeUpdate(ua.entity(), ua.dirtyColumns());
                case DeleteAction da -> p.executeDelete(da.entity());
            }
        }
        context.clearActionQueue();
    }

    /**
     * 현재 상태와 snapshot을 비교해 변경된 인덱스의 비트를 켠다.
     *
     * @param current  현재 엔티티 필드 값 배열
     * @param original snapshot (flush 이전 기준선)
     * @return dirty 인덱스의 비트가 설정된 BitSet (변경 없으면 empty)
     */
    private BitSet computeDirty(Object[] current, Object[] original) {
        BitSet dirty = new BitSet();
        for (int i = 0; i < current.length; i++) {
            if (!Objects.equals(current[i], original[i])) dirty.set(i);
        }
        return dirty;
    }

    /**
     * EntityAction의 실행 우선순위를 반환한다.
     * INSERT(1) → UPDATE(2) → DELETE(3) 순으로 처리한다.
     *
     * @param a 정렬 기준을 구할 EntityAction
     * @return 순서 값 (낮을수록 먼저 실행)
     */
    private int typeOrder(EntityAction a) {
        return switch (a) {
            case InsertAction ia -> 1;
            case UpdateAction ua -> 2;
            case DeleteAction da -> 3;
        };
    }

    /**
     * detached 엔티티의 상태를 managed 인스턴스에 복사하고 managed 인스턴스를 반환한다.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>1차 캐시에서 managed 인스턴스를 먼저 찾는다.</li>
     *   <li>없으면 DB에서 find()로 조회한다.</li>
     *   <li>DB에도 없으면 {@link SfsPersistenceException}을 던진다 (PK null persist 폴백 없음 — spec 단순화).</li>
     *   <li>shallow copy: {@code md.columns()} + {@code md.manyToOnes()} 필드를 managed에 복사.</li>
     *   <li>snapshot 갱신: 다음 flush의 dirty 체크 기준선을 현재 상태로 맞춘다.</li>
     * </ol>
     *
     * <p>함정 박제: 호출자가 인자로 넘긴 detached 인스턴스는 여전히 detached.
     * 반드시 반환된 managed 인스턴스를 사용해야 한다.
     *
     * @param entity detached 엔티티 인스턴스
     * @return 1차 캐시에 등재된 managed 인스턴스 (entity와 다른 객체일 수 있음)
     * @throws SfsPersistenceException 알 수 없는 클래스, DB에 행 없음, reflection 접근 실패 시
     */
    @Override
    public <T> T merge(T entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) throw new SfsPersistenceException("Unknown entity class");

        try {
            Object pk = md.idField().field().get(entity);
            EntityKey key = new EntityKey(entity.getClass(), pk);

            Object managed = context.getEntity(key);
            if (managed == null) {
                managed = find(entity.getClass(), pk);
                if (managed == null) {
                    throw new SfsPersistenceException("Cannot merge: entity not in DB " + key);
                }
            }

            // Shallow copy (cascade 없음, EntityListener 없음 — spec 정합)
            for (FieldMetadata col : md.columns()) {
                col.field().set(managed, col.field().get(entity));
            }
            for (RelationMetadata rel : md.manyToOnes()) {
                rel.field().set(managed, rel.field().get(entity));
            }

            // snapshot 갱신 — 갱신 안 하면 다음 flush에서 dirty가 잘못 잡힘
            context.putSnapshot(key, captureSnapshot(managed, md));

            @SuppressWarnings("unchecked")
            T result = (T) managed;
            return result;
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Merge failed", e);
        }
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
