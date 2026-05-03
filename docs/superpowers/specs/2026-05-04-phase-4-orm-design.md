# Spring From Scratch — Phase 4: ORM 핵심 (영속성 컨텍스트 + lazy proxy + 더티 체킹) 설계

| 항목 | 값 |
|---|---|
| 작성일 | 2026-05-04 |
| 상태 | 초안 → 사용자 리뷰 대기 |
| 범위 | Phase 4 (ORM 핵심) — 6단계 로드맵 중 4단계 |
| 선행 의존 | Phase 1 (IoC), Phase 2A/2B (AOP), Phase 3 (트랜잭션), Phase 1A 결함 보강 — 모두 완료 + main 머지 |
| 후속 의존 | Phase 5 (Redis), Phase 6 (Kafka) — 본 phase의 영속성 컨텍스트 패턴이 *cross-cutting cache 추상화*의 토대 |
| 관련 메모리 | `project_phase1a_gap_resume_point.md` (학습 정점 *대칭 회수* 직접 활용) |

---

## 0. 프로젝트 개요

### 0.1 한 줄 요약

> **"sfs-tx 위에 *영속성 컨텍스트(1차 캐시)* + *snapshot 기반 더티 체킹* + *byte-buddy lazy proxy*를 얹어, *영속성 컨텍스트가 entity identity의 단일 source of truth*가 되는 ORM 메커니즘을 own minimal API로 박제. Phase 1A 결함 보강에서 박제한 *책임 분담 패턴*의 *대칭 회수* (단일 SoT 패턴)가 학습 정점."**

### 0.2 6단계 로드맵 위치

| 단계 | 모듈 | 상태 |
|---|---|---|
| 1. IoC 컨테이너 | sfs-core / sfs-beans / sfs-context | ✅ 완료 |
| 2. AOP | sfs-aop | ✅ 완료 |
| 3. 트랜잭션 추상화 | sfs-tx | ✅ 완료 |
| 중간. Phase 1A 결함 보강 | sfs-beans/aop/tx | ✅ 완료 |
| **4. ORM 핵심** | **sfs-orm (신설)** | **본 spec 대상** |
| 5. Redis 통합 | sfs-redis (가칭) | 본 phase의 *영속성 컨텍스트 패턴*이 cache 추상화의 토대 |
| 6. Kafka 통합 | sfs-kafka (가칭) | 본 phase의 *write-behind 큐 패턴* 재활용 |

> **모듈 이름 정정**: 원안 spec § 0.2는 *"sfs-jpa (가칭)"* 으로 박제했으나 본 phase는 *spec 호환을 추구하지 않는 own minimal API* (의사결정 1) → **`sfs-orm`** 으로 확정. *"JPA 핵심"* 표현은 메모리 박제 그대로 유지하되 모듈 이름은 *기술 영역명*으로 정확화.

### 0.3 학습 정점 3개

본 phase는 *3개 학습 정점*을 *서로 강결합된 단일 메커니즘*으로 박제:

> **① persist ≠ INSERT, flush ≠ commit (transactional write-behind)**
> 
> persist 호출 시점에는 *영속성 컨텍스트 등재만*, 실제 SQL은 flush 시점. flush ≠ commit (FL3: auto-on-commit + 명시 호출). *SEQUENCE entity*로 *정상 박제*, *IDENTITY entity*로 *정점 깨지는 함정 박제* (Hibernate batch insert 비활성화 이유와 동일 메커니즘).

> **② 1 entity = 1 instance per persistence context**
> 
> identityMap이 single source of truth (C1: Hibernate 본가 패턴). `find()` 두 번 호출 시 같은 인스턴스 (`==` 보장). proxy도 캐시 entry *그 자체* — proxy와 원본은 *같은 인스턴스의 다른 시점 모습*.

> **③ lazy proxy의 identity 보장 — Phase 1A gap 책임 분담 패턴의 *대칭 회수***
> 
> Phase 1A gap에서 박제한 *"BPP가 enhance 1회 보장 + 인프라(DSBR)가 필드 동기화"* 책임 분담 패턴이, 본 phase에서는 *"영속성 컨텍스트가 identity의 단일 source of truth → 책임 분담 자체 불필요"* 로 *대칭 진화*. 두 phase가 학습 짝패가 됨.

### 0.4 학습 짝패 박제 — Phase 1A gap ↔ Phase 4

| 측면 | Phase 1A 결함 보강 (책임 분담) | Phase 4 (단일 SoT) |
|---|---|---|
| 도메인 | bean 생명주기 (BPP enhance) | entity 생명주기 (ORM lazy) |
| 도구 | byte-buddy 서브클래싱 | byte-buddy 서브클래싱 (도구 공유, 도메인 분리) |
| identity 책임 | *분담* — BPP가 *재enhance 방지* + DSBR이 *필드 동기화* | *단일 SoT* — 영속성 컨텍스트가 identity 보장, 책임 분담 불필요 |
| 학습 진화 | *D1 BLOCKED* → 책임 분담 패턴 박제 | *대칭 회수* → 단일 SoT 패턴 박제 |
| 부산물 | "*enhance 반환 정책에 따라 책임 위치가 갈린다*" | "*proxy도 캐시 entry — 분리 자체가 함정*" |

> 두 phase는 *동일 trade-off의 두 끝점*을 박제하며, 다음 phase 학습 정점 결정 시 *"이번엔 분담인가 단일 SoT인가"* 기준 자체가 됨.

---

## 1. 의사결정 로그

브레인스토밍 (2026-05-04)에서 확정된 결정 11건.

| # | 결정 영역 | 선택지 | 채택 | 근거 |
|---|---|---|---|---|
| Q1 | API 정체성 | A: own minimal / B: jakarta.persistence 호환 / C: SQL mapper (MyBatis 스타일) | **A** | spec 호환 부담 회피 + 학습 정점에 집중. 메모리 *책임 분담 패턴*과 직결되는 own naming 자유. |
| Q2 | 기능 범위 | A1: 단일 CRUD / A2: + 단방향 N:1 lazy / A3: + collection / A4: + 양방향 + cascade | **A2** | demo 자연 정합. § 0.3 학습 정점 3개에 깔끔히 매핑 (write-behind / 1 entity = 1 instance / lazy proxy identity). A3/A4는 별도 mini-phase. |
| Q3 | EM 라이프사이클 | L1: 트랜잭션 바운드 / L2: resource-local / L3: OSIV | **L1** | sfs-tx 자산 자연 회수. Spring `@PersistenceContext` 정확 박제. |
| Q4 | lazy 메커니즘 + 모듈 | P1: byte-buddy 직접 + sfs-orm 신설 / P2: sfs-aop 재활용 / P3: JDK 동적 프록시 / P4: 명시 wrapper | **P1** | 도메인 정합 (AOP advice ≠ ORM lazy). 도구만 공유. |
| Q5 | identity map 정책 | C1: Hibernate 본가 (proxy도 캐시) / C2: 순진한 분리 (Phase 1A gap 함정 재발) / C3: no cache | **C1** | Phase 1A gap *책임 분담 패턴*의 대칭 회수 (단일 SoT). |
| Q6 | EM API 표면 | API1: 5개 / API2: + merge / API3: + refresh/detach/clear/contains | **API2** | detached state 학습 정점을 merge로 *완성*. shallow merge / cascade 없음 / EntityListener 없음. *(브레인스토밍 중 추천 정정)* |
| Q7 | fetch 전략 | F1: default LAZY / F2: default EAGER (spec) / F1.5: default LAZY + 1 EAGER 시연 | **F1.5** | 메커니즘 박제 + 명시 EAGER 시연 동시. *(절충안)* |
| Q8 | ID 전략 | I1: IDENTITY only / I2: SEQUENCE only / I3: ASSIGNED only / I4: IDENTITY + SEQUENCE | **I4** | 학습 정점 ① *정상 박제 + 깨짐 함정 박제* 동시. sequence는 매번 DB 호출 (allocationSize는 mini-phase). |
| Q9 | schema 관리 | S1: hand-written only / S2: auto DDL only / S3: 둘 다 / S4: Hibernate full | **S1** | 학습 도메인 분리. Phase 3 schema 재활용. auto DDL은 mini-phase 후보. |
| Q10 | flush 모드 | FL1: commit only / FL2: auto-on-query / FL3: commit + 명시 flush() / FL4: Hibernate full | **FL3** | API2 `flush()`와 자연 정합. write-behind 시각화. FL2의 의의는 한 줄 박제 (mini-phase 후보). |
| Q11 | entity 발견 | D1: explicit register (fluent builder) / D2: classpath scanning / D3: ComponentScan 통합 | **D1** | 학습 도메인 외. Phase 1B ComponentScan과 중복 회피. |

### 1.1 추천 정정 1건 (Q6) 박제

본 brainstorming 중 *receiving feedback* 패턴이 적용된 정정 1건:

> **Q6 정정 사이클**: 어시스턴트가 처음 *API1 (5개, no merge)* 추천 → 사용자가 *"API2 어때?"* 검토 → 어시스턴트가 *"detached state 학습 정점이 attach 경로 없이는 *반쪽임*"* 인정 후 **API2로 정정**. *학습 정점 집중 원칙*이 *detached state 의미 완결*보다 보수적이었음을 박제.

이 패턴은 본 phase 마감 게이트 reviewer 단계에서 *"추천 정정 사이클의 박제 가치"* 로 재평가 가능.

---

## 2. 아키텍처 & 모듈 구조

### 2.1 모듈 의존 그래프

```
sfs-samples ──► sfs-orm ──► sfs-tx ──► sfs-aop ──► sfs-beans ──► sfs-core
                  │                                     │
                  │         byte-buddy (직접)           │
                  └────────────► (도구 공유) ◄──────────┘
                  
                  (sfs-aop 비의존, sfs-context 비의존 — layering 정합)
```

> **핵심 박제 (Q4 P1 정합)**: byte-buddy는 *AOP advice 도메인 (sfs-aop)*과 *ORM lazy 도메인 (sfs-orm)*에서 *각자 직접* 사용. 도구 공유는 *우연의 일치*, 도메인 분리는 *모듈 책임 정합*. *공통 util 모듈로 격하*는 *세 번째 도메인 등장 시* 검토 (현재 YAGNI).

### 2.2 sfs-orm `build.gradle.kts`

```kotlin
plugins { `java-library` }
dependencies {
    implementation(project(":sfs-tx"))     // JdbcTemplate, TSM, @Transactional
    implementation(libs.byteBuddy)         // lazy proxy 직접 (Phase 2A 도입분)
    testImplementation(libs.h2)
    testImplementation(project(":sfs-context"))  // 통합 테스트 한정
}
```

> **layering 박제**: sfs-orm은 sfs-context를 *프로덕션에서 모름* — sfs-context가 *application layer로 sfs-orm 위*에 있어야 자연. 사용자 통합은 *sfs-context의 @SfsConfiguration 안에서 SfsEntityManagerFactory.builder()*로 엮음. sfs-orm 자체는 *plain Java로도 사용 가능* (별도 mini-phase에서 *resource-local 모드* 추가도 옵션).

### 2.3 패키지 구조

```
com.choisk.sfs.orm/
├── annotation/                           ← Q1 own minimal API
│   ├── SfsEntity.java                    (클래스 레벨, optional table name)
│   ├── SfsId.java                        (PK 필드 마커)
│   ├── SfsGeneratedValue.java            (strategy = IDENTITY | SEQUENCE, sequenceName)
│   ├── SfsColumn.java                    (optional, default = 필드명 그대로)
│   ├── SfsManyToOne.java                 (fetch = LAZY | EAGER, default LAZY ← F1.5)
│   └── SfsJoinColumn.java                (FK 컬럼명)
│
├── SfsEntityManager.java                 ← API2 인터페이스
├── SfsEntityManagerFactory.java          ← fluent builder (D1 explicit register)
│
├── exception/
│   ├── SfsPersistenceException.java       (root RuntimeException)
│   ├── SfsLazyInitializationException.java
│   ├── SfsTransactionRequiredException.java
│   └── SfsEntityMappingException.java
│
├── support/                              ← 핵심 메커니즘 (§ 3)
│   ├── PersistenceContext.java            (1차 캐시 + write-behind + snapshot)
│   ├── EntityMetadata.java                (어노테이션 분석 결과)
│   ├── EntityMetadataAnalyzer.java        (부팅 시 fail-fast 검증)
│   ├── EntityPersister.java               (SQL 생성 + JdbcTemplate 호출)
│   ├── LazyProxyFactory.java              (byte-buddy 서브클래싱)
│   ├── LazyInterceptor.java               (lazy 트리거 + identity 보장)
│   ├── IdentifierGenerator.java           (interface)
│   ├── IdentityGenerator.java             (Q8 IDENTITY)
│   └── SequenceGenerator.java             (Q8 SEQUENCE — 매번 DB 호출)
│
└── boot/                                 ← sfs-tx 통합 (L1)
    ├── SfsEntityManagerFactoryBean.java   (bean lifecycle + TSM 콜백 등록)
    └── SfsTransactionalEntityManager.java (current TX의 EM lookup proxy)
```

### 2.4 EntityManager API 시그니처

```java
public interface SfsEntityManager {
    void persist(Object entity);
    <T> T find(Class<T> entityClass, Object primaryKey);   // null이면 not found
    void remove(Object entity);
    void flush();                                            // FL3 명시 호출
    <T> T merge(T entity);                                   // shallow, return value 함정 박제
    boolean contains(Object entity);                         // 1차 캐시 확인 (디버깅 용)
    // close()는 사용자가 호출하지 않음 — TSM 콜백에서 자동
}
```

### 2.5 EM Lifecycle (L1 — 트랜잭션 바운드)

```
@Transactional 메서드 진입
    │
    ▼
TransactionInterceptor (sfs-tx)
    │  ├─ tx begin
    │  └─ TransactionSynchronizationManager.bindResource(emf, holder)  ← L1
    │
    ▼
service 코드: entityManager.persist(order)
    │
    ▼
SfsTransactionalEntityManager (proxy)
    │  ├─ TSM.getResource(emf) → EntityManagerHolder
    │  ├─ holder.getEm() — 없으면 새로 생성
    │  └─ realEm.persist(order)
    │
    ▼
@Transactional 메서드 종료
    │
    ▼
TransactionInterceptor
    │  ├─ TSM.synchronize() 콜백
    │  │   └─ SfsEntityManagerFactoryBean의 콜백:
    │  │       ├─ em.flush()      ← FL3 auto-on-commit
    │  │       └─ em.close()      ← 영속성 컨텍스트 destroy
    │  └─ tx commit
    │
    ▼
service 메서드 반환된 entity → detached
```

### 2.6 트랜잭션 없이 EM 조작 시 (L1 자연 귀결)

`@Transactional` 누락 메서드에서 `em.persist()` 호출 → `SfsTransactionalEntityManager.persist()` → `TSM.getResource(emf)` 가 *null* (트랜잭션 미시작) → `SfsTransactionRequiredException("No transaction in progress — annotate with @Transactional")` throw. JPA spec과 정합.

### 2.7 사용자 config 패턴

```java
@SfsConfiguration
public class OrmConfig {
    @SfsBean
    public SfsEntityManagerFactory entityManagerFactory(DataSource ds) {
        return SfsEntityManagerFactory.builder()
            .dataSource(ds)
            .addEntityClass(Order.class)
            .addEntityClass(User.class)
            .addEntityClass(AuditLog.class)
            .build();
    }
    
    @SfsBean
    public SfsEntityManager entityManager(SfsEntityManagerFactory emf) {
        return new SfsTransactionalEntityManager(emf);
    }
    
    @SfsBean
    public SfsEntityManagerFactoryBean emfLifecycle(SfsEntityManagerFactory emf) {
        return new SfsEntityManagerFactoryBean(emf);  // TSM 콜백 등록 + close lifecycle
    }
}
```

---

## 3. 핵심 컴포넌트 (5)

### 3.1 PersistenceContext — 영속성 컨텍스트 자체

```java
class PersistenceContext {
    private final Map<EntityKey, Object> identityMap;     // (Class, PK) → entity (proxy or 원본 — C1)
    private final Map<EntityKey, Object[]> snapshots;     // (Class, PK) → 필드값 snapshot
    private final List<EntityAction> actionQueue;         // pending INSERT/DELETE
    private boolean closed = false;                       // close 후 lazy 접근 → SfsLazyInitializationException
    
    record EntityKey(Class<?> entityClass, Object primaryKey) { }
}

sealed interface EntityAction permits InsertAction, DeleteAction, UpdateAction { }
record InsertAction(Object entity, EntityMetadata md) implements EntityAction { }
record DeleteAction(Object entity, EntityMetadata md) implements EntityAction { }
record UpdateAction(Object entity, EntityMetadata md, BitSet dirtyColumns) implements EntityAction { }
```

> **자료구조 박제**: `identityMap`이 *single source of truth* (C1). proxy와 원본은 *같은 인스턴스의 다른 시점 모습* — *proxy 자체가 캐시 entry, 초기화되면 내부 위임 시작*. snapshot은 *값 비교*로 dirty 판단 (동일성 비교 아님).

### 3.2 EntityMetadata — 어노테이션 분석 결과 (부팅 시 1회)

```java
record EntityMetadata(
    Class<?> entityClass,
    String tableName,
    FieldMetadata idField,                     // @SfsId + @SfsGeneratedValue
    IdentifierGenerator idGenerator,           // strategy에 따라 IDENTITY/SEQUENCE 인스턴스
    List<FieldMetadata> columns,               // @SfsColumn (또는 default mapping)
    List<RelationMetadata> manyToOnes,         // @SfsManyToOne
    Class<?> enhancedClass                     // byte-buddy lazy subclass (lazy 필드 있을 때만)
) { }

record FieldMetadata(Field field, String columnName, Class<?> javaType) { }
record RelationMetadata(Field field, FetchType fetch, Class<?> targetEntity, String joinColumnName) { }
```

> **caching 박제**: `EntityManagerFactoryBuilder.build()`에서 *모든 등록 entity의 metadata 미리 분석 + 캐시*. 런타임 reflection 비용은 0 (필드 lookup만). *Hibernate `SessionFactory` 패턴*.

### 3.3 EntityPersister — SQL 생성 + JdbcTemplate 위임

```java
class EntityPersister {
    private final EntityMetadata md;
    private final JdbcTemplate jdbc;
    private final String insertSql;     // PreparedStatement 캐싱용
    private final String updateSqlBase; // SET 절은 dirtyColumns에 따라 동적
    private final String deleteSql;
    private final String selectByIdSql;
    
    Object loadById(Object pk) { /* SELECT → row → entity 매핑 + lazy proxy 채우기 */ }
    void executeInsert(Object entity) { /* JdbcTemplate.update */ }
    void executeUpdate(Object entity, BitSet dirtyColumns) { /* 동적 SET */ }
    void executeDelete(Object entity) { /* DELETE */ }
}
```

> **SQL 캐싱 박제**: 부팅 시 INSERT/DELETE/SELECT 풀 SQL은 미리 생성. UPDATE만 *dirty 컬럼에 따라 동적* (Hibernate `dynamic-update`와 동일). PreparedStatement 자체 캐싱은 JDBC driver 책임.

### 3.4 LazyProxyFactory + LazyInterceptor — byte-buddy 서브클래싱 (P1)

```java
class LazyProxyFactory {
    private final Map<Class<?>, Class<?>> enhancedCache;
    
    Object createProxy(Class<?> targetClass, Object pk, PersistenceContext context) {
        Class<?> enhanced = enhancedCache.computeIfAbsent(targetClass, this::buildEnhanced);
        Object proxy = enhanced.getDeclaredConstructor().newInstance();
        // proxy의 hidden interceptor에 (targetClass, pk, context) 주입
        return proxy;
    }
    
    private Class<?> buildEnhanced(Class<?> targetClass) {
        return new ByteBuddy()
            .subclass(targetClass)
            .method(ElementMatchers.isPublic().and(ElementMatchers.not(ElementMatchers.named("getId"))))
            .intercept(MethodDelegation.to(LazyInterceptor.class))
            .make()
            .load(targetClass.getClassLoader())
            .getLoaded();
    }
}

class LazyInterceptor {
    private Object target;
    private final PersistenceContext context;
    private final Class<?> targetClass;
    private final Object pk;
    
    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args) {
        if (target == null) {
            if (context.isClosed())
                throw new SfsLazyInitializationException(targetClass.getSimpleName() + "#" + pk);
            target = context.findInternal(targetClass, pk);   // ← C1: 같은 EM에서 같은 PK는 같은 인스턴스
        }
        return method.invoke(target, args);
    }
}
```

> **getId() 비-인터셉트 박제**: PK getter는 *프록시 hidden 필드에서 즉시 반환* — *PK만 필요한 시나리오*에서 lazy 초기화 회피. Hibernate도 동일.
> 
> **target 채우기 메커니즘 박제 (학습 정점 ③)**: `LazyInterceptor.target = context.findInternal(targetClass, pk)` 호출 시 *현재 영속성 컨텍스트의 identityMap을 거침* → 만약 같은 PK가 캐시에 있으면 *그 인스턴스를 그대로 받음* → C1 정합. *proxy와 원본의 동일성이 자동 보장*.

### 3.5 IdentifierGenerator — IDENTITY / SEQUENCE 분기 (I4)

```java
interface IdentifierGenerator {
    Object generate(Object entity, EntityMetadata md);
    boolean isPostInsert();   // true면 generate()가 INSERT까지 수행 (IDENTITY)
}

class SequenceGenerator implements IdentifierGenerator {
    private final String sequenceName;
    private final JdbcTemplate jdbc;
    
    @Override public boolean isPostInsert() { return false; }
    
    @Override public Object generate(Object entity, EntityMetadata md) {
        return jdbc.queryForObject("SELECT NEXTVAL('" + sequenceName + "')", Long.class);
        // 호출자가 entity.id 필드에 set + actionQueue에 InsertAction 추가
    }
}

class IdentityGenerator implements IdentifierGenerator {
    private final JdbcTemplate jdbc;
    
    @Override public boolean isPostInsert() { return true; }
    
    @Override public Object generate(Object entity, EntityMetadata md) {
        // 학습 정점 박제: persist == INSERT
        return jdbc.updateAndReturnKey(md.insertSql(), boundParams(entity));
    }
}
```

> **JdbcTemplate(sfs-tx) 신규 메서드 2종 필요** — 본 phase에서 sfs-tx에 추가 (§ 8 DoD 1.5):
> - `<T> T queryForObject(String sql, Class<T> requiredType, Object... args)` — single value 조회 (SEQUENCE NEXTVAL용)
> - `Number updateAndReturnKey(String sql, Object... args)` — INSERT 후 generated key 반환 (`Statement.RETURN_GENERATED_KEYS` 활용)
> 
> Connection 주입은 *기존 JdbcTemplate 패턴 정합* — TSM이 잡고 있는 Connection을 내부에서 lookup. IdentifierGenerator는 Connection을 받지 않음.

---

## 4. 데이터 플로우 (operation별 6개)

### 4.1 persist(user) — SEQUENCE entity (정점 ① 정상 박제)

> *예시 entity*: `User` (SEQUENCE strategy — § 5.3 정합)

```
em.persist(user)
    │
    ▼
SfsTransactionalEntityManager.persist
    └─ TSM.getResource(emf) → realEm
       └─ realEm.persist(user)
          │
          ▼
       PersistenceContext.persist
          ├─ md.idGenerator() = SequenceGenerator
          ├─ generator.generate() → SEQUENCE NEXTVAL ◄── 1회 SELECT
          │  → 받은 ID를 user.id 필드에 set
          ├─ identityMap.put((User, id), user)
          ├─ snapshots.put((User, id), capture(user))
          └─ actionQueue.add(new InsertAction(user))   ◄── INSERT는 flush 때
          
*persist 시점에는 INSERT 발생 안 함 — 학습 정점 ① 정상 박제*
```

### 4.2 persist(order) — IDENTITY entity (정점 ① 깨짐 함정 박제)

```
em.persist(order)
    │
    ▼
PersistenceContext.persist
    ├─ md.idGenerator() = IdentityGenerator
    ├─ generator.generate(conn, order, md) ───────────► INSERT 즉시 실행
    │  → returnGeneratedKey: order.id = 새 PK
    ├─ identityMap.put((Order, id), order)
    ├─ snapshots.put((Order, id), capture(order))
    └─ actionQueue 추가 안 함 (이미 INSERT됨)
    
*persist == INSERT — 학습 정점 ① 깨짐 함정 박제 (Hibernate batch insert 비활성화 이유)*
```

### 4.3 find(Order.class, 1L)

```
em.find(Order.class, 1L)
    │
    ▼
PersistenceContext.find
    │
    ├─ identityMap.get((Order, 1L))
    │   ├─ HIT  → return cached (proxy든 원본이든 — 학습 정점 ② 정합)
    │   └─ MISS → 다음 단계
    │
    ├─ EntityPersister.loadById(1L)
    │   ├─ SELECT * FROM orders WHERE id = 1
    │   ├─ row → Order 새 인스턴스 + @SfsColumn 필드 매핑
    │   ├─ for each manyToOne (LAZY):
    │   │   └─ field.set(entity, lazyProxyFactory.createProxy(targetClass, fkValue, context))
    │   ├─ for each manyToOne (EAGER):
    │   │   └─ field.set(entity, em.find(targetClass, fkValue))   ← 재귀 — 1차 캐시 활용
    │   └─ return entity
    │
    ├─ identityMap.put((Order, 1L), entity)
    ├─ snapshots.put((Order, 1L), capture(entity))
    └─ return entity
```

### 4.4 remove(order)

```
em.remove(order)
    ├─ contains((Order, order.id)) 검증 — 없으면 IllegalArgumentException
    └─ actionQueue.add(new DeleteAction(order))   ← flush 시 DELETE
    
*identityMap에서 제거는 *flush 후* — 같은 트랜잭션 안에서 다시 find() 시도 시 일관된 동작*
```

### 4.5 T merge(detached) — shallow merge

```
T managed = em.merge(detachedOrder)
    │
    ▼
PersistenceContext.merge
    │
    ├─ EntityKey key = (Order, detachedOrder.id)
    │
    ├─ Object existing = identityMap.get(key)
    │   ├─ HIT  → managed = existing
    │   └─ MISS → managed = em.find(Order, id)   ← DB 로드 + 캐시 등재
    │
    ├─ for each @SfsColumn field:
    │   └─ field.set(managed, field.get(detachedOrder))   ← shallow copy
    │
    ├─ snapshots.put(key, capture(managed))   ← 갱신
    │
    └─ return managed     ← ⚠️ detachedOrder는 여전히 detached
                              return value 안 받으면 변경 사라짐 (학습 정점 박제)
```

> **함정 박제**: `em.merge(order); /* return 안 받음 */ order.setStatus("PAID");` ← 이 setStatus는 *detached*에 적용되어 사라짐. **API 시그니처 `T merge(T)`가 *return value 사용 강제*하는 이유**.

### 4.6 flush() — auto-on-commit + 명시 호출 (FL3)

```
em.flush()
    │
    ▼
PersistenceContext.flush
    │
    ├─ Phase 1: dirty check (모든 managed entity 순회)
    │   for each (key, entity) in identityMap:
    │       Object[] current = capture(entity)
    │       Object[] original = snapshots.get(key)
    │       BitSet dirty = compare(current, original)
    │       if (!dirty.isEmpty()):
    │           actionQueue.add(new UpdateAction(entity, md, dirty))
    │           snapshots.put(key, current)
    │
    ├─ Phase 2: action 순차 실행 (INSERT → UPDATE → DELETE 순)
    │   for action in actionQueue:
    │       persister.execute(action)   ← JdbcTemplate 호출
    │
    │   *실행 순서는 FK 제약 회피 (INSERT 먼저, DELETE 마지막)*
    │
    └─ actionQueue.clear()
    
*close()는 자동 — TSM 콜백에서 flush() + identityMap.clear() + closed=true*
```

### 4.7 EAGER fetch 처리 (F1.5 정합)

`@SfsManyToOne(fetch = EAGER)` 필드는 **별도 SELECT** (JOIN 안 함):
- 단순성 우선
- *list 조회 (`findAll`) 없음* (A2 범위)이라 N+1 폭발 위험 없음
- *EAGER 추가 SELECT도 1차 캐시 거침* → 같은 PK 중복 조회 방지

> **이월 박제 후보 (MP-4)**: *fetch JOIN 박제* (collection lazy 도입 시 N+1 함정 박제와 함께)

### 4.8 학습 정점 ↔ 코드 위치 매핑표

| 정점 | 코드 위치 | 박제 메커니즘 |
|---|---|---|
| ① persist ≠ INSERT (SEQUENCE) | `PersistenceContext.persist` → `actionQueue.add(InsertAction)` | flush까지 INSERT 미발생 |
| ① persist == INSERT (IDENTITY 함정) | `IdentityGenerator.generate()` | 즉시 INSERT |
| ① flush ≠ commit | TSM `afterCompletion()` 콜백 | flush() → commit 순서 |
| ② 1 entity = 1 instance | `identityMap.get(key)` hit | proxy든 원본이든 1개 |
| ② proxy도 캐시 entry | `find()` cache miss → lazy 필드는 proxy로 채워서 entity 캐시에 들어감 | C1 정합 |
| ③ lazy proxy의 identity 보장 | `LazyInterceptor.intercept()` → `context.findInternal()` | 같은 EM의 identityMap 거침 |
| ③ 책임 분담 *불필요* (대칭 회수) | identityMap 단일 SoT | Phase 1A gap 패턴의 대칭 진화 |

---

## 5. Demo 시나리오 — `OrmDemoApplication` (DM1)

Phase 3 `TransactionDemoApplication`은 *그대로 보존* (before/after 학습 비교 자산). 본 phase는 **새 demo `OrmDemoApplication`** 신설.

### 5.1 도메인 구성

```
sfs-samples/src/main/java/com/choisk/sfs/samples/orm/
├── OrmDemoApplication.java          (main + console 시연 7개)
├── config/
│   └── OrmConfig.java               (SfsEntityManagerFactory + DataSource bean)
├── domain/
│   ├── User.java                    (@SfsEntity, SEQUENCE strategy)
│   ├── Order.java                   (@SfsEntity, IDENTITY strategy, user→LAZY)
│   └── AuditLog.java                (@SfsEntity, IDENTITY, order→EAGER ← F1.5)
└── service/
    ├── OrderService.java            (@Transactional 메서드들)
    └── UserService.java
    
sfs-samples/src/main/resources/
└── orm-schema.sql                   (Phase 3 schema 재활용 + USERS + USERS_SEQ + FK)
```

### 5.2 Schema (`orm-schema.sql`)

```sql
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS users;
DROP SEQUENCE IF EXISTS users_seq;

CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE users (
    id BIGINT PRIMARY KEY,                   -- SEQUENCE로 미리 받음
    name VARCHAR(100) NOT NULL,
    email VARCHAR(200) NOT NULL
);

CREATE TABLE orders (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,    -- IDENTITY
    user_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE audit_log (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,    -- IDENTITY
    order_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

### 5.3 Entity 정의 (학습 정점 박제 매핑)

```java
@SfsEntity(name = "users")
public class User {
    @SfsId
    @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "users_seq")  // ← 정점 ① 정상 박제
    private Long id;
    
    @SfsColumn private String name;
    @SfsColumn private String email;
    // getters/setters
}

@SfsEntity(name = "orders")
public class Order {
    @SfsId
    @SfsGeneratedValue(strategy = IDENTITY)   // ← 정점 ① 깨짐 함정 박제
    private Long id;
    
    @SfsManyToOne(fetch = LAZY)                // ← F1.5 default LAZY
    @SfsJoinColumn(name = "user_id")
    private User user;
    
    @SfsColumn private BigDecimal amount;
    @SfsColumn private String status;
    @SfsColumn(name = "created_at") private LocalDateTime createdAt;
}

@SfsEntity(name = "audit_log")
public class AuditLog {
    @SfsId
    @SfsGeneratedValue(strategy = IDENTITY)
    private Long id;
    
    @SfsManyToOne(fetch = EAGER)               // ← F1.5 EAGER 시연 케이스
    @SfsJoinColumn(name = "order_id")
    private Order order;
    
    @SfsColumn private String action;
    @SfsColumn private String message;
    @SfsColumn(name = "created_at") private LocalDateTime createdAt;
}
```

### 5.4 시연 7개 (각각 학습 정점 박제)

| # | 시나리오 | 박제 정점 | service 메서드 |
|---|---|---|---|
| **DA** | `createUser("Alice")` → `placeOrder(userId, 99.99)` | ① SEQUENCE 정상 + IDENTITY 즉시 INSERT 차이 비교 | `OrderService.placeOrder` |
| **DB** | `payOrder(orderId)` → status 변경만 | 더티 체킹 (snapshot 비교 → UPDATE 자동 발견) | `OrderService.payOrder` |
| **DC** | `describeOrder(orderId)` → `order.getUser().getName()` | ② lazy proxy 첫 호출 시 SELECT, ③ proxy ↔ 원본 identity | `OrderService.describeOrder` |
| **DD** | `verifyIdentity(orderId, userId)` → `order.getUser() == user` | ② 1 entity = 1 instance (== 보장) | `OrderService.verifyIdentity` |
| **DE** | tx 밖에서 `detached.getUser()` 호출 | ③ `SfsLazyInitializationException` | (try-catch in main) |
| **DF** | `merge(detached)` 정상 + `merge` return 무시 함정 | merge return value 함정 | `OrderService.updateOrder` + `brokenUpdate` |
| **DG** | tx 없이 `persist()` | `SfsTransactionRequiredException` | (try-catch in main) |

### 5.5 Console output 시연 (대표 발췌)

```
=== Phase 4 ORM Demo ===

[DA] Creating user (SEQUENCE) and placing order (IDENTITY)
  ► SELECT NEXTVAL('users_seq')                       ← persist != INSERT (SEQUENCE)
  ► INSERT INTO orders (...) VALUES (...) RETURNING id ← persist == INSERT (IDENTITY 함정)
  ✓ Created user id=1, order id=1

[DC] describeOrder(1) — lazy proxy 시연
  ► SELECT * FROM orders WHERE id = 1
  (order.user는 LazyProxy — 아직 SELECT 없음)
  Description = "Order #1 by Alice"
  ► SELECT * FROM users WHERE id = 1                  ← lazy 첫 호출 시점

[DD] verifyIdentity(1, 1) — proxy ↔ 원본 동일성
  Identity guaranteed: true
  (학습 정점 ②: 1 entity = 1 instance per persistence context)

[DE] LazyInitializationException 박제
  ✗ Caught: SfsLazyInitializationException(User#1)
  ⓘ 영속성 컨텍스트 close 후 lazy 접근 — @Transactional 경계 박제

[DF] merge return value 함정
  ✓ updateOrder(detached) — return 받음, 변경 반영됨
  ✗ brokenUpdate(detached) — return 무시, status 변경 사라짐
  ⓘ T merge(T) 시그니처가 return value 사용 강제하는 이유

[DG] @Transactional 누락
  ✗ Caught: SfsTransactionRequiredException
  ⓘ EM은 트랜잭션 바운드 — TSM이 resource lookup 실패
```

> **demo 가치**: *각 정점이 console output으로 시각화* — Phase 2B의 `LoggingAspect 8→11 라인` 콘솔 패턴 재활용. 학습 자산이 *실행 가능한 시연*으로 박제.

---

## 6. 에러 처리 정책

### 6.1 예외 계층 (모두 unchecked — JPA spec 정합)

```
RuntimeException
  └─ SfsPersistenceException (root)
      ├─ SfsLazyInitializationException        ← lazy 접근 시 EM closed
      ├─ SfsTransactionRequiredException       ← EM 조작 시 TX 없음
      ├─ SfsEntityMappingException             ← @SfsEntity 어노테이션 분석 실패 (부팅 시 fail-fast)
      └─ DataAccessException (sfs-tx 자산 재활용)
          └─ JdbcTemplate이 SQLException을 wrap — sfs-orm은 그대로 전파
```

> **`find()` not found 정책**: JPA spec 정합으로 **null 반환** (예외 throw 안 함). `findRequired()` 같은 변종은 yagni — 사용자가 `Objects.requireNonNull`로 처리.

### 6.2 부팅 시 fail-fast 검증

`SfsEntityManagerFactory.builder().build()` 호출 시점에 *모든 등록 entity 검증*:
- `@SfsEntity` 누락 → `SfsEntityMappingException`
- `@SfsId` 필드 0개 또는 2개 이상 → `SfsEntityMappingException`
- `@SfsGeneratedValue(SEQUENCE)`인데 `sequenceName` 누락 → `SfsEntityMappingException`
- `@SfsManyToOne` 필드 type이 *@SfsEntity가 아님* → `SfsEntityMappingException`
- `@SfsJoinColumn` 누락 → `SfsEntityMappingException`

> **fail-fast 박제**: 런타임에 *처음 persist() 시점*에 발견되면 디버깅 어려움. 부팅 시 검증으로 *오류 시점이 시작과 동시*. Hibernate `Validator`도 동일.

### 6.3 동시성 정책 — *낙관적 락 없음* (이월 박제 MP-8)

본 phase는 `@SfsVersion` (낙관적 락) 없음 — 별도 mini-phase 후보:
- *concurrent UPDATE 시 lost update*는 *학습 정점*으로 박제 가능 but A2 범위 외
- 본 phase는 *단일 트랜잭션 안의 메커니즘*에 집중

### 6.4 Connection 관리 — sfs-tx에 *완전 위임*

EM은 *Connection을 직접 잡지 않음* — `JdbcTemplate`을 통해 *TSM이 잡고 있는* Connection을 사용:

```java
class EntityPersister {
    Object loadById(Object pk) {
        return jdbc.queryForObject(selectByIdSql, rowMapper, pk);
        // jdbc 내부: TSM.getResource(dataSource).getConnection() → 같은 TX의 Connection 재사용
    }
}
```

> **자산 회수 박제**: Phase 3에서 박제한 *"`TransactionSynchronizationManager`가 Connection 재사용 보장"* 가 그대로 동작 → EM의 모든 SQL이 *같은 Connection + 같은 트랜잭션*에서 실행. `flush()` 호출도 동일 — *추가 Connection 획득 없음*.

---

## 7. 테스트 전략

### 7.1 TDD 적용/제외 분류 (CLAUDE.md 정합)

| 컴포넌트 | TDD | 근거 |
|---|---|---|
| `PersistenceContext` (캐시/snapshot/queue) | **적용** | 분기 + 상태 + 라이프사이클 |
| `LazyProxyFactory` + `LazyInterceptor` | **적용** | 분기 (closed 시 예외) + identity 보장 + lazy 트리거 |
| `IdentityGenerator` / `SequenceGenerator` | **적용** | post-insert vs pre-insert 분기 |
| `EntityPersister` (SQL 생성, dirty UPDATE) | **적용** | 알고리즘 + 동적 SQL 생성 |
| `EntityMetadataAnalyzer` (어노테이션 분석 + fail-fast) | **적용** | 5종 검증 분기 |
| 모든 예외 (Lazy/Tx/Mapping) | **적용** | 발생 시나리오가 박제 자체 |
| 어노테이션 정의 (`@SfsEntity` 등) | **제외** | 단순 마커, 통합 테스트가 회귀망 |
| `EntityMetadata` record | **제외** | 데이터 컨테이너 (DTO) |
| `SfsEntityManager` 인터페이스 | **제외** | 시그니처만 |
| `EntityManagerFactoryBuilder` (fluent API) | **제외** | 통합 테스트로 간접 검증 |

### 7.2 테스트 파일 매핑 (회귀 +50 목표)

```
sfs-orm/src/test/java/com/choisk/sfs/orm/
│
├── support/                                       (단위 테스트 — 33건)
│   ├── PersistenceContextTest.java                 +6
│   ├── EntityMetadataAnalyzerTest.java             +8 (성공 1 + fail-fast 5종 + 캐싱 + 다중 등록)
│   ├── EntityPersisterTest.java                    +5 (SELECT/INSERT/UPDATE/DELETE SQL + dynamic dirty UPDATE)
│   ├── LazyProxyFactoryTest.java                   +3 (생성 + caching + getId 비-인터셉트)
│   ├── LazyInterceptorTest.java                    +3 (target 채우기 + closed 예외 + identity)
│   ├── IdentityGeneratorTest.java                  +2 (post-insert + key 회수)
│   └── SequenceGeneratorTest.java                  +2 (pre-insert + sequenceName)
│
└── integration/                                    (통합 테스트 — 19건, H2 + sfs-tx)
    ├── BasicCrudIntegrationTest.java               +5 (persist/find hit/find miss/remove/flush)
    ├── DirtyCheckingIntegrationTest.java           +3 (단일 dirty / 다중 dirty / no dirty)
    ├── LazyLoadingIntegrationTest.java             +4 (LAZY 첫 호출 / EAGER 별도 SELECT / LAZY 안 호출 / EAGER+LAZY 혼합)
    ├── IdentityMapIntegrationTest.java             +3 (find 두 번 == / proxy ↔ 원본 == / EM 다르면 ≠)
    ├── MergeIntegrationTest.java                   +3 (정상 + return 무시 함정 + snapshot 갱신)
    ├── LazyInitializationExceptionTest.java        +2 (tx 종료 후 / EM close 후 명시 호출)
    ├── TransactionRequiredExceptionTest.java       +1
    └── SequenceVsIdentityComparisonTest.java       +2 (정점 ① 정상 vs 깨짐 비교 박제)
```

**합계: 단위 33 + 통합 19 = 52건**. 목표 +40~55 부합.

### 7.3 통합 테스트 setup 패턴

```java
@TestInstance(PER_CLASS)
class BasicCrudIntegrationTest {
    private SfsEntityManagerFactory emf;
    private DataSource ds;
    private DataSourceTransactionManager tm;
    
    @BeforeAll
    void setup() {
        ds = h2DataSource("test-db", "classpath:orm-schema-test.sql");
        tm = new DataSourceTransactionManager(ds);
        emf = SfsEntityManagerFactory.builder()
            .dataSource(ds)
            .addEntityClass(User.class).addEntityClass(Order.class)
            .build();
    }
    
    @Test
    void persist_then_find_returns_same_instance() {
        var em = new SfsTransactionalEntityManager(emf);
        TransactionTemplate.execute(tm, () -> {
            User u = new User("Alice", "a@x.com");
            em.persist(u);
            assertThat(em.find(User.class, u.getId())).isSameAs(u);   // ← 학습 정점 ② 박제
        });
    }
}
```

> **TransactionTemplate 박제**: sfs-orm 통합 테스트에서 *@Transactional 사용 안 함* — sfs-context aspect가 inject되지 않은 *순수 sfs-tx 환경*에서 검증. `TransactionTemplate.execute()` 패턴은 sfs-tx에 이미 있음 (Phase 3).

### 7.4 회귀 카운트 추정 표

| 시작 | 목표 | 차이 | 분배 |
|---|---|---|---|
| **244 PASS** (Phase 1A gap 마감 시점) | **~296 PASS** | **+52** | sfs-orm 단위 33 + sfs-orm 통합 19 + **sfs-tx JdbcTemplate 신규 메서드 +2** (DoD 1.5) |

---

## 8. DoD (Definition of Done) — 14항목

| # | 항목 | 검증 방법 |
|---|---|---|
| 1 | sfs-orm 모듈 신설 + Gradle 의존 정합 | `./gradlew :sfs-orm:compileJava` PASS |
| 1.5 | sfs-tx `JdbcTemplate`에 `queryForObject` + `updateAndReturnKey` 신규 메서드 2종 추가 | sfs-tx 단위 테스트 +2 (별도 회귀 카운트 — § 7.4 +50에 미포함) |
| 2 | 어노테이션 6개 정의 (@SfsEntity, @SfsId, @SfsGeneratedValue, @SfsColumn, @SfsManyToOne, @SfsJoinColumn) | reflection으로 retention/target 검증 |
| 3 | 예외 4개 (`SfsPersistenceException` 계층) | 각 발생 시나리오 통합 테스트 |
| 4 | `EntityMetadataAnalyzer` 부팅 시 fail-fast (5종 검증) | 5개 단위 테스트 |
| 5 | `PersistenceContext` 1차 캐시 + snapshot + actionQueue | 6 단위 + 통합 검증 |
| 6 | `EntityPersister` 동적 SQL 생성 + dirty UPDATE | 5 단위 |
| 7 | `LazyProxyFactory` + `LazyInterceptor` (byte-buddy 서브클래싱) | 3+3 단위 + 통합 |
| 8 | `IdentityGenerator` / `SequenceGenerator` (I4 두 전략) | 2+2 단위 + 비교 통합 |
| 9 | `SfsTransactionalEntityManager` proxy + TSM 콜백 (L1) | tx 통합 테스트 + lifecycle 검증 |
| 10 | `SfsEntityManager` API 6개 (persist/find/remove/flush/merge/contains) 정상 동작 | BasicCrud + Merge 통합 |
| 11 | `OrmDemoApplication` main 메서드 console output 7개 시연 정상 | manual run 또는 integration smoke test |
| 12 | 회귀 +52 (244 → 296) PASS, 0 FAIL | `./gradlew build` |
| 13 | 마감 게이트 3단계 통과 (다관점 리뷰 + 리팩토링 + simplify 패스) | 게이트 기록 박제 |

---

## 9. 이월 박제 — Mini-phase 후보 (총 9건)

본 brainstorming 중 *학습 정점 인플레이션 회피*로 명시적으로 *별도 phase로 미룬* 것들:

| # | mini-phase 후보 | 출처 | 학습 정점 |
|---|---|---|---|
| **MP-1** | `merge` cascade + EntityListener (`@PrePersist`/`@PostLoad` 등) | Q6 (API2 한정) | EntityListener 콜백, deep merge 트리 처리 |
| **MP-2** | `@SfsOneToMany` 단방향 + collection lazy | Q2 (A3 미선택) | collection proxy, N+1 자연 노출, `PersistentBag/PersistentSet` |
| **MP-3** | 양방향 연관관계 + cascade + orphanRemoval | Q2 (A4 미선택) | mappedBy 의미, 양방향 일관성 책임 |
| **MP-4** | EAGER fetch JOIN + N+1 박제 | § 4.7 EAGER 별도 SELECT 결정 | fetch JOIN, *별도 SELECT vs JOIN trade-off* |
| **MP-5** | auto DDL 생성기 (Hibernate hbm2ddl 흉내) | Q9 (S1 한정) | type 매핑, CREATE/DROP 생성, FK 처리, validate 모드 |
| **MP-6** | classpath entity scanning (`@SfsEntityScan`) | Q11 (D1 한정) | ComponentScan의 entity 도메인 확장 |
| **MP-7** | sequence pre-allocation (`allocationSize`) | Q8 보너스 | batch insert 최적화 메커니즘 |
| **MP-8** | 낙관적 락 (`@SfsVersion`) | § 6.3 | concurrent UPDATE lost update 박제 |
| **MP-9** | resource-local mode (`em.getTransaction()`) | Q3 (L2 미선택) | JPA spec resource-local 모드 박제 |

**우선순위 *추천*** (Phase 4 마감 후 사용자가 선택할 때 참고):
- *학습 가치 1순위*: **MP-2** (collection lazy + N+1 자연 노출) — Phase 4의 자연 후속
- *학습 가치 2순위*: **MP-8** (낙관적 락) — concurrent 도메인 진입
- *학습 가치 3순위*: **MP-5** (auto DDL) — 어노테이션 ↔ schema 동기화 박제
- 그 외: *Phase 4 사용 경험에 따라 재평가*

### 9.1 Phase 1A gap "이월 박제" 자연 회수 점검

Phase 1A gap 메모리에 박제된 *후속 작업 (이월 박제)* 4건 중 본 phase에서 *자연 회수 가능한 것*:

| Phase 1A gap 이월 박제 | Phase 4에서 회수? |
|---|---|
| sfs-context `@Configuration`/`@Bean` 순환 의존 통합 테스트 | ✅ **본 phase에서 회수** — `OrmDemoApplication`의 `OrmConfig + AppConfig` 조합이 자연 시연 |
| enhanced 반환 정책 전환으로 DSBR 단순화 | ❌ **회수 안 됨** — sfs-aop 도메인, 본 phase 무관. 별도 mini-phase로 유지 |
| `@Aspect` + `@Transactional` 동시 enhance 시나리오 | ❌ **회수 안 됨** — 본 phase는 byte-buddy 직접 사용, AOP 무관 |
| prototype 빈 BPP 캐시 정책 | ❌ **회수 안 됨** — bean 생명주기 도메인, 본 phase는 entity 생명주기 |

> Phase 1A gap 이월 박제 4건 중 *1건 자연 회수* — 메모리 박제 정합.

---

## 10. spec ↔ 구현 매핑 (Plan 단계 입력)

다음 단계인 implementation plan 작성 시 *task 단위 매핑*의 토대:

| spec 섹션 | task 영역 추정 | 관련 컴포넌트 |
|---|---|---|
| § 2.1 ~ 2.3 | sfs-orm 모듈 신설 + 패키지 구조 + build.gradle.kts | (인프라) |
| § 2.4 + § 3.1 | `SfsEntityManager` 인터페이스 + `PersistenceContext` 자료구조 | A |
| § 3.2 | `EntityMetadata` + `EntityMetadataAnalyzer` (어노테이션 분석 + fail-fast) | B |
| § 2.5 + § 3.3 | `EntityPersister` (SQL 생성 + JdbcTemplate 호출) | C |
| § 3.5 | `IdentifierGenerator` 2 구현체 (IDENTITY/SEQUENCE) | D |
| § 4.1 + 4.2 | `persist()` flow (SEQUENCE + IDENTITY 분기) | E |
| § 4.3 | `find()` flow (cache hit/miss + lazy 채우기) | F |
| § 4.4 + 4.5 + 4.6 | `remove()`, `merge()`, `flush()` flow | G |
| § 3.4 | `LazyProxyFactory` + `LazyInterceptor` (byte-buddy) | H |
| § 2.5 boot/ + § 6.4 | `SfsEntityManagerFactoryBean` + `SfsTransactionalEntityManager` (TSM 통합) | I |
| § 5 | `OrmDemoApplication` + 도메인 + schema | J |
| § 6 | 예외 4개 + fail-fast 검증 | K |
| § 7 | 단위 테스트 33 + 통합 테스트 19 | (각 task에 분배) |
| § 8 | DoD 13항목 검증 | (마감 단계) |

---

## 11. 결정 기록 (브레인스토밍 세션 박제)

| 항목 | 값 |
|---|---|
| 브레인스토밍 세션 | 2026-05-04 |
| 결정 11건 | Q1~Q11 (§ 1) |
| 추천 정정 사이클 1건 | Q6 API1 → API2 (§ 1.1) |
| 디자인 섹션 5개 | 모두 사용자 OK |
| 비주얼 컴패니언 사용 | 없음 (ASCII 다이어그램으로 충분) |
| 다음 단계 | spec 사용자 리뷰 → writing-plans 스킬 invoke |
