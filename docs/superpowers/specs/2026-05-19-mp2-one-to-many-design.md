# MP-2 mini-phase 설계 — `@SfsOneToMany` 단방향 + Collection Lazy

> **본 문서 위치:** Phase 4 ORM 완전 마감 (2026-05-17, main `0336875` PR #23) 이후 첫 mini-phase.
> Phase 4 spec § 9 박제 *MP-2 (1순위)*의 실행 단계. brainstorming 세션 결정(Q1~Q7) 기반.
>
> **선행 의존:** Phase 4 (ORM 핵심) — main 머지 완료, 회귀 304 PASS / 0 FAIL.
> **연관 plan:** `docs/superpowers/plans/2026-05-XX-mp2-one-to-many.md` (writing-plans 스킬로 작성 예정).
> **브랜치:** `feat/mp2-one-to-many` (main `0336875`에서 분기 예정).

---

## 0. 프로젝트 개요

### 0.1 한 줄 요약

단방향 `@SfsOneToMany` 어노테이션 + `SfsPersistentList<T>` collection wrapper (Hibernate 본가 패턴, byte-buddy 미사용)로 **collection lazy 발화**와 **N+1 자연 노출** 두 학습 정점을 박제한다. cascade · orphanRemoval은 의식적으로 미도입 — *함정의 출발점*만 만들고 *해결*은 후속 MP-3가 회수 (*학습 짝패 박제* 시간축 확장).

### 0.2 학습 정점 2개

| 정점 | 박제 메커니즘 | 회수 위치 |
|---|---|---|
| **① collection lazy 발화** | `SfsPersistentList<T>`의 *첫 List 메서드 호출 시점*에 `SELECT … WHERE fk = ?` 1회 발생 + 내부 ArrayList에 결과 채우기 | 단위 `SfsPersistentListTest` + 통합 `OneToManyLazyIntegrationTest` |
| **② N+1 자연 노출** | `em.findAll(User.class)`로 N명 부모 조회 (1 SELECT) + for-loop으로 each `user.getOrders().size()` (N SELECT) → 총 **N+1 SELECT** | demo console (눈) + 통합 `NPlusOneIntegrationTest`의 spy JdbcTemplate SQL 카운트 (회귀) |

### 0.3 학습 짝패 박제 — cascade 미도입 자연 노출

```
MP-2 (cascade 미도입)                    MP-3 (cascade 도입)
─────────────────────                    ─────────────────────
user.getOrders().add(o)                  user.getOrders().add(o)
em.persist(user)         →  o INSERT 안됨    em.persist(user)  →  cascade PERSIST
                                                              →  o INSERT
em.persist(o) 별도 호출   →  o INSERT됨       (학습자가 cascade 필요성 체감 → MP-3로 해결)
```

Phase 4 학습 정점 ① 정상/깨짐 4분면 박제와 *동형 구조* — 다만 시간축이 다름:
- Phase 4의 4분면은 *한 phase 내*에서 양면 박제 (E1/E2/G1/G2)
- MP-2의 짝패는 *MP-2 → MP-3 phase 간* 박제 (증상 → 해결)

> **mini-phase 시리즈는 *학습 짝패의 시간축 확장***이라는 메타 패턴.

### 0.4 6단계 로드맵 위치

```
Phase 1 IoC  ──► Phase 2 AOP  ──► Phase 3 Tx  ──► Phase 4 ORM  ──► [MP-2]  ──► MP-3 ──► MP-4 ──► ...
                                                                      ↑
                                                                  본 mini-phase
```

본가 Spring 학습 로드맵에서 *컬렉션 관계*는 ORM 핵심 직후의 자연 후속. MP-2는 그 *첫 mini-phase*로, 컬렉션 도메인의 *고유 패턴* (POJO wrapper, lazy init의 method 진입점 강제)을 박제.

### 0.5 자율 판단 / 자연 노출 예상 박제

mini-phase 단위에서도 *implementer 자율적 판단*([[feedback_implementer_autonomy]] 메모리 정합)이 발생할 수 있다. 본 spec은 다음을 *명시 박제*해 implementer가 자율 판단의 근거로 활용한다:

| 잠재 자율 판단 영역 | 본 spec의 입장 |
|---|---|
| `SfsPersistentList`의 메서드 N개를 한 번에 위임할지, 메서드별 명시할지 | 메서드별 명시 우선 — *모든 메서드가 lazy init trigger*가 학습 정점이라 *어느 메서드를 호출해도 동일 시점*이 박제 표현. `AbstractList` 상속 대신 `List<T>` 직접 implement 권장 |
| `findAll`의 *대상 entity가 OneToMany 보유 시* 각 row의 collection field 채우기 | LAZY stub 주입 (Phase 4 ManyToOne LAZY와 동일 패턴) — eager 채우기는 *N+1 박제 자체를 깨뜨림* |
| `EntityMetadata` record의 oneToManies 필드 추가 시 *기존 4개 필드 다음 위치인지 manyToOnes 인접 위치인지* | `manyToOnes` 바로 다음 — *관계 도메인끼리 인접*이 가독성 우선 |

---

## 1. 의사결정 로그

### 1.1 Q1~Q7 결정 + trade-off 박제

| Q | 결정 | 선택지 미선택 사유 |
|---|---|---|
| Q1 | 학습 정점 2개 (lazy 발화 + N+1) | 정점 1개(B): 1순위 후보 가치 절반 / 정점 3개(C, PersistentBag·Set 의미론): 인플레이션, phase 규모 폭창 |
| Q2 | `List<T>` 단일 (Bag 패턴) | Set / Collection abstraction: 의미론 학습은 mini-phase 외 박제 가능, 1차 범위 단순화 우선 |
| Q3 | `SfsPersistentList<T> implements List<T>` POJO wrapper (Hibernate 본가) | byte-buddy 확장(B): 컬렉션 메서드 다수 인터셉트 부담, 디버깅 난이도 / JDK Proxy(C): 학습 가치 보너스 미흡 |
| Q4 | demo console 시연 + spy JdbcTemplate SQL 카운트 | demo만(B): 회귀 박제 부재 / 테스트만(C): 학습자 체감 부족 |
| Q5 | cascade · orphanRemoval *모두 MP-3에 위임* | cascade=PERSIST 포함(B): MP-3 독립성 약화, *학습 짝패* 박제 약화 / 모두 포함(C): mini-phase 인플레이션 |
| Q6 | generic erasure 자동 추출 (`ParameterizedType.getActualTypeArguments`) | targetEntity 명시(A): JPA 본가 default 아님 / 양측 지원(C): fallback 분기 코드 복잡도 |
| Q7 | `findAll(Class<T>)` 도입 + 정확한 N+1 박제 | 명시 PK 변형 N+1(B): 모양 부정확 / 다층 LAZY 중첩(C): mini-phase 인플레이션 |

### 1.2 Phase 4 결정 *의식적 번복* 박제 (Q7)

Phase 4 spec § 0.3 결정 *list 조회 미지원 → N+1 폭발 위험 없음 보장*. MP-2는 정반대 결정: *N+1 자연 노출이 학습 정점*이므로 `findAll(Class<T>)` 도입.

> **메타 패턴 박제**: mini-phase는 *상위 phase의 결정을 재고할 수 있다*. 향후 모든 mini-phase에 동일 자유. spec 작성 시 *재고 사유*를 명시 박제 의무.

### 1.3 채택 안 = Hibernate 본가 정합

3가지 grand approach 중 *Hibernate 본가 정합* 채택 (brainstorming의 grand approach A):

- **장점**: 컴포넌트 boundary가 본가 검증된 패턴과 일치 → 학습자가 *본가 코드와 mapping* 가능 / Phase 4 인프라 *대부분 재사용* (LazyTargetLoader → CollectionLoader 동형 패턴 등)
- **단점**: `SfsPersistentList`의 List 메서드 보일러플레이트 (위임만 ~30 메서드)
- **trade-off 박제**: 보일러플레이트는 *컬렉션 도메인 고유 비용*. byte-buddy로 줄이는 대안(grand approach C)은 *디버깅 난이도 + 인터셉트 비용*으로 더 비쌈

---

## 2. 아키텍처 & 모듈 구조

### 2.1 모듈 의존 (불변)

```
sfs-samples ──► sfs-orm ──► sfs-tx ──► sfs-aop ──► sfs-beans ──► sfs-core
                  │
                  └─► byte-buddy  (entity proxy에만, collection wrapper에는 미사용)
```

byte-buddy 의존성 *유지* (Phase 4 LazyProxyFactory가 그대로 사용). `SfsPersistentList`는 *byte-buddy 없이* 직접 작성 — 도메인 분리 박제.

### 2.2 신설 파일 (production)

```
sfs-orm/src/main/java/com/choisk/sfs/orm/
├── annotation/
│   └── SfsOneToMany.java                   (신설 — FetchType.LAZY only, joinColumn 속성)
└── support/
    ├── SfsPersistentList.java              (신설 — List<T> implements, ~150 LOC)
    ├── CollectionMetadata.java             (신설 — record, ~10 LOC)
    ├── CollectionLoader.java               (신설 — interface, ~5 LOC)
    ├── DefaultCollectionLoader.java        (신설 — interface 구현, ~30 LOC)
    └── (수정) EntityMetadata.java           (oneToManies: List<CollectionMetadata> + selectAllSql: String 필드 2개 추가)
    └── (수정) EntityMetadataAnalyzer.java   (@SfsOneToMany 분기 + generic 추출 + fail-fast 3종 + buildSelectAllSql 헬퍼)
    └── (수정) EntityPersister.java          (buildRowMapper에 oneToManies 채우기 + findAll + findByForeignKey 2 메서드 신설)
    └── (수정) RealEntityManager.java        (findAll API 추가)
    └── (수정) SfsEntityManager.java         (findAll 시그니처 추가)
    └── (수정) SfsEntityManagerFactory.java  (collectionLoader 빈 + DefaultCollectionLoader 노출)
```

> **`RelationMetadata`는 의도적으로 분리 유지** (수정 없음). `joinColumnName`이 ManyToOne(*내 테이블의 FK*)과 OneToMany(*대상 테이블의 FK*)에서 의미가 다르므로 한 record로 묶으면 *의미 오버로드* 발생. 본가 Hibernate도 `ToOne` / `Collection` metadata 분리.

### 2.3 신설 파일 (test 전용 인프라)

```
sfs-orm/src/test/java/com/choisk/sfs/orm/integration/
└── SqlCountingJdbcTemplate.java            (신설 — spy wrapper, SQL 실행 횟수 카운트, ~40 LOC)
```

production JdbcTemplate은 *변경 0* — spy는 *테스트 전용 서브클래스*. AbstractOrmIntegrationTest의 datasource 빈에 inject.

### 2.4 demo 도메인 확장

```
sfs-samples/src/main/java/com/choisk/sfs/samples/orm/domain/
└── (수정) User.java                          (orders: List<Order> + @SfsOneToMany 추가)

sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/
└── (수정) UserService.java                   (dumpAllUserOrders / describeUserOrders / tryAddOrderWithoutCascade)
└── (수정) OrmDemoApplication.java            (DH/DI/DJ 시나리오 3건 추가)
```

schema 변경 *0* — `orders.user_id`는 Phase 4 단계에서 이미 존재.

---

## 3. 핵심 컴포넌트

### 3.1 `@SfsOneToMany` 어노테이션

```java
@Retention(RUNTIME) @Target(FIELD)
public @interface SfsOneToMany {
    FetchType fetch() default FetchType.LAZY;   // LAZY only (EAGER 미지원, 학습 정점 집중)
    String joinColumn();                         // 대상 테이블의 FK 컬럼명 (예: "user_id")
    // targetEntity 없음 — generic 자동 추출 (Q6)
    enum FetchType { LAZY }
}
```

**EAGER 미지원 결정 박제** — 학습 정점 1(lazy 발화) 집중 + MP-2 범위 명확. EAGER collection은 *MP-2-α 별도 mini-phase* 후보로 § 9 박제.

### 3.2 `CollectionMetadata` record

```java
public record CollectionMetadata(
    Field field,                                 // List<Order> orders
    Class<?> elementType,                        // Order.class (ParameterizedType 추출)
    String joinColumnName                        // "user_id" — 대상 테이블의 FK
) { }
```

`RelationMetadata`와 분리 — 의미 차이 박제 (§ 2.2 참고).

### 3.3 `EntityMetadataAnalyzer` 확장

```java
// doAnalyze() 4단계 (연관 필드 수집) 추가 분기
else if (f.isAnnotationPresent(SfsOneToMany.class)) {
    validateOneToMany(f);                        // fail-fast 신설
    SfsOneToMany rel = f.getAnnotation(SfsOneToMany.class);
    Class<?> elementType = extractGenericType(f); // ParameterizedType.getActualTypeArguments[0]
    oneToManies.add(new CollectionMetadata(f, elementType, rel.joinColumn()));
}

// fail-fast 3종
private void validateOneToMany(Field f) {
    if (!List.class.isAssignableFrom(f.getType())) {
        throw new SfsEntityMappingException(
            "@SfsOneToMany field '" + f.getName() 
            + "' must be List<T> (other types not supported in MP-2)");
    }
    if (!(f.getGenericType() instanceof ParameterizedType)) {
        throw new SfsEntityMappingException(
            "@SfsOneToMany field '" + f.getName() 
            + "' must have generic type parameter");
    }
    // elementType 비엔티티 검증은 extractGenericType 내부에서 처리
}

private Class<?> extractGenericType(Field f) {
    ParameterizedType pt = (ParameterizedType) f.getGenericType();
    Class<?> elementType = (Class<?>) pt.getActualTypeArguments()[0];
    if (!elementType.isAnnotationPresent(SfsEntity.class)) {
        throw new SfsEntityMappingException(
            "@SfsOneToMany element type " + elementType.getSimpleName() 
            + " is not annotated with @SfsEntity");
    }
    return elementType;
}
```

> **fail-fast 패턴 박제**: Phase 4의 `validateManyToOne` 동형 패턴. 부팅 시 (analyze 호출 시) 즉시 던짐 → 런타임 lazy init 시점에는 검증 안 함 = *런타임 신뢰성 박제*.

### 3.4 `SfsPersistentList<T> implements List<T>` (핵심)

```java
public class SfsPersistentList<T> implements List<T> {
    
    private final Class<T> elementType;          // Order.class
    private final Object ownerPk;                 // User.id (FK 값)
    private final String joinColumnName;          // "user_id"
    private final CollectionLoader loader;        // lazy init callback
    private final PersistenceContext context;     // element들 identityMap 등록
    private List<T> delegate;                     // null = uninitialized
    
    public SfsPersistentList(Class<T> elementType, Object ownerPk, String joinColumnName,
                              CollectionLoader loader, PersistenceContext context) {
        this.elementType = elementType;
        this.ownerPk = ownerPk;
        this.joinColumnName = joinColumnName;
        this.loader = loader;
        this.context = context;
    }
    
    /** 첫 메서드 호출 시 1회 SELECT — 학습 정점 ① 핵심. */
    private void initialize() {
        if (delegate != null) return;            // 1차 호출 후 캐시 hit
        if (context.isClosed()) {                // PC 닫힌 후 호출 시 예외
            throw new SfsLazyInitializationException(
                elementType.getSimpleName() + "#" + ownerPk + ".collection");
        }
        delegate = loader.loadCollection(elementType, joinColumnName, ownerPk, context);
    }
    
    /** 테스트 헬퍼 — 초기화 여부 박제용 (단위 테스트가 호출) */
    public boolean isInitialized() { return delegate != null; }
    
    // ─── List<T> 위임 (모든 메서드가 initialize() 트리거) ──────────────
    @Override public int size() { initialize(); return delegate.size(); }
    @Override public boolean isEmpty() { initialize(); return delegate.isEmpty(); }
    @Override public boolean contains(Object o) { initialize(); return delegate.contains(o); }
    @Override public Iterator<T> iterator() { initialize(); return delegate.iterator(); }
    @Override public Object[] toArray() { initialize(); return delegate.toArray(); }
    @Override public <U> U[] toArray(U[] a) { initialize(); return delegate.toArray(a); }
    @Override public boolean add(T e) { initialize(); return delegate.add(e); }
    @Override public boolean remove(Object o) { initialize(); return delegate.remove(o); }
    @Override public boolean containsAll(Collection<?> c) { initialize(); return delegate.containsAll(c); }
    @Override public boolean addAll(Collection<? extends T> c) { initialize(); return delegate.addAll(c); }
    @Override public boolean addAll(int i, Collection<? extends T> c) { initialize(); return delegate.addAll(i, c); }
    @Override public boolean removeAll(Collection<?> c) { initialize(); return delegate.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { initialize(); return delegate.retainAll(c); }
    @Override public void clear() { initialize(); delegate.clear(); }
    @Override public T get(int i) { initialize(); return delegate.get(i); }
    @Override public T set(int i, T e) { initialize(); return delegate.set(i, e); }
    @Override public void add(int i, T e) { initialize(); delegate.add(i, e); }
    @Override public T remove(int i) { initialize(); return delegate.remove(i); }
    @Override public int indexOf(Object o) { initialize(); return delegate.indexOf(o); }
    @Override public int lastIndexOf(Object o) { initialize(); return delegate.lastIndexOf(o); }
    @Override public ListIterator<T> listIterator() { initialize(); return delegate.listIterator(); }
    @Override public ListIterator<T> listIterator(int i) { initialize(); return delegate.listIterator(i); }
    @Override public List<T> subList(int from, int to) { initialize(); return delegate.subList(from, to); }
}
```

**모든 메서드가 trigger** — Hibernate의 write-only optimization 미도입 (Q1 정점 2개 집중, 인플레이션 회피). 학습자가 *어떤 List 메서드를 호출해도 동일 시점에 SELECT 발생*을 체감.

### 3.5 `EntityPersister` 확장 + `findAll` / `findByForeignKey` API

#### EntityMetadata 보강 (selectAllSql 필드 추가)

`EntityMetadata` record에 `selectAllSql` 필드 1개 추가. `EntityMetadataAnalyzer.doAnalyze`에서 `buildSelectAllSql(tableName, idField, columns, manyToOnes)` 호출로 부팅 시 1회 생성 — Phase 4의 *SQL 사전 빌드 패턴* 그대로.

```java
// EntityMetadata 새 필드: selectAllSql (insertSql/selectByIdSql/deleteSql 옆)
// 형식: "SELECT id, name, email FROM users"   (WHERE 절 없음)
```

#### buildRowMapper 확장 (oneToManies 채우기)

```java
private RowMapper<Object> buildRowMapper(PersistenceContext context) {
    return (rs, rowNum) -> {
        // ... 기존 Phase 4 로직 (id/columns/manyToOnes) 그대로 ...
        
        // 신설: @SfsOneToMany 필드 — SfsPersistentList stub 주입 (DB 호출 0)
        for (CollectionMetadata col : md.oneToManies()) {
            Object ownerPk = md.idField().field().get(instance);
            SfsPersistentList<?> proxy = new SfsPersistentList<>(
                col.elementType(), ownerPk, col.joinColumnName(), 
                emf.collectionLoader(), context);
            col.field().set(instance, proxy);
        }
        return instance;
    };
}
```

#### findAll / findByForeignKey 신설 (책임 분담: persister가 모든 SQL 캡슐화)

```java
// findAll(Class<T>) 신설 — SELECT * FROM <table>
public List<Object> findAll(PersistenceContext context) {
    return jdbc.query(md.selectAllSql(), buildRowMapper(context));
    // 각 row → buildRowMapper → context.putEntity (cache hit이면 재사용, 정점 ② 정합)
}

// findByForeignKey 신설 — SELECT … WHERE fk = ? (DefaultCollectionLoader가 호출)
public List<Object> findByForeignKey(String fkColumn, Object fkValue, PersistenceContext context) {
    String sql = "SELECT " + columnsForSelectFromSelectAllSql() 
               + " FROM " + md.tableName() 
               + " WHERE " + fkColumn + " = ?";
    return jdbc.query(sql, buildRowMapper(context), fkValue);
    // 컬럼 리스트는 selectAllSql 재활용 — SQL 캐싱 일관성 (구현 디테일은 plan task)
}
```

> **`buildRowMapper`의 cache hit 재사용 보강** — Phase 4의 EAGER 분기에 이미 있는 패턴(`getEntity → 없으면 loadById → putEntity`)을 *findAll/findByForeignKey의 각 row 매핑*에도 *명시 박제*. 정점 ② 1 entity = 1 instance 보장. (구현 디테일 — 컬럼 리스트 추출 방식, cache hit 재사용 로직은 plan task에서 박제)

### 3.6 `CollectionLoader` 인터페이스

```java
public interface CollectionLoader {                  // Phase 4 LazyTargetLoader 동형 패턴
    <T> List<T> loadCollection(Class<T> elementType, String fkColumn, 
                                Object fkValue, PersistenceContext ctx);
}

public class DefaultCollectionLoader implements CollectionLoader {
    private final SfsEntityManagerFactory emf;
    
    public DefaultCollectionLoader(SfsEntityManagerFactory emf) { this.emf = emf; }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> loadCollection(Class<T> elementType, String fkColumn, 
                                       Object fkValue, PersistenceContext ctx) {
        // 책임 분담: SQL 실행은 persister가 캡슐화, loader는 *대상 persister lookup + 위임*만
        EntityPersister persister = emf.persisterOf(elementType);
        return (List<T>) persister.findByForeignKey(fkColumn, fkValue, ctx);
    }
}
```

**Phase 4 `LazyTargetLoader` 동형 패턴** — *production-interface 분리*로 *단위 테스트가 fake loader로 SfsPersistentList 단독 검증 가능*.

> **책임 분담 박제**: `DefaultCollectionLoader`는 *jdbc 의존 없음* — `EntityPersister.findByForeignKey`가 SQL 실행 캡슐화. loader는 *어느 persister가 처리할지*만 결정. Phase 1A gap의 *책임 분담 패턴*과 동형 (BPP/EarlyProxyCache 책임 분리 박제와 같은 모양).

---

## 4. 데이터 플로우

### 4.1 `em.find(User.class, 1L)` — collection LAZY stub 주입

```
1. cache hit? → identityMap[(User,1)] 반환 (Phase 4 정합)
2. cache miss → EntityPersister.loadById(1, context)
   ├─ SELECT id, name, email FROM users WHERE id = 1            (1 SELECT)
   ├─ buildRowMapper:
   │   ├─ id/name/email 채움
   │   ├─ manyToOnes 채움 (Phase 4 패턴 그대로)
   │   └─ oneToManies 채움 (신설):
   │       └─ user.orders = new SfsPersistentList<>(
   │             Order.class, 1L, "user_id", loader, context)
   │           (DB 호출 0 — stub만 주입)
   ├─ context.putEntity((User,1), user)
   └─ context.putSnapshot((User,1), values)
```

**collection field는 LAZY 스텁만** — 첫 List 메서드 호출 전까지 SELECT 0. *Phase 4 ManyToOne LAZY와 동일 시간 분할*.

### 4.2 `user.getOrders().size()` — SfsPersistentList lazy init

```
SfsPersistentList.size()
└─ initialize()
   ├─ delegate != null? → return (1차 호출 후 캐시)
   ├─ context.isClosed()? → throw SfsLazyInitializationException
   └─ loader.loadCollection(Order.class, "user_id", 1L, context)
       ├─ SELECT id, amount, status, created_at, user_id 
       │   FROM orders WHERE user_id = 1                      (1 SELECT)
       └─ each row → buildRowMapper:
           ├─ EntityKey relKey = (Order, row.id)
           ├─ context.getEntity(relKey)? → 있으면 재사용, 없으면 new Order
           ├─ context.putEntity(relKey, order)
           └─ list.add(order)
       └─ return List<Order>
```

> **정점 ① 회수 시점**: 첫 List 메서드 호출 시점에 정확히 *1 SELECT*. `iterator()/contains(x)/get(0)/size()` 어느 것이든 동일 시점.
>
> **정점 ② 정합**: collection element들이 모두 identityMap에 등재 → `findAll`이 반환한 instance와 collection 안의 instance가 *같은 PK는 같은 인스턴스* 보장.

### 4.3 N+1 시나리오 — `findAll` + for-loop

```
List<User> users = em.findAll(User.class);          // 1 SELECT  (SELECT * FROM users)
                                                     // → users 모두 identityMap 등재
for (User u : users) {                              // 예: N = 3
    System.out.println(u.getOrders().size());       // 각 user의 List<Order> lazy init
}                                                    // → N개 SELECT (SELECT ... WHERE user_id = ?)

// 총 N + 1 = 4 SELECT
```

**spy `SqlCountingJdbcTemplate`이 통합 테스트에서 정확한 카운트 박제**. demo console은 SQL 로그 출력으로 학습자에게 *눈으로 N+1을 보여줌*.

---

## 5. Demo 시나리오 — `OrmDemoApplication` 확장

### 5.1 도메인 확장 (User에 OneToMany 필드 추가)

```java
@SfsEntity(name = "users")
public class User {
    @SfsId
    @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "users_seq")
    private Long id;
    @SfsColumn private String name;
    @SfsColumn private String email;
    
    @SfsOneToMany(joinColumn = "user_id")        // ← 신설 (default LAZY)
    private List<Order> orders;                   // ← 신설 (SfsPersistentList stub 주입 대상)
    
    // getters/setters
}
```

schema 변경 *0* — `orders.user_id`는 Phase 4에서 이미 존재. *Phase 4 도메인의 자연 확장*이 MP-2의 demo 가치.

### 5.2 신설 시연 3건 (Phase 4 DA~DG 그대로 보존)

| # | 시나리오 | 박제 정점 | service 메서드 |
|---|---|---|---|
| **DH** | `findAll(User)` (사전 3명 User 생성) + for-loop `u.getOrders().size()` | ② N+1 자연 노출 (총 4 SELECT, demo console에 SQL 로그 출력) | `UserService.dumpAllUserOrders` |
| **DI** | `user.getOrders().iterator().next()` 첫 호출 → 정확히 1 SELECT 발생 | ① collection lazy 발화 시점 (어느 메서드든 동일 시점) | `UserService.describeUserOrders` |
| **DJ** | `user.getOrders().add(newOrder)` + `persist(user)` only → `newOrder` INSERT 안 됨 → 별도 `persist(newOrder)` 필요 | 학습 짝패 (cascade 부재 자연 노출) | `UserService.tryAddOrderWithoutCascade` |

### 5.3 Console output 시연 (DJ 대표 발췌)

```
[DJ] cascade 미도입 자연 노출 시연
─────────────────────────────────────
  user.getOrders().add(newOrder)  → list 메서드 호출 (lazy init 발생, SELECT)
  em.persist(user)                 → user는 이미 managed, no-op
  em.flush()                       → INSERT/UPDATE 없음 ← newOrder는 단방향이라 미반영
  
  ❌ DB 확인: SELECT COUNT(*) FROM orders WHERE id = newOrder.id → 0
  
  → 해결: em.persist(newOrder) 별도 호출 필요 (MP-3에서 cascade=PERSIST로 자동화 예정)
```

---

## 6. 에러 처리 정책

### 6.1 신설 예외 0개 — Phase 4 인프라 완전 재사용

| 시나리오 | 예외 (모두 Phase 4 기존) | 메시지 패턴 |
|---|---|---|
| `SfsPersistentList` 메서드 호출 시 PC closed | `SfsLazyInitializationException` | `"Order#1.collection"` |
| fail-fast: `List` 외 타입 (예: `Set<Order>`) | `SfsEntityMappingException` | `"@SfsOneToMany field 'tags' must be List<T> (other types not supported in MP-2)"` |
| fail-fast: generic 미명시 (raw `List`) | `SfsEntityMappingException` | `"@SfsOneToMany field 'orders' must have generic type parameter"` |
| fail-fast: elementType 비엔티티 | `SfsEntityMappingException` | `"@SfsOneToMany element type Tag is not annotated with @SfsEntity"` |
| `SfsPersistentList` element 로드 중 DB 에러 | `SfsPersistenceException` | `"Collection load failed: orders WHERE user_id = 1"` |

> **예외 도메인 추상 안정성**: Phase 4의 4계층(`SfsPersistenceException` 루트 + Lazy/Tx/Mapping)이 mini-phase 추가 도메인에도 그대로 적합 → *적정 추상도 박제*. 본 mini-phase가 *Phase 4 예외 계층의 검증*.

### 6.2 fail-fast 시점

모든 fail-fast는 *EntityMetadataAnalyzer.analyze()* 시점 (= 부팅 시 EMF 빈 초기화 단계). 런타임에는 검증 안 함 = *런타임 신뢰성*. Phase 4 `validateManyToOne` 동형 패턴.

---

## 7. 테스트 전략 + 회귀 카운트 추정

### 7.1 TDD 적용/제외 (Phase 4 패턴 정합)

| 영역 | TDD 적용/제외 | 사유 |
|---|---|---|
| `@SfsOneToMany` 어노테이션 | 제외 | 마커, 통합이 회귀망 |
| `CollectionMetadata` record | 제외 | DTO, 통합 + 단위 테스트가 자연 검증 |
| `EntityMetadataAnalyzer` 분기 + fail-fast | **적용** | 분기 4종 (성공 1 + 실패 3) |
| `SfsPersistentList<T>` | **적용** | 라이프사이클 + 모든 메서드 trigger + closed 예외 + identity 보장 |
| `EntityPersister.findAll` | **적용** | 새 SQL 패턴 |
| `EntityPersister.buildRowMapper` collection 채우기 | **적용** | 분기 추가 |
| `DefaultCollectionLoader` | **적용** | SQL 패턴 박제 |
| `SqlCountingJdbcTemplate` (test 전용) | 제외 | spy, 통합 테스트가 사용처 |
| `User.orders` 도메인 추가 | 제외 | 단순 POJO |

### 7.2 회귀 카운트 추정 (304 → ~318)

| Task 영역 | 단위 | 통합 | 누적 |
|---|---|---|---|
| 시작 (Phase 4 마감) | - | - | 304 |
| EntityMetadataAnalyzer @SfsOneToMany 분기 + fail-fast 3종 (성공 1 + 실패 3) | +4 | - | 308 |
| SfsPersistentList<T> (lazy 발화 / closed 예외 / identity 보장 / write 메서드 trigger 1건) | +4 | - | 312 |
| EntityPersister.findAll (SELECT *) | +1 | - | 313 |
| DefaultCollectionLoader (SELECT WHERE fk) | +1 | - | 314 |
| `OneToManyLazyIntegrationTest` (lazy 발화 시점 + closed 예외) | - | +2 | 316 |
| `NPlusOneIntegrationTest` (findAll + spy SQL count) | - | +2 | 318 |
| **목표** | **+10** | **+4** | **~318 (+14)** |

> *±2 자연 변동* 허용 — Phase 4 패턴(추정 +52 → 실측 +60) 정합. plan § 12 추적표에 *추정 vs 실측* 양 컬럼 박제 예정.

### 7.3 spy 인프라 (`SqlCountingJdbcTemplate`)

```java
public class SqlCountingJdbcTemplate extends JdbcTemplate {
    
    public final List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());
    
    // Phase 4 JdbcTemplate 시그니처 정합: (DataSource, TransactionSynchronizationManager) 2-arg 생성자
    public SqlCountingJdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) { 
        super(ds, tsm); 
    }
    
    @Override 
    public <T> List<T> query(String sql, RowMapper<T> rm, Object... params) {
        executedSqls.add(sql);
        return super.query(sql, rm, params);
    }
    
    @Override 
    public int update(String sql, Object... params) {
        executedSqls.add(sql);
        return super.update(sql, params);
    }
    
    // queryForObject / updateAndReturnKey 동일 패턴 override
    
    public int countMatching(String pattern) {
        return (int) executedSqls.stream().filter(s -> s.contains(pattern)).count();
    }
    
    public void reset() { executedSqls.clear(); }
}
```

**production JdbcTemplate 변경 0** — spy는 *테스트 전용 서브클래스*. `AbstractOrmIntegrationTest`의 datasource 빈에 inject (또는 별도 헬퍼 메서드로 spy 인스턴스 획득).

### 7.4 통합 테스트 시나리오 매핑

| 통합 테스트 | 시나리오 (회귀 박제) |
|---|---|
| `OneToManyLazyIntegrationTest` | (1) 첫 List 메서드 호출 시 정확히 1 SELECT 발생 — `size()` 호출 검증 (spy로 SELECT 1회 확인) / (2) PC close 후 List 메서드 호출 시 `SfsLazyInitializationException` |
| `NPlusOneIntegrationTest` | (1) 사전 *3명 User + 각각 1~2 Order INSERT/flush* → tx commit (= PC close). 새 tx에서 **`spy.reset()` 후** `em.findAll(User)` + for-loop `u.getOrders().size()` → `spy.countMatching("SELECT")` 정확히 N+1 = 4 / (2) 같은 PC 안에서 for-loop *재실행* 시 추가 SELECT 0 — `delegate != null` 캐시 hit으로 lazy init 1회만 |

> **spy 측정 시점 규약**: 사전 데이터 setup의 INSERT/SELECT은 *카운트에서 제외* (`spy.reset()` 후 측정). 본 mini-phase에서 SQL 카운트는 *항상 reset 후*에 측정되는 게 시나리오 1차 의도 표현.

---

## 8. Definition of Done — 14 항목

1. **`@SfsOneToMany` 어노테이션** — `fetch()`, `joinColumn()` 속성 정의 (FetchType.LAZY only enum)
2. **`CollectionMetadata` record** — `field`, `elementType`, `joinColumnName`
3. **`EntityMetadata`** `oneToManies: List<CollectionMetadata>` + `selectAllSql: String` 필드 2개 추가
4. **`EntityMetadataAnalyzer`** `@SfsOneToMany` 분기 + generic 자동 추출 + fail-fast 3종 + `buildSelectAllSql` 헬퍼
5. **`SfsPersistentList<T> implements List<T>`** — 모든 메서드 lazy init trigger + closed 예외 + isInitialized 헬퍼
6. **`CollectionLoader` 인터페이스** + **`DefaultCollectionLoader`** 구현 (jdbc 의존 없음, persister 위임)
7. **`EntityPersister.buildRowMapper`** oneToManies 채우기 (SfsPersistentList stub 주입) + cache hit 재사용 보강
8. **`EntityPersister.findAll`** + **`EntityPersister.findByForeignKey`** + **`RealEntityManager.findAll`** + **`SfsEntityManager.findAll`** 시그니처
9. **`SfsLazyInitializationException` 재사용** (collection closed 시) — Phase 4 인프라 그대로
10. **fail-fast 3종 통합 검증** — List 외 타입 / generic 누락 / elementType 비엔티티
11. **`User` 도메인 확장** — `List<Order> orders` + `@SfsOneToMany(joinColumn = "user_id")` + getter/setter
12. **`OrmDemoApplication` 시연 3건 (DH/DI/DJ)** — manual run으로 console 출력 확인
13. **회귀 +14 (목표 ~318 PASS, ±2 자연 변동 허용)**
14. **마감 게이트** — 다관점 리뷰 + 리팩토링 + simplify (CLAUDE.md "완료 후 품질 게이트" 정합)

---

## 9. 이월 박제 — Mini-phase 후보 갱신

Phase 4 spec § 9 박제 9건 그대로 유지 + MP-2 결정으로 *추가/조정*:

| # | 후보 | 출처 / 변경 | 학습 정점 후보 |
|---|---|---|---|
| **MP-2-α (신설)** | EAGER collection fetch (`@SfsOneToMany(fetch = EAGER)`) | MP-2 Q3 결정으로 EAGER 미지원 → 별도 phase | collection EAGER의 *별도 SELECT vs JOIN trade-off* (MP-4와 짝패) |
| **MP-3** | 양방향 + cascade + orphanRemoval | Phase 4 spec § 9 유지 — *MP-2 학습 짝패 회수* | mappedBy 의미, cascade=PERSIST/REMOVE, orphanRemoval, 양방향 일관성 책임 |
| **MP-4** | fetch JOIN + N+1 *해결책* 박제 | Phase 4 spec § 9 유지 — *MP-2 학습 정점 2의 해결책* | fetch JOIN 메커니즘, *별도 SELECT vs JOIN trade-off* |
| MP-1, MP-5, MP-6, MP-7, MP-8, MP-9 | Phase 4 박제 그대로 | 변경 없음 | (Phase 4 spec § 9 참고) |

### 9.1 MP-2가 만든 *학습 부채* (의도적)

| 부채 | MP-2 시연 | 회수 위치 |
|---|---|---|
| cascade 부재 | DJ: `add() + persist()만` → orphan INSERT 누락 | MP-3 cascade=PERSIST |
| N+1 폭발 | DH: `findAll + for-loop` → N+1 SELECT | MP-4 fetch JOIN |
| EAGER collection 부재 | (시연 없음, MP-2-α 박제만) | MP-2-α |
| 양방향 일관성 | (시연 없음, MP-3 주제) | MP-3 |
| collection의 dirty 처리 (`add()`를 entity dirty로 박제 안 함) | (시연 없음, 단방향이라 *DB 영향 없음*이 의미상 정합) | MP-3 (양방향에서 부모 entity dirty 처리) |

> **mini-phase는 *학습 부채를 의도적으로 만들고 다음 phase가 회수***하는 패턴이 명시 — *학습 곡선 설계*의 박제. 향후 모든 mini-phase에 동일 표 박제 의무.

### 9.2 우선순위 *추천* 갱신 (MP-2 마감 후 사용자가 선택 시 참고)

- *학습 가치 1순위*: **MP-3** (양방향 + cascade) — *MP-2 학습 짝패 자연 회수*
- *학습 가치 2순위*: **MP-4** (fetch JOIN) — *MP-2 N+1 해결책 자연 회수*
- *학습 가치 3순위*: **MP-2-α** (EAGER collection) — 단독 phase로는 가치 다소 낮음, MP-4와 묶을지 검토 가능
- 그 외: Phase 4 + MP-2 사용 경험에 따라 재평가

---

## 10. 부록 — Phase 4와의 시그니처 정합 점검

본 mini-phase가 Phase 4 인프라를 *깨뜨리지 않는지* 사전 점검:

| Phase 4 시그니처 | MP-2 변경 | 영향 |
|---|---|---|
| `EntityMetadata(class, table, idField, idGen, columns, manyToOnes, insertSql, selectByIdSql, deleteSql)` | **+ `oneToManies`, `selectAllSql` 필드 2개 추가** | 모든 생성자 호출 지점 정정 필요 (`EntityMetadataAnalyzer.doAnalyze` 1곳) — 회귀로 Phase 4 PASS 유지 가능 (record 신호 변경, 호출지 1곳) |
| `EntityMetadataAnalyzer.doAnalyze` | 분기 추가 + `buildSelectAllSql` 헬퍼 신설, 기존 분기 그대로 | 회귀 영향 0 (분기 추가만) |
| `EntityPersister.buildRowMapper` | oneToManies 루프 추가, 기존 로직 그대로 | 회귀 영향 0 (분기 추가만) |
| `EntityPersister` 메서드 | `findAll`, `findByForeignKey` 2 메서드 신설 | 기존 메서드 변경 없음 |
| `SfsEntityManager` 인터페이스 | `findAll(Class<T>)` 시그니처 추가 | 기존 API 정합 (추가만) |
| `RealEntityManager` 구현 | `findAll` 메서드 추가 | 기존 API 정합 |
| `SfsEntityManagerFactory` | `collectionLoader` 빈 추가 | 기존 빈 노출 그대로 |
| `LazyProxyFactory` / `LazyInterceptor` / `LazyTargetLoader` | 변경 *없음* | byte-buddy 영역은 entity 한정, collection은 별도 패턴 |
| `JdbcTemplate` (sfs-tx) | 변경 *없음* | spy는 테스트 전용 서브클래스 (DataSource, TSM 2-arg 생성자 호출) |

> **Phase 4 회귀 304 PASS는 MP-2 작업 후에도 모두 유지** — DoD 13항 회귀 +14의 의미가 *Phase 4 PASS 유지 + MP-2 신규 +14*.

---

## 11. 다음 단계

이 spec 사용자 승인 후 — `superpowers:writing-plans` 스킬 invoke로 implementation plan 작성:
`docs/superpowers/plans/2026-05-XX-mp2-one-to-many.md`

plan 작성 시 § 7 test 표 ↔ plan task / § 8 DoD ↔ plan task / § 9 이월 박제 ↔ plan § 10 매핑 명시.

구현은 *별도 세션*에서 진입 ([[feedback_design_implementation_session_split]] 정합).
