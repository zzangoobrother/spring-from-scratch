# MP-3 mini-phase 설계 — 양방향 `mappedBy` + cascade + orphanRemoval

> **본 문서 위치:** MP-2 (`@SfsOneToMany` 단방향 + collection lazy) 완전 마감 이후 두 번째 mini-phase.
> MP-2 spec § 9 / Phase 4 spec § 9 박제 *MP-3 (양방향 + cascade + orphanRemoval, 1순위)*의 실행 단계.
> brainstorming 세션 결정(Q1~Q6) 기반.
>
> **선행 의존:** MP-2 (`feat/mp2-one-to-many`, 회귀 319 PASS / 0 FAIL, DoD 14/14). main 머지는 사용자 직접 진행 영역 — *구현 세션 진입 전 머지 권장*(현재 main은 Phase 4 `0336875`).
> **연관 plan:** `docs/superpowers/plans/2026-05-XX-mp3-bidirectional-cascade.md` (writing-plans 스킬로 작성 예정).
> **브랜치:** `feat/mp3-bidirectional-cascade` (MP-2 머지된 main에서 분기 예정).

---

## 0. 프로젝트 개요

### 0.1 한 줄 요약

`@SfsOneToMany`에 양방향 `mappedBy` + `cascade`(PERSIST/REMOVE) + `orphanRemoval`을 도입해, MP-2가 *의도적으로 심어둔 cascade 부재 학습 부채*를 회수한다. owning side(@SfsManyToOne)와 inverse side(컬렉션)의 책임 분리를 통해 **양방향 일관성은 application 책임**임을 박제하고(정점 ①), cascade로 영속성 그래프 전파를(정점 ②), orphanRemoval로 *컬렉션 변화 감지*를(정점 ③) 박제한다. 모든 메커니즘은 Hibernate 본가 동형 — byte-buddy 미사용, `SfsPersistentList`의 `storedSnapshot` diff(본가 `PersistentBag`)를 그대로 따른다.

### 0.2 학습 정점 3개

| 정점 | 박제 메커니즘 | 균열선 | 회수 위치 |
|---|---|---|---|
| **① mappedBy / 양방향 일관성** | `mappedBy="user"`는 컬렉션이 *inverse(읽기 매핑)* side임을 선언 → FK는 오직 owning side(`order.user`)가 결정. ORM은 양쪽 자동 동기화 *안 함* | "FK는 owning, 일관성은 application 책임" — `orders.add(o)`만 하고 `o.setUser(user)` 누락 시 cascade가 INSERT해도 **FK null** | 통합 `BidirectionalConsistencyIntegrationTest` + demo console(함정 → helper) |
| **② cascade PERSIST/REMOVE** | `persist(parent)`/`remove(parent)`가 cascade 관계를 *그래프 순회*하며 자식으로 전파. **managed 재persist도 cascade 재발화** → MP-2 부채(`add()+persist(user)`) 회수 | "전파는 호출 트리거 — managed 부모를 다시 persist하면 새 자식이 따라 들어간다" | 단위 `RealEntityManagerCascadeTest` + 통합 `CascadePersistIntegrationTest`/`CascadeRemoveIntegrationTest` |
| **③ orphanRemoval** | `SfsPersistentList`가 `initialize()` 시 `storedSnapshot` 보관. `flush()`가 `storedSnapshot − current` diff → 사라진 element를 orphan DELETE | "cascade(호출 전파)와 orphanRemoval(컬렉션 변화 감지)은 *다른 트리거*" | 단위 `SfsPersistentListOrphanTest` + 통합 `OrphanRemovalIntegrationTest` |

### 0.3 학습 짝패 회수 — MP-2 cascade 부채 정산

```
MP-2 (cascade 미도입 — 부채 박제)              MP-3 (cascade 도입 — 부채 회수)
──────────────────────────────              ──────────────────────────────
user.getOrders().add(o)                      user.addOrder(o)        // helper: 양쪽 세팅
em.persist(user)      →  o INSERT 안됨         em.persist(user)        // managed 재persist
em.persist(o) 별도 호출 →  o INSERT됨            → cascade PERSIST → o INSERT 자동
(UserService.tryAddOrderWithoutCascade)       (UserService 신 시나리오)
```

- MP-2 spec § 0.3에서 *시간축 짝패*(증상 → 해결, MP-2 → MP-3)로 예고된 회수를 본 phase가 정산.
- MP-2의 `tryAddOrderWithoutCascade()` console 메시지("MP-3에서 cascade=PERSIST로 자동화 예정")의 약속을 demo 코드로 *이행*.

> **메타 패턴**: mini-phase 시리즈는 *학습 부채를 의도적으로 만들고 다음 phase가 회수*하는 학습 곡선 설계 ([[project_mp2_design_resume_point]] 정합).

### 0.4 6단계 로드맵 위치

```
Phase 1 IoC ─► Phase 2 AOP ─► Phase 3 Tx ─► Phase 4 ORM ─► MP-2 ─► [MP-3] ─► MP-4 ─► ...
                                                                       ↑
                                                                   본 mini-phase
```

본가 JPA에서 *양방향 + cascade + orphanRemoval*은 컬렉션 매핑(MP-2) 직후의 자연 후속. owning/inverse 책임 분리는 JPA의 가장 빈번한 함정 지점이라 학습 가치가 높다.

### 0.5 자율 판단 / 자연 노출 예상 박제

mini-phase 단위에서도 *implementer 자율적 판단*([[feedback_implementer_autonomy]] 메모리 정합)이 발생할 수 있다. 본 spec은 다음을 *명시 박제*해 implementer가 자율 판단의 근거로 활용한다:

| 잠재 자율 판단 영역 | 본 spec의 입장 |
|---|---|
| cascade 사이클 가드를 `context.contains()`(PK 기반)로 할지 `IdentityHashMap`(참조 기반)으로 할지 | **`IdentityHashMap` visited(참조 기반)** — IDENTITY 전략 자식은 persist 전 PK가 없어 `contains()`가 무력. 단일 persist 호출 동안의 참조 추적이 안전. `contains()`는 *self-insert 스킵* 판단에만 사용 |
| orphanRemoval diff를 *PK 동등성*으로 할지 *참조 동등성*으로 할지 | **참조 동등성**(`storedSnapshot`이 보관한 인스턴스 vs 현재 인스턴스) — identityMap이 1 entity = 1 instance를 보장하므로 참조 비교로 충분. `equals/hashCode` 의존 회피(엔티티가 재정의 안 했을 수 있음) |
| cascade REMOVE 시 자식 컬렉션을 *강제 초기화*할지 | **강제 초기화**(`for child in coll` 진입이 lazy 발화) — 본가 정합. 미초기화 컬렉션은 자식을 모르므로 cascade 불가 |
| flush-time auto-cascade-persist(컬렉션에 add 후 persist 호출 없이 flush만으로 cascade) 도입 여부 | **미도입**(§ 1.2 의식적 단순화) — MP-2 demo가 `persist(user)`를 명시하므로 cascade-on-persist로 충분. flush-only 경로는 향후 mini-phase |

---

## 1. 의사결정 로그

### 1.1 Q1~Q6 결정 + trade-off 박제

| Q | 결정 | 선택지 미선택 사유 |
|---|---|---|
| **Q1 범위** | 풀 번들 — 양방향 + cascade(PERSIST/REMOVE) + orphanRemoval (학습 정점 3개) | orphanRemoval 제외(B): "컬렉션 변화 → DB" 이야기 미완결 / 양방향만(C): MP-2 cascade 부채 미회수 — 짝패 정산 지연 |
| **Q2 매핑 모델** | `mappedBy` XOR `joinColumn` — 둘 다 지원, 정확히 하나 강제(검증). `User.orders`는 `mappedBy="user"`로 진화 | joinColumn 전면 교체(B): MP-2 단방향 fixture/테스트 손상 + *단방향 학습 산출물 소멸* — 시간축 짝패 보존 약화 |
| **Q3 orphan 탐지** | 컬렉션 snapshot diff (Hibernate `PersistentBag` 동형) — `storedSnapshot − current` | op-tracking(B): 본가와 다른 메커니즘, `set()`/`addAll` 재할당 조합 시 누락. `SfsPersistentList`가 이미 PersistentBag 동형이라 박제됨 |
| **Q4 양방향 일관성** | 본가 충실 — application 책임. cascade는 inverse 컬렉션 순회, FK는 owning side에서 읽음. owning 미설정 → FK null 함정 박제 + helper 시연 | ORM auto-sync(B): `add()`가 owning side 자동 설정 — 편의↑이나 owning/inverse 구분 의미 소멸, "일관성 책임" 학습 정점 증발, 본가 충실도 저하 |
| **Q5 로직 배치** | EntityManager 중심 — `persist`/`remove`가 그래프 순회+재귀, `flush`가 orphan diff | ActionQueue 후처리(B): cascade가 enqueue 경로에 흩어짐, 추적난 / EntityPersister 내부(C): SQL 실행 + 그래프 순회 두 책임 혼선 |
| **Q6 cascade 표면** | `SfsCascadeType { PERSIST, REMOVE, ALL }`, `@SfsOneToMany.cascade()` 한정, default `{}` | PERSIST/REMOVE만(B): ALL 편의 부재. MERGE/DETACH/REFRESH는 MP-1/향후 영역 — 본 phase 범위 밖 |

### 1.2 의식적 단순화 박제 (scope cuts)

| 단순화 | 사유 | 회수 위치 |
|---|---|---|
| **flush-time auto-cascade-persist 미도입** | 본가는 managed 컬렉션에 add 후 flush만으로도 cascade하지만, MP-2 demo가 `persist(user)`를 명시하므로 cascade-on-persist로 짝패 회수 충분. 단순성 우선 | 향후 mini-phase 후보 (§ 9) |
| **cascade MERGE/DETACH/REFRESH 미도입** | merge cascade는 Phase 4 spec § 9의 MP-1 후보로 이미 분리 박제 | MP-1 |
| **ManyToOne cascade 미도입** | 본 phase는 OneToMany(컬렉션) 방향 cascade에 집중 — owning side cascade는 학습 가치 중복 | 향후 |
| **양방향 자동 동기화 미도입** | Q4 결정 — application 책임이 학습 정점 ① 자체 | (의도적 영구 미도입) |

### 1.3 채택 안 = Hibernate 본가 정합

- `mappedBy` = inverse side, owning = FK 보유 `@ManyToOne` — JPA spec 정의 그대로.
- `orphanRemoval`을 collection snapshot diff로 — Hibernate `AbstractPersistentCollection.getOrphans` 동형.
- 양방향 일관성 = application 책임 — Hibernate 공식 권고(helper 메서드 패턴) 그대로.

---

## 2. 아키텍처 & 모듈 구조

### 2.1 모듈 의존 (불변)

```
sfs-samples ──► sfs-context ──► sfs-beans ──► sfs-core
       └──────────────► sfs-orm ◄── (cascade/orphan 로직은 sfs-orm 내부 완결)
```
MP-3는 `sfs-orm` 내부 + `sfs-samples` demo만 변경. 역방향 의존 없음.

### 2.2 변경/신설 파일 (production)

| 파일 | 구분 | 변경 요지 |
|---|---|---|
| `annotation/SfsCascadeType.java` | **신설** | `enum { PERSIST, REMOVE, ALL }` |
| `annotation/SfsOneToMany.java` | 변경 | `mappedBy`/`cascade`/`orphanRemoval` 추가, `joinColumn` default `""` 완화 |
| `support/CollectionMetadata.java` | 변경 | `mappedBy`, `Set<SfsCascadeType> cascadeTypes`, `boolean orphanRemoval` 추가 + `cascadesPersist()`/`cascadesRemove()` 헬퍼(ALL 해석) |
| `support/EntityMetadataAnalyzer.java` | 변경 | XOR 검증 + mappedBy 해석(owning `@SfsManyToOne`의 `@SfsJoinColumn`에서 FK 도출, targetEntity 일치 검증) |
| `support/SfsPersistentList.java` | 변경 | `initialize()` 시 `storedSnapshot` 보관 + 패키지 전용 `findOrphans()` |
| `RealEntityManager.java` | 변경 | `persist` cascade PERSIST(재귀+사이클 가드), `remove` cascade REMOVE(자식 먼저), `flush` orphanRemoval diff |

> `CollectionLoader`/`DefaultCollectionLoader`/`EntityPersister`는 **무변경** — `joinColumnName`이 양방향에서도 동일하게 채워지므로 로딩 경로 재사용.

### 2.3 신설/재사용 파일 (test 전용)

- `SqlCountingJdbcTemplate` — MP-2 신설분 **재사용**(INSERT/DELETE 카운트 단언).
- `AbstractOrmIntegrationTest` — **재사용**(nanoTime URL 격리, `emf.close()` 부재 존중).
- 통합 fixture 엔티티는 `public static class` + 직접 접근 필드 `public`([[project_mp2_design_resume_point]] I-1 학습 — `EntityPersister`가 다른 패키지 package-private fixture 인스턴스화 시 `IllegalAccessException`).

### 2.4 demo 도메인 확장

- `domain/User.java` — `orders`를 `@SfsOneToMany(mappedBy="user", cascade={PERSIST,REMOVE}, orphanRemoval=true)`로 마이그레이션 + `addOrder/removeOrder` helper.
- `domain/Order.java` — `setUser` 세터 확인(owning side 설정용).
- `service/UserService.java` + `OrmDemoApplication.java` — 시연 DK~DN.

---

## 3. 핵심 컴포넌트

### 3.1 `SfsCascadeType` enum (신설)

```java
public enum SfsCascadeType { PERSIST, REMOVE, ALL }
```
`ALL`은 `cascadesPersist()`/`cascadesRemove()` 헬퍼에서 PERSIST/REMOVE 양쪽으로 해석(데이터 컨테이너 — TDD 제외).

### 3.2 `@SfsOneToMany` 확장

```java
@Retention(RUNTIME) @Target(FIELD)
public @interface SfsOneToMany {
    FetchType fetch() default FetchType.LAZY;     // LAZY only (MP-2-α 이월)
    String joinColumn()  default "";              // 단방향(MP-2) — XOR
    String mappedBy()    default "";              // 양방향 — owning @ManyToOne 필드명
    SfsCascadeType[] cascade() default {};
    boolean orphanRemoval() default false;
    enum FetchType { LAZY }
}
```

### 3.3 `CollectionMetadata` record 확장

```java
public record CollectionMetadata(
        Field field,
        Class<?> elementType,
        String joinColumnName,            // 단방향=직접 / 양방향=owning에서 도출 (로더 무변경)
        String mappedBy,                  // "" = 단방향
        Set<SfsCascadeType> cascadeTypes,
        boolean orphanRemoval) {
    public boolean cascadesPersist() {
        return cascadeTypes.contains(SfsCascadeType.PERSIST) || cascadeTypes.contains(SfsCascadeType.ALL);
    }
    public boolean cascadesRemove() {
        return cascadeTypes.contains(SfsCascadeType.REMOVE) || cascadeTypes.contains(SfsCascadeType.ALL);
    }
}
```

### 3.4 `EntityMetadataAnalyzer` 확장 — XOR 검증 + mappedBy 해석

분석 시점(부팅) fail-fast:
1. **XOR**: `mappedBy`/`joinColumn` 둘 다 비었거나 둘 다 채워짐 → `SfsEntityMappingException`("정확히 하나만 지정").
2. **단방향**(joinColumn): MP-2 경로 그대로 — `joinColumnName = joinColumn`.
3. **양방향**(mappedBy):
   - element 타입(Order)에서 `mappedBy` 이름의 필드 탐색 → 없으면 예외.
   - 그 필드에 `@SfsManyToOne` + `@SfsJoinColumn` 없으면 예외.
   - `@SfsJoinColumn.name`을 `joinColumnName`으로 채택.
   - owning 필드 타입(또는 RelationMetadata.targetEntity)이 owner 엔티티 클래스와 불일치 → 예외.

> *행위(분기·예외)가 본질 → TDD 적용 대상.*

### 3.5 `RealEntityManager` — cascade persist/remove

**persist (cascade PERSIST):**
```
persist(entity):                      // SfsEntityManager 진입점
  doPersist(entity, new IdentityHashMap<>())

doPersist(entity, visited):
  if (!visited.add(entity)) return                       // 사이클/중복 가드 — 그래프 전체에 걸쳐 visited 공유(참조 기반)
  if (!context.contains(key(entity))) { 기존 self-insert (SEQUENCE/IDENTITY 분기) 그대로 }
  for cm in md.oneToManies where cm.cascadesPersist():    // managed든 아니든 cascade
    coll = cm.field.get(entity)
    if (coll == null) continue
    for child in coll: doPersist(child, visited)          // 같은 visited 공유 — managed child는 self-insert 스킵
```
- **visited는 단일 persist 호출의 그래프 전체에서 공유** — 재귀마다 새로 만들면 진짜 순환(user→order→user, 양방향 모두 cascade일 때)을 못 막으므로 threaded. 본 phase 도메인은 OneToMany만 cascade라 실제 순환은 없으나 *방어적 정합*.
- **managed 재persist도 cascade** → MP-2 부채 회수의 핵심.
- self-insert 스킵(managed 가드)은 MP-2 G2 C-1 결함(managed 재persist 중복 INSERT)의 정식 해소 — demo 가드를 production 가드로 승격.

**remove (cascade REMOVE):**
```
remove(entity):
  md = ...; key = ...; if (!context.contains(key)) throw IllegalArgument
  for cm in md.oneToManies where cm.cascadesRemove():
    coll = cm.field.get(entity)                          // 강제 lazy 초기화
    if (coll != null) for child in coll: remove(child)   // 자식 DeleteAction 먼저
  enqueueAction(new DeleteAction(entity, md))            // 부모는 마지막
```
- 자식 먼저 enqueue + `flush`의 `actions.sort`가 **stable**(`List.sort`)이라 같은 DELETE 타입 내 삽입 순서 보존 → FK 제약(`child.user_id → user`) 충족.

### 3.6 `SfsPersistentList` — storedSnapshot + orphan

```java
private List<T> storedSnapshot;   // initialize() 시 로드 직후 사본

private void initialize() {
    if (delegate != null) return;
    if (context.isClosed()) throw new SfsLazyInitializationException(...);
    delegate = loader.loadCollection(...);
    storedSnapshot = new ArrayList<>(delegate);    // 본가 PersistentBag.storedSnapshot 동형
}

/** flush의 orphan diff용 — storedSnapshot에는 있으나 현재 delegate에 없는 element(참조 기반). */
List<T> findOrphans() {
    if (delegate == null) return List.of();         // 미초기화 → 변화 없음
    List<T> orphans = new ArrayList<>();
    for (T e : storedSnapshot)
        if (!containsByIdentity(delegate, e)) orphans.add(e);
    return orphans;
}
```
- 참조 동등성(§ 0.5) — identityMap 1:1 보장 전제. `equals` 의존 회피.

### 3.7 `RealEntityManager.flush` — orphanRemoval 단계

기존 dirty-check 루프 뒤, action 실행 전에 삽입:
```
for (key, entity) in identityMap:
  md = metadataOf(key)
  for cm in md.oneToManies where cm.orphanRemoval:
    coll = cm.field.get(entity)
    if (coll instanceof SfsPersistentList p):
      for orphan in p.findOrphans():
        if (context.contains(key(orphan)) && !alreadyPendingDelete(orphan)):
          enqueueAction(new DeleteAction(orphan, metadataOf(orphan)))
```
- **cascade REMOVE 중복 가드**: 이미 DeleteAction이 큐에 있는 orphan은 재등록 안 함(`alreadyPendingDelete`).

---

## 4. 데이터 플로우

### 4.1 cascade PERSIST — `user.addOrder(o); em.persist(user)`
1. `addOrder` helper가 `orders.add(o)` + `o.setUser(user)` (양쪽 세팅 — application 책임).
2. `persist(user)`: user는 managed(이미 영속) → self-insert 스킵, cascade 진행.
3. cascade: `orders` 순회 → `persist(o)`. o는 미관리 → SEQUENCE id 채움 + InsertAction 등록.
4. `flush()`: o의 INSERT 실행. `o.user`가 set돼 있으므로 FK(user_id) 정상 기록.

### 4.2 cascade REMOVE + 삭제 순서 — `em.remove(user)`
1. `remove(user)`: cascadesRemove → `orders` 강제 초기화 → 각 `remove(order)`가 DeleteAction(order) 먼저 enqueue.
2. 마지막에 DeleteAction(user) enqueue.
3. `flush()`: stable sort로 DELETE 그룹 내 순서 보존 → `DELETE order...` → `DELETE user` → FK 제약 충족.

### 4.3 orphanRemoval — `user.removeOrder(o); em.flush()`
1. `removeOrder` helper가 `orders.remove(o)` + `o.setUser(null)`.
2. `flush()` orphan 단계: `orders.findOrphans()` = `{o}` (storedSnapshot에 있으나 현재 없음) → DeleteAction(o).
3. `DELETE order WHERE id=?` 실행.

### 4.4 양방향 일관성 함정 (학습 정점 ①)
```
user.getOrders().add(o);   // inverse만 — helper 미사용
em.persist(user);          // cascade → persist(o) → INSERT
  → 그러나 o.user == null → user_id FK = NULL  ← 함정
```
demo는 함정 발화 후 helper(`addOrder`)로 교정하는 대비를 console에 박제.

---

## 5. Demo 시나리오 — `OrmDemoApplication` / `UserService` 확장

### 5.1 도메인 마이그레이션
```java
// User
@SfsOneToMany(mappedBy = "user", cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
private List<Order> orders = new ArrayList<>();

public void addOrder(Order o)    { orders.add(o); o.setUser(this); }
public void removeOrder(Order o) { orders.remove(o); o.setUser(null); }
```
> MP-2 단방향 fixture/통합 테스트는 `joinColumn` 경로로 그대로 PASS(XOR 공존).

### 5.2 신설 시연 (Phase 4 DA~DG / MP-2 DH~DJ 보존, 신설 DK~DN)

| 코드 | 시나리오 | 학습 정점 |
|---|---|---|
| **DK** | `user.addOrder(newOrder); em.persist(user)` → newOrder 자동 INSERT (MP-2 DJ 업그레이드) | ② cascade PERSIST — 부채 회수 |
| **DL** | helper 없이 `getOrders().add(o)` + persist → FK null 함정, 이후 helper로 교정 | ① 양방향 일관성 |
| **DM** | `em.remove(user)` → orders까지 DELETE (자식 먼저) | ② cascade REMOVE |
| **DN** | `user.removeOrder(o); em.flush()` → o만 DELETE | ③ orphanRemoval |

각 시연은 `SqlCountingJdbcTemplate`로 INSERT/DELETE 카운트를 단언(눈 + 회귀 동시 박제).

### 5.3 MP-2 `tryAddOrderWithoutCascade` 처리
- console 메시지의 "MP-3에서 자동화 예정" 약속이 DK로 *이행*됨을 demo 흐름에 명시.
- 메서드 자체는 *단방향 학습 산출물*로 존치할지 제거할지 — **존치**(MP-2 시간축 짝패의 "증상" 측 보존). DK가 "해결" 측.

---

## 6. 에러 처리 정책

### 6.1 신설 예외 0개 — 기존 인프라 재사용
- 매핑 오류 → `SfsEntityMappingException`(기존). lazy/closed → `SfsLazyInitializationException`(기존). persist/remove 오류 → `SfsPersistenceException`(기존).

### 6.2 fail-fast 시점
- XOR 위반 / mappedBy 해석 실패 → **부팅 시**(EntityMetadataAnalyzer) 즉시 예외.
- detached cascade remove → 기존 `remove`의 managed 가드가 자식에도 적용(미관리 자식 remove 시 IllegalArgument).

---

## 7. 테스트 전략 + 회귀 카운트 추정

### 7.1 TDD 적용/제외

| 대상 | TDD | 근거 |
|---|---|---|
| `EntityMetadataAnalyzer` XOR/mappedBy 해석 | **적용** | 분기·예외 — 안전망 부재 |
| `RealEntityManager` cascade persist(재귀/managed/사이클) | **적용** | 그래프 순회 + 상태 변경 |
| `RealEntityManager` cascade remove(삭제 순서) | **적용** | FK 순서 보장 로직 |
| `RealEntityManager.flush` orphanRemoval diff | **적용** | 변화 감지 + 중복 가드 |
| `SfsPersistentList.findOrphans`/storedSnapshot | **적용** | 알고리즘(diff) |
| `SfsCascadeType` enum, `CollectionMetadata` record | **제외** | 데이터 컨테이너 — analyzer 테스트가 경유 |
| 도메인 helper(`addOrder`) | **제외**(통합 경유) | 단순 양쪽 세팅 — 통합 함정 테스트가 검증 |

### 7.2 회귀 카운트 추정 (319 → ~335)

| 영역 | 신규 테스트(추정) |
|---|---|
| analyzer XOR/mappedBy | ~4 (both/neither/정상해석/targetEntity 불일치) |
| cascade persist | ~3 (기본/managed 재persist 회수/사이클) |
| cascade remove | ~2 (cascade/삭제 순서) |
| orphanRemoval 단위 | ~3 (diff/미초기화 skip/cascade 중복 가드) |
| SfsPersistentList orphan | ~2 (storedSnapshot/findOrphans) |
| 통합 | ~4 (FK null 함정/cascade persist E2E/cascade remove E2E/orphan E2E) |
| **합계** | **~18 → 319 + 18 = ~337** (plan에서 확정) |

### 7.3 통합 테스트 시나리오 매핑
- `BidirectionalConsistencyIntegrationTest` — 정점 ①(FK null 함정 + helper 교정).
- `CascadePersistIntegrationTest` / `CascadeRemoveIntegrationTest` — 정점 ②(SqlCounting으로 INSERT/DELETE 카운트 + 순서).
- `OrphanRemovalIntegrationTest` — 정점 ③.

---

## 8. Definition of Done — 14 항목

- [x] 1. `SfsCascadeType` enum 신설 (PERSIST/REMOVE/ALL)
- [x] 2. `@SfsOneToMany`에 mappedBy/cascade/orphanRemoval 추가, joinColumn default `""` 완화
- [x] 3. `CollectionMetadata` 확장 + `cascadesPersist()`/`cascadesRemove()` 헬퍼
- [x] 4. `EntityMetadataAnalyzer` XOR 검증 (both/neither → `SfsEntityMappingException`)
- [x] 5. `EntityMetadataAnalyzer` mappedBy 해석 (owning `@SfsManyToOne` `@SfsJoinColumn`에서 FK 도출 + targetEntity 일치 검증)
- [x] 6. `RealEntityManager.persist` cascade PERSIST (managed 재persist 포함, `IdentityHashMap` 사이클 가드)
- [x] 7. `RealEntityManager.remove` cascade REMOVE (자식 먼저 enqueue → FK 순서, stable sort)
- [x] 8. `SfsPersistentList` storedSnapshot 보관 + `findOrphans`
- [x] 9. `RealEntityManager.flush` orphanRemoval diff (cascade remove 중복 가드)
- [x] 10. 도메인 마이그레이션 (`User.orders` mappedBy + `addOrder`/`removeOrder` helper)
- [x] 11. demo 시연 DK~DN + `SqlCountingJdbcTemplate` 카운트 단언
- [x] 12. 단위 테스트 (analyzer/cascade/orphan/SfsPersistentList) — TDD 적용분 전수
- [x] 13. 통합 테스트 (양방향 함정/cascade persist·remove E2E/orphan E2E)
- [x] 14. `./gradlew build` 전체 PASS (회귀 319 → **343 실측**, MP-3 신규 +24 전부 sfs-orm: 계획 +23 + 마감 게이트 cycle 가드 테스트 +1) + 마감 게이트(다관점 리뷰 3 + 리팩토링 + `/simplify` 2) 기록 박제 — plan 하단 품질 게이트 기록 참조

---

## 9. 이월 박제 — Mini-phase 후보 갱신

### 9.1 MP-3가 회수한 학습 부채
- ✅ **MP-2 cascade 부재**(DJ `tryAddOrderWithoutCascade`) → DK cascade PERSIST로 회수.
- ✅ **양방향 일관성**(MP-2 § "시연 없음, MP-3 주제") → 정점 ① + DL 함정.
- ✅ **컬렉션 dirty 처리**(MP-2 위임) → orphanRemoval(정점 ③). *단, 부모 entity dirty가 아니라 컬렉션 자체 diff*로 해소(inverse는 FK 미보유 → 부모 dirty 불필요)임을 박제.

### 9.2 남은/신설 후보
| # | 후보 | 사유 |
|---|---|---|
| MP-2-α | EAGER collection fetch | MP-2 Q3 |
| MP-1 | merge cascade + EntityListener | Phase 4 § 9 |
| **MP-3-α (신설)** | flush-time auto-cascade-persist | § 1.2 단순화 — managed 컬렉션 add 후 flush-only cascade |
| MP-4 | EAGER fetch JOIN | Phase 4 § 9 |
| MP-8 | 낙관적 락(`@SfsVersion`) | Phase 4 § 9 (2순위) |

---

## 10. 부록 — MP-2 / Phase 4 시그니처 정합 점검

| 재사용 대상 | 시그니처 | MP-3 영향 |
|---|---|---|
| `CollectionLoader.loadCollection(elementType, joinColumnName, ownerPk, context)` | 무변경 | 양방향도 joinColumnName 채워짐 |
| `PersistenceContext.enqueueAction/contains/identityMap` | 무변경 | cascade/orphan이 그대로 사용 |
| `EntityPersister.executeInsert/executeDelete` | 무변경 | cascade는 action만 추가, 실행은 기존 |
| `AbstractOrmIntegrationTest` (nanoTime URL 격리) | 무변경 | `emf.close()` 부재 존중 ([[project_mp2_design_resume_point]]) |
| `SqlCountingJdbcTemplate` | 무변경 | INSERT/DELETE 카운트 단언 재사용 |

---

## 11. 다음 단계

1. 본 spec self-review (placeholder/일관성/scope/모호성) — 작성 직후 인라인 수정.
2. 사용자 spec 리뷰 게이트.
3. 승인 시 `superpowers:writing-plans`로 구현 plan 작성(체크박스 단위) — *구현은 별도 세션*([[feedback_design_implementation_session_split]]).
4. 구현 세션 진입 전 MP-2 → main 머지(사용자 직접).
