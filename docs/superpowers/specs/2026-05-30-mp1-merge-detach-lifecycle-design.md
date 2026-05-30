# MP-1 merge/detach 준영속 생명주기 — Design Spec

> **상태:** 디자인 확정 (2026-05-30). 구현은 별도 세션 권장 ([[feedback_design_implementation_session_split]]).
> **선행:** Phase 4 ORM → MP-2 단방향 → MP-3 양방향+cascade (all main 머지 완료, `326d576`). 현재 회귀 **343 PASS**.
> **브랜치:** `feat/mp1-merge-detach-lifecycle` (main 기준 분기, MP-3까지 main 반영 완료된 깨끗한 출발점).

---

## 0. 한 줄 요약

MP-3가 만든 **cascade 4종 골격**(`SfsCascadeType`)에서 MERGE/DETACH 2종을 회수하고, EntityManager의 **준영속(detached) 생명주기**(`detach`/`clear` + merge 그래프 전파)를 완성한다. EntityListener·refresh는 의도적 학습 부채로 분리 박제.

---

## 1. 배경 — 현재 구현 실측

구현 진입 전 코드 실측으로 *진짜 빠진 것*을 확정했다 (메인 선조사가 디자인을 바꾼 사례).

### 1.1 merge는 이미 부분 구현됨 (`RealEntityManager:508-541`)

- 스칼라 컬럼 복사 ✓
- **`@SfsManyToOne` 연관 참조 복사** ✓ (line 528-530)
- 캐시 miss 시 `find()`로 DB 로드 ✓
- snapshot 갱신 ✓
- 학습 정점 ①("인자로 넘긴 detached 인스턴스는 여전히 detached")이 **이미 Javadoc에 박제**됨 (line 500)
- 기존 `MergeIntegrationTest` 존재

→ **진짜 빠진 것:** merge가 `@SfsOneToMany` 컬렉션을 **cascade=MERGE로 전파하지 않음** (자식 그래프 미순회).

### 1.2 detach/clear는 전무

- `SfsEntityManager` 인터페이스에 `detach`/`clear` 없음.
- `PersistenceContext`에 **단일 키 제거(`removeEntity`)도, 비-종료 비우기(non-closing clear)도 없음.** `close()`는 맵을 비우되 `closed=true`로 컨텍스트를 죽이므로 `em.clear()`(비우되 계속 사용)와 의미가 다르다.

### 1.3 의식적 단순화 (범위 밖 결정)

| 단순화 | 이유 | 이월처 |
|---|---|---|
| `refresh()` 미도입 | DB→메모리 재로드(REFRESH)는 detach/merge와 다른 축. 응집 우선 | **MP-1-γ** 신설 |
| EntityListener 미도입 | "생명주기 콜백"은 독립 개념. Phase 2B→이월→응집 패턴 계승 | **MP-1-β** 신설 |
| 비-cascade 참조의 managed 치환(풀 JPA merge) 미도입 | MP-3 orphanRemoval과 의미 중복 → 학습 노이즈 | 영구 박제 |
| ManyToOne cascade 미도입 | MP-3와 동일 결정 — 컬렉션 방향 cascade에 집중 | 영구 박제 |

---

## 2. 학습 정점 (4개)

> 디자인 세션에서 4개 전부 채택. ①③은 *기존 코드/예외를 통합 시나리오로 승격*, ②④는 신규 구현.

1. **merge 복사 의미론** — `merged != detached` (인자는 여전히 준영속). merge는 detached를 managed로 *전환*하는 게 아니라 별도 managed 인스턴스에 *복사*하고 그것을 반환한다. JPA 초보 최대 오해 지점.
2. **detach → dirty checking 중단** — detach가 snapshot을 제거하면 flush 시 변경 비교 기준선이 사라져 UPDATE가 발생하지 않는다. "준영속 = 변경 추적 끊김"의 메커니즘 박제.
3. **detach 후 lazy 접근 → `SfsLazyInitializationException`** — 준영속 + lazy의 그 유명한 조합. 기존 closed-context 예외 경로 재사용.
4. **cascade MERGE 그래프 전파 + 사이클 가드** — MP-3 `visited` IdentityHashMap 패턴 **3번째 재사용**. cascade 4종(PERSIST/REMOVE/MERGE/DETACH)이 *동일 골격*임을 완성.

---

## 3. API 표면 변경

### 3.1 `SfsEntityManager` 인터페이스 (+2)

```java
/** managed → 준영속. snapshot 제거로 dirty checking 중단. cascade=DETACH 전파. */
void detach(Object entity);

/** 전체 준영속 — 1차 캐시·snapshot·펜딩 액션 비우되 컨텍스트는 open 유지(close ≠ clear). */
void clear();
```

### 3.2 `SfsCascadeType` (+2)

```java
public enum SfsCascadeType { PERSIST, REMOVE, MERGE, DETACH, ALL }
```

> **설계 의도 박제:** `ALL`을 끝에 둔 채 MERGE/DETACH를 끼워넣으면 enum ordinal이 변하지만, 본 프로젝트는 `Set<SfsCascadeType>.contains()` 기반이라 ordinal 의존 코드가 없어 안전. `ALL`은 4종 모두를 의미한다.

### 3.3 `CollectionMetadata` 헬퍼 (+2, MP-3 cascadesPersist/Remove와 대칭)

```java
public boolean cascadesMerge()  { return cascadeTypes.contains(MERGE)  || cascadeTypes.contains(ALL); }
public boolean cascadesDetach() { return cascadeTypes.contains(DETACH) || cascadeTypes.contains(ALL); }
```

---

## 4. 내부 동작 설계

### 4.1 `PersistenceContext` 확장 (+2 메서드)

```java
/** 단일 엔티티를 1차 캐시·snapshot에서 제거 (detach 기반). */
public void removeEntity(EntityKey key) {
    ensureOpen();
    identityMap.remove(key);
    snapshots.remove(key);
}

/** 1차 캐시·snapshot 전체 비우기 — close()와 달리 closed=false 유지 (clear 기반). */
public void detachAll() {
    ensureOpen();
    identityMap.clear();
    snapshots.clear();
}
```

> **`detachAll` vs `close` 비대칭:** `close()`는 `closed=true`로 컨텍스트를 죽여 이후 getEntity가 silent null·lazy 접근이 예외가 된다. `detachAll()`은 살아있는 컨텍스트를 비울 뿐이라 직후 persist/find가 정상 동작한다. 이 비대칭이 "clear ≠ close" 학습 포인트(데모 DS). 둘 다 write 경로이므로 `ensureOpen()` 통과 — 기존 규약 일관.

### 4.2 `detach(entity)` — managed → 준영속

```
detach(p):
  pk = read @SfsId
  if pk == null: return                 // 신규(미영속) → no-op (JPA 정합)
  key = EntityKey(class, pk)
  if !context.contains(key): return     // 이미 준영속/미관리 → no-op
  ── [D1] 펜딩 액션 취소: actionQueue에서 이 인스턴스 대상 액션 제거
  context.removeEntity(key)             // snapshot 제거 → dirty checking 끊김 (정점 ②)
  ── cascade DETACH: @SfsOneToMany(cascadesDetach()) 자식 재귀, visited 가드
```

#### 결정 D1 — detach가 펜딩 InsertAction을 취소한다 (채택: (a))

`flush` 전에 `persist(p)` → `detach(p)`하면 `removeEntity`는 캐시만 지우고 actionQueue의 InsertAction은 남아 flush 때 **유령 INSERT**가 발생한다.

- **(a) 펜딩 액션도 제거 — 채택.** JPA 정합(준영속이 된 new 엔티티의 persist는 취소). `EntityAction.entity()` 접근자가 이미 존재(MP-3 테스트의 `InsertAction ia && ia.entity()==p` 패턴)하여 저비용. "detach는 진행 중 작업도 되돌린다" 학습 포인트.
- (b) 한계 박제("detach는 flush 완료 managed 전용") — 기각. 유령 INSERT 함정 잠복.

> 구현 위치: `PersistenceContext`에 `removeEntity`가 actionQueue purge까지 담당할지, `RealEntityManager.detach`가 별도로 처리할지는 plan에서 결정. snapshot 제거와 action purge를 한 메서드에 묶으면 응집↑, 분리하면 PC 책임 단순. **권장: PC에 `removePendingActions(Object entity)` 별도 메서드** — `removeEntity`(키 기반)와 action purge(인스턴스 기반)는 식별 단위가 달라 분리가 자연스럽다.

### 4.3 `clear()` — 전체 준영속

```
clear():  context.detachAll()           // 캐시·snapshot 비우기
          context.clearActionQueue()    // 펜딩 액션 전량 폐기 (D1과 일관)
```

### 4.4 merge cascade — `@SfsOneToMany(cascade=MERGE)` 그래프 전파

기존 merge(스칼라+ManyToOne 복사)에 컬렉션 순회를 추가하고 MP-3 `doPersist`처럼 `visited` 가드로 재구성:

```java
public <T> T merge(T entity) { return (T) doMerge(entity, new IdentityHashMap<>()); }

private Object doMerge(Object entity, Map<Object,Boolean> visited) {
    Object key = keyOf(entity);
    if (visited.put(entity, TRUE) != null) return context.getEntity(key);  // 사이클 가드
    // ── 기존: managed 확보(캐시 or find) + 스칼라/ManyToOne 복사 + snapshot 갱신 ──
    for (CollectionMetadata cm : md.oneToManies()) {
        if (!cm.cascadesMerge()) continue;
        Object coll = readField(cm.field(), entity);
        if (coll == null) continue;                         // null 컬렉션 가드 (doPersist 대칭)
        List<Object> managedChildren = new ArrayList<>();
        for (Object child : (Iterable<?>) coll) {
            managedChildren.add(doMerge(child, visited));   // 자식도 managed로 치환
        }
        cm.field().set(managed, rebuildCollection(managedChildren));  // managed 컬렉션 재구성
    }
    return managed;
}
```

> **컬렉션 재구성 필수 이유:** 자식만 재귀 merge하면 부모(managed)의 컬렉션은 여전히 detached 자식을 가리킨다. `managedChildren`으로 컬렉션을 재구성해야 반환 그래프 전체가 managed가 되어 정점 ①이 자식까지 재귀 성립.
> **사이클 가드 반환값:** merge는 값을 반환하므로(persist=void와 차이) 이미 방문한 엔티티는 `context.getEntity(key)`(=managed 인스턴스)를 돌려줘야 양방향에서 일관된 managed 참조가 유지된다. cascade 4종 동일 골격이지만 반환 타입 차이가 만드는 미묘한 분기.
> **@ManyToOne 역참조는 치환하지 않는다:** 자식의 부모 back-reference(@ManyToOne)는 기존 merge 로직대로 detached 값 그대로 복사된다(§1.3 "비-cascade 참조 managed 치환 미도입"). 따라서 merge 후 부모→자식 컬렉션은 managed로 재구성되지만 자식→부모 참조는 detached로 남을 수 있다. **양방향 일관성은 application 책임**(MP-3 정합) — 데모/테스트는 양방향 helper로 세팅하므로 실무 무영향. 이 비대칭을 테스트 주석에 박제한다.

---

## 5. 테스트 전략

### 5.1 TDD 적용/제외 (CLAUDE.md "TDD 적용 가이드")

| 대상 | 판단 | 근거 |
|---|---|---|
| `SfsCascadeType += MERGE/DETACH` | 제외 | enum 단순 정의 — 컴파일만 |
| `CollectionMetadata.cascadesMerge/Detach` | 적용 | 매핑 분기(ALL 해석). `CollectionMetadataTest` 확장 |
| `PersistenceContext.removeEntity/detachAll` | 적용(경량) | open 유지 vs close 죽임 비대칭 + ensureOpen 가드 |
| `RealEntityManager.detach` | 적용 | 분기 다수(pk null / not-managed / cascade / D1 펜딩 취소) |
| `RealEntityManager.clear` | 적용(경량) | 캐시·펜딩 전량 폐기 + open 유지 |
| `RealEntityManager.merge` cascade | 적용 | 그래프 순회 + 사이클 가드 + 컬렉션 재구성 |
| `OrmDemoApplication` 데모 | 제외 | 통합 테스트가 안전망 — 컴파일+실행 |

### 5.2 단위 테스트 (신설/확장)

- `CollectionMetadataTest` (확장) — cascadesMerge/Detach + ALL 4종 해석
- `PersistenceContextTest` (확장/신설) — removeEntity/detachAll/ensureOpen
- `RealEntityManagerDetachTest` (신설) — pk null no-op, not-managed no-op, snapshot 제거, cascade DETACH 전파, **D1 펜딩 InsertAction 취소**
- `RealEntityManagerMergeCascadeTest` (신설) — cascade MERGE 자식 재귀, managed 컬렉션 재구성, **양방향 cascade=ALL 사이클 가드**(MP-3 cycle 테스트 대칭)

### 5.3 통합 테스트 — 학습 정점 4개 승격 (H2 in-memory)

| 통합 테스트 | 정점 | 시나리오 |
|---|---|---|
| `MergeIntegrationTest` (확장) | ① | `merged != detached`, `contains(merged)==true && contains(detached)==false` |
| `DetachDirtyCheckingIntegrationTest` | ② | persist+flush → detach → setName → flush → SELECT 시 이름 불변(UPDATE 미발생) |
| `DetachLazyInitializationIntegrationTest` | ③ | persist+flush(자식 포함) → detach(parent) → `parent.getChildren()` 접근 → `SfsLazyInitializationException` |
| `CascadeMergeIntegrationTest` | ④ | detached parent + 수정된 자식 컬렉션 → merge → 반환 그래프 전체 managed + 자식 변경 DB 반영 |

> **정점 ③ 초기화 타이밍 함정:** detach(parent) **전에** `getChildren()`을 호출해 컬렉션이 초기화되면 예외가 안 난다(이미 로드됨). 테스트는 lazy 컬렉션을 건드리지 않은 채(`SfsPersistentList.isInitialized()==false`) detach해야 정점 성립. 테스트 주석에 박제.

---

## 6. 데모 시나리오 (User/Order, OrmDemoApplication 확장)

MP-3 DK~DN 다음 **DO~DS**:

| # | 시연 | 콘솔 박제 |
|---|---|---|
| **DO** | persist+flush → detach → 이름 변경 → flush | `detach 후 변경 → UPDATE 없음 (dirty checking 중단)` |
| **DP** | `merged = em.merge(detachedUser)` | `merged != detached → true`, `contains(detached) → false` |
| **DQ** | detach(user) → `user.getOrders()` 접근 | `SfsLazyInitializationException 발생 (준영속+lazy)` |
| **DR** | cascade MERGE — 준영속 user.orders 수정 후 merge | `자식 변경까지 DB 반영 (cascade=MERGE 전파)` |
| **DS** | `em.clear()` 후 동일 user find | `clear ≠ close: 직후 find/persist 정상 동작` |

> 데모 DS는 §4.1 detachAll vs close 비대칭을 *실행 가능한 살아있는 문서*로 박제 (Phase 1C부터의 demo 철학 정합).

---

## 7. 이월 박제 — Mini-phase 후보 갱신

| 후보 | 내용 | 원천 | 상태 |
|---|---|---|---|
| **MP-1** | merge/detach 준영속 생명주기 | Phase 4 § 9 | **본 phase 회수** |
| **MP-1-β (신설)** | EntityListener (@PrePersist/@PostLoad/@PreUpdate 등) | MP-1에서 분리 | 신설 박제 |
| **MP-1-γ (신설)** | `refresh()` — DB→메모리 재로드(REFRESH) | 본 phase 범위 OUT | 신설 박제 |
| MP-2-α | EAGER collection fetch | MP-2 Q3 | 유지 |
| MP-4 | EAGER fetch JOIN | Phase 4 § 9 | 유지 |
| MP-5~MP-9 | Phase 4 박제 그대로 | Phase 4 § 9 | 유지 |

> **메타 패턴 박제** (MP-2 spec § 9 의무 계승): 본 phase는 MP-3가 만든 cascade 4종 골격에서 MERGE/DETACH를 회수하고, EntityListener·refresh를 의도적 학습 부채로 분리 박제한다. "mini-phase는 학습 부채를 만들고 다음이 회수한다" 패턴 지속.

---

## 8. DoD (14항목)

1. `SfsCascadeType += MERGE, DETACH` (ALL이 4종 의미)
2. `CollectionMetadata.cascadesMerge/cascadesDetach` + 단위 테스트
3. `PersistenceContext.removeEntity/detachAll` + 단위 테스트 (open 유지 vs close 죽임)
4. `SfsEntityManager` 인터페이스 `detach`/`clear` 추가
5. `RealEntityManager.detach` — pk null/not-managed no-op + snapshot 제거 + cascade DETACH + D1 펜딩 액션 취소
6. `RealEntityManager.clear` — 캐시·펜딩 액션 전량 폐기 + open 유지
7. `RealEntityManager.merge` cascade — 그래프 순회 + 컬렉션 재구성 + 사이클 가드
8. 학습 정점 ① 통합 테스트 (merged != detached)
9. 학습 정점 ② 통합 테스트 (detach → dirty checking 중단)
10. 학습 정점 ③ 통합 테스트 (detach 후 lazy → `SfsLazyInitializationException`)
11. 학습 정점 ④ 통합 테스트 (cascade MERGE 그래프 전파 + 사이클)
12. `OrmDemoApplication` DO~DS 시연 + 콘솔 박제
13. `./gradlew build` 전체 PASS · 회귀 343 → ~360 (실측 기입)
14. 마감 게이트 (다관점 리뷰 + 리팩토링 + `/simplify`) 통과 + plan 하단 기록

> 회귀 예상 +17 내외(단위 11 + 통합 6)는 추정치. MP-3가 "계획 +23 + 게이트 +1"로 실측이 예상을 넘었듯, 마감 게이트가 추가 테스트를 낳을 수 있어 항목 13은 *실측과 예상의 차이 자체를 기록*한다. 항목 14(마감 게이트)는 항상 마지막 — Phase 4·MP-2·MP-3 모두 "14/14"로 수렴한 구조적 일관성.

---

## 9. 모듈 영향 범위

- **변경:** `sfs-orm` (annotation/SfsCascadeType, support/{CollectionMetadata, PersistenceContext}, RealEntityManager, SfsEntityManager)
- **변경:** `sfs-samples` (orm/domain, orm/service, orm/OrmDemoApplication — DO~DS)
- **무영향:** sfs-context / sfs-beans / sfs-core (의존 방향 정합 — 역방향 import 없음)

---

## 10. 구현 진입 체크리스트

- [x] MP-3 → main 머지 완료 (`326d576`), 로컬 main fast-forward + 작업 브랜치 정리
- [x] `feat/mp1-merge-detach-lifecycle` 브랜치 생성 (main 기준)
- [ ] plan 문서 작성 (`docs/superpowers/plans/2026-05-30-mp1-merge-detach-lifecycle.md`) — §5 test 표 ↔ plan task / §8 DoD ↔ plan task / §7 이월 박제 ↔ plan 매핑
- [ ] 구현은 별도 세션 권장 ([[feedback_design_implementation_session_split]])
