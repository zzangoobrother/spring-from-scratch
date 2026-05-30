# MP-1 merge/detach 준영속 생명주기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EntityManager의 준영속(detached) 생명주기를 완성한다 — `detach`/`clear` 신설 + `merge`를 cascade 그래프 전파로 확장하고, `SfsCascadeType`에 MERGE/DETACH를 추가한다.

**Architecture:** MP-3가 만든 cascade 4종 골격(`SfsCascadeType` + `CollectionMetadata.cascadesXxx` + `RealEntityManager`의 `visited` IdentityHashMap 그래프 순회)에서 MERGE/DETACH 2종을 회수한다. `detach`는 1차 캐시·snapshot·펜딩 액션에서 엔티티를 제거(dirty checking 중단)하고 미초기화 lazy 컬렉션을 `detached`로 마킹한다. `merge`는 `doMerge` 재귀로 자식까지 managed 인스턴스로 치환한다.

**Tech Stack:** Java 25, Gradle 9.4.1, JUnit 5 + AssertJ (Mockito 미사용), H2 in-memory.

**선행:** spec `docs/superpowers/specs/2026-05-30-mp1-merge-detach-lifecycle-design.md`. 브랜치 `feat/mp1-merge-detach-lifecycle` (main `326d576` 기준, MP-3까지 머지 완료). 현재 회귀 **343 PASS**.

---

## File Structure

| 파일 | 구분 | 책임 |
|---|---|---|
| `sfs-orm/.../annotation/SfsCascadeType.java` | 변경 | MERGE, DETACH 추가 |
| `sfs-orm/.../support/CollectionMetadata.java` | 변경 | cascadesMerge/cascadesDetach 헬퍼 |
| `sfs-orm/.../support/PersistenceContext.java` | 변경 | removeEntity/detachAll/removePendingActions |
| `sfs-orm/.../support/SfsPersistentList.java` | 변경 | detached 플래그 + markDetached + initialize 가드 |
| `sfs-orm/.../SfsEntityManager.java` | 변경 | detach/clear 시그니처 |
| `sfs-orm/.../RealEntityManager.java` | 변경 | detach/detachInternal, clear, doMerge 재귀 |
| `sfs-samples/.../orm/service/UserService.java` | 변경 | DO~DS 시나리오 메서드 |
| `sfs-samples/.../orm/OrmDemoApplication.java` | 변경 | DO~DS console 시연 |

**테스트 (신설/확장):** `CollectionMetadataTest`(확장), `PersistenceContextTest`(확장), `SfsPersistentListTest`(확장), `RealEntityManagerDetachTest`(신설), `RealEntityManagerMergeCascadeTest`(신설), `MergeReturnsManagedIntegrationTest`(신설, 정점 ①), `DetachDirtyCheckingIntegrationTest`(신설, 정점 ②), `DetachLazyInitializationIntegrationTest`(신설, 정점 ③), `CascadeMergeIntegrationTest`(신설, 정점 ④).

> **테스트 셋업 규약:** EM 레벨 테스트는 MP-3 `RealEntityManagerCascadeTest`와 동일한 self-contained `@BeforeEach` H2 셋업(아래 Task 5 Step 1에 전문 포함)을 쓴다. fixture 클래스는 각 테스트 파일에 `static` 중첩으로 정의(MP-3 cp_parent/cp_child 패턴).

---

## Task 1: `SfsCascadeType`에 MERGE, DETACH 추가

> TDD 제외 (CLAUDE.md "TDD 적용 가이드": enum 단순 정의). 컴파일만 검증.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsCascadeType.java`

- [ ] **Step 1: enum 상수 추가 + Javadoc 갱신**

기존 파일 전체를 아래로 교체:

```java
package com.choisk.sfs.orm.annotation;

/**
 * cascade 전파 종류 — @SfsOneToMany.cascade()에서 사용.
 *
 * <p>PERSIST(persist 전파), REMOVE(remove 전파), MERGE(merge 전파), DETACH(detach 전파),
 * ALL(4종 모두). REFRESH는 본 phase 범위 밖(MP-1-γ 이월).
 *
 * <p>WHY ALL을 끝에 둔 채 MERGE/DETACH를 끼움: 본 프로젝트는 Set.contains() 기반이라
 * ordinal 의존 코드가 없어 안전. cascadesXxx 헬퍼가 ALL을 모든 종에 매핑한다.
 */
public enum SfsCascadeType { PERSIST, REMOVE, MERGE, DETACH, ALL }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :sfs-orm:compileJava`
Expected: BUILD SUCCESSFUL. (CollectionMetadata의 cascadesPersist/Remove는 contains 기반이라 무영향.)

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 343 PASS 유지 (기존 cascade=ALL 사용처는 contains(ALL) 그대로 동작).

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsCascadeType.java
git commit -m "feat(sfs-orm): SfsCascadeType에 MERGE/DETACH 추가 (cascade 4종 골격 완성)"
```

---

## Task 2: `CollectionMetadata`에 cascadesMerge/cascadesDetach 헬퍼

> TDD 적용 — 매핑 분기(ALL 해석). MP-3 cascadesPersist/Remove와 대칭.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/CollectionMetadataTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`CollectionMetadataTest`에 메서드 추가 (기존 `withCascade(SfsCascadeType...)` 헬퍼 재사용 — 6인자 생성자 `new CollectionMetadata(null, Object.class, "fk", "", Set.of(types), false)`):

```java
    @Test
    void cascadesMerge_MERGE_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.MERGE).cascadesMerge()).isTrue();
        assertThat(withCascade(SfsCascadeType.MERGE).cascadesDetach()).isFalse();
    }

    @Test
    void cascadesDetach_DETACH_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.DETACH).cascadesDetach()).isTrue();
        assertThat(withCascade(SfsCascadeType.DETACH).cascadesMerge()).isFalse();
    }

    @Test
    void cascadesAll_ALL은_merge와_detach도_true() {
        CollectionMetadata all = withCascade(SfsCascadeType.ALL);
        assertThat(all.cascadesMerge()).isTrue();
        assertThat(all.cascadesDetach()).isTrue();
    }

    @Test
    void cascade_없으면_merge_detach_모두_false() {
        assertThat(withCascade().cascadesMerge()).isFalse();
        assertThat(withCascade().cascadesDetach()).isFalse();
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :sfs-orm:test --tests CollectionMetadataTest`
Expected: 컴파일 실패 — `cascadesMerge`/`cascadesDetach` 메서드 없음.

- [ ] **Step 3: 헬퍼 2개 추가**

`CollectionMetadata`의 `cascadesRemove()` 아래에 추가:

```java
    /** MERGE 또는 ALL 포함 시 merge 전파. */
    public boolean cascadesMerge() {
        return cascadeTypes.contains(SfsCascadeType.MERGE) || cascadeTypes.contains(SfsCascadeType.ALL);
    }

    /** DETACH 또는 ALL 포함 시 detach 전파. */
    public boolean cascadesDetach() {
        return cascadeTypes.contains(SfsCascadeType.DETACH) || cascadeTypes.contains(SfsCascadeType.ALL);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests CollectionMetadataTest`
Expected: 기존 + 4 PASS.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/CollectionMetadataTest.java
git commit -m "feat(sfs-orm): CollectionMetadata cascadesMerge/cascadesDetach 헬퍼 (MP-3 대칭)"
```

---

## Task 3: `PersistenceContext` — removeEntity/detachAll/removePendingActions

> TDD 적용(경량) — "detachAll은 open 유지 / close는 죽임" 비대칭 + ensureOpen 가드가 본질.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/PersistenceContext.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/PersistenceContextTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`PersistenceContextTest`에 추가 (필요 import: `com.choisk.sfs.orm.support.EntityKey`는 동일 패키지이므로 불필요; `InsertAction`도 동일 패키지). 더미 엔티티는 `Object`와 임의 `EntityKey`로 충분 — `EntityKey(Class, Object)` 생성자 사용:

```java
    @Test
    void removeEntity_1차캐시와_snapshot에서_제거() {
        PersistenceContext ctx = new PersistenceContext();
        EntityKey key = new EntityKey(String.class, 1L);
        Object entity = "e";
        ctx.putEntity(key, entity);
        ctx.putSnapshot(key, new Object[]{"v"});

        ctx.removeEntity(key);

        assertThat(ctx.contains(key)).isFalse();
        assertThat(ctx.getSnapshot(key)).isNull();
    }

    @Test
    void detachAll_캐시는_비우되_open_유지() {
        PersistenceContext ctx = new PersistenceContext();
        EntityKey key = new EntityKey(String.class, 1L);
        ctx.putEntity(key, "e");
        ctx.putSnapshot(key, new Object[]{"v"});

        ctx.detachAll();

        assertThat(ctx.contains(key)).isFalse();
        assertThat(ctx.isClosed()).isFalse();   // close와 달리 죽지 않음
        // open이므로 직후 putEntity 정상 동작 (close였다면 IllegalStateException)
        ctx.putEntity(new EntityKey(String.class, 2L), "e2");
        assertThat(ctx.contains(new EntityKey(String.class, 2L))).isTrue();
    }

    @Test
    void removePendingActions_해당_인스턴스의_액션만_제거() {
        PersistenceContext ctx = new PersistenceContext();
        Object a = "a";
        Object b = "b";
        EntityMetadata md = null;   // 액션 식별은 entity() 참조로만 — metadata 불필요
        ctx.enqueueAction(new InsertAction(a, md));
        ctx.enqueueAction(new InsertAction(b, md));

        ctx.removePendingActions(a);

        assertThat(ctx.actionQueue()).hasSize(1);
        assertThat(ctx.actionQueue().get(0).entity()).isSameAs(b);
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :sfs-orm:test --tests PersistenceContextTest`
Expected: 컴파일 실패 — removeEntity/detachAll/removePendingActions 없음.

- [ ] **Step 3: 메서드 3개 추가**

`PersistenceContext`의 `clearActionQueue()` 아래에 추가:

```java
    /** 단일 엔티티를 1차 캐시·snapshot에서 제거 (detach 기반). */
    public void removeEntity(EntityKey key) {
        ensureOpen();
        identityMap.remove(key);
        snapshots.remove(key);
    }

    /**
     * 1차 캐시·snapshot 전체 비우기 — close()와 달리 closed=false 유지 (clear 기반).
     * WHY: close()는 컨텍스트를 죽이지만 clear()는 살아있는 컨텍스트를 비울 뿐이라 직후 사용 가능.
     */
    public void detachAll() {
        ensureOpen();
        identityMap.clear();
        snapshots.clear();
    }

    /** 주어진 인스턴스를 대상으로 하는 펜딩 액션 제거 (detach D1 — 유령 INSERT 방지). 참조 동등성. */
    public void removePendingActions(Object entity) {
        ensureOpen();
        actionQueue.removeIf(a -> a.entity() == entity);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests PersistenceContextTest`
Expected: 기존 + 3 PASS.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/PersistenceContext.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/PersistenceContextTest.java
git commit -m "feat(sfs-orm): PersistenceContext removeEntity/detachAll/removePendingActions (detach/clear 기반)"
```

---

## Task 4: `SfsPersistentList` detached 마킹 (정점 ③ 메커니즘)

> TDD 적용 — initialize() 분기 추가(detached → 예외).

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`SfsPersistentListTest`에 추가 (기존 `list` 필드 + FakeCollectionLoader["a","b","c"] 재사용. import: `com.choisk.sfs.orm.exception.SfsLazyInitializationException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`):

```java
    @Test
    void markDetached_후_접근_시_SfsLazyInitializationException() {
        list.markDetached();
        assertThatThrownBy(() -> list.size())
                .isInstanceOf(SfsLazyInitializationException.class)
                .hasMessageContaining("detached");
    }

    @Test
    void markDetached_했어도_이미_초기화됐으면_정상_접근() {
        list.size();            // 먼저 초기화 → delegate != null
        list.markDetached();
        assertThat(list.size()).isEqualTo(3);   // delegate != null 가드가 detached 검사보다 먼저
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 컴파일 실패 — `markDetached` 없음.

- [ ] **Step 3: detached 플래그 + initialize 가드 추가**

`storedSnapshot` 필드 아래에 추가. **가시성 결정:** `RealEntityManager.detach`(다른 패키지 `com.choisk.sfs.orm`)가 `markDetached`/`isInitialized`를 호출하므로 둘 다 `public`으로 둔다 (MP-3 `findOrphans` public 승격 선례 — spec §4.5의 package-private 기술은 본 plan에서 cross-package 사유로 public 상향, [[feedback_implementer_autonomy]] 정합).

```java
    /** detach 시 미초기화 lazy 컬렉션에 마킹 — 이후 초기화 시도가 예외(정점 ③). */
    private boolean detached = false;

    /** RealEntityManager.detach(다른 패키지)가 미초기화 컬렉션에 호출. */
    public void markDetached() { this.detached = true; }
```

그리고 기존 `isInitialized()`(현재 package-private, line 62)도 `public`으로 상향:

```java
    /** 초기화 여부 — RealEntityManager.detach(다른 패키지)가 미초기화 판정에 사용. */
    public boolean isInitialized() { return delegate != null; }
```

`initialize()`를 수정 — `delegate != null` 가드 다음, `context.isClosed()` 검사 *앞에* detached 가드 삽입:

```java
    private void initialize() {
        if (delegate != null) return;
        if (detached) {
            // WHY: owner가 detach됨 — 컨텍스트는 열려 있어도 이 엔티티는 준영속
            throw new SfsLazyInitializationException(
                    elementType.getSimpleName() + "#" + ownerPk + ".collection (detached owner)");
        }
        if (context.isClosed()) {
            throw new SfsLazyInitializationException(
                    elementType.getSimpleName() + "#" + ownerPk + ".collection");
        }
        delegate = loader.loadCollection(elementType, joinColumnName, ownerPk, context);
        storedSnapshot = new ArrayList<>(delegate);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 기존 + 2 PASS.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java
git commit -m "feat(sfs-orm): SfsPersistentList detached 플래그 — 준영속 lazy 접근 예외 (정점 ③ 메커니즘)"
```

---

## Task 5: `RealEntityManager.detach` (인터페이스 + 구현)

> TDD 적용 — 분기 다수(pk null no-op / not-managed no-op / snapshot 제거 / D1 펜딩 취소 / cascade DETACH / 미초기화 lazy 마킹).

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java` (신설)

- [ ] **Step 1: 실패 테스트 작성 (신설)**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RealEntityManager.detach — managed → 준영속.
 * snapshot 제거로 dirty checking 중단(정점 ②), 펜딩 InsertAction 취소(D1),
 * cascade DETACH 자식 전파, 미초기화 lazy 컬렉션 markDetached.
 */
class RealEntityManagerDetachTest {

    @SfsEntity(name = "dt_parent")
    static class DtParent {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "dt_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.ALL})
        List<DtChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "dt_child")
    static class DtChild {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "dt_child_seq")
        Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        DtParent parent;
        @SfsColumn String label;
    }

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:detach-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("CREATE SEQUENCE dt_parent_seq START WITH 1");
        jdbc.update("CREATE SEQUENCE dt_child_seq START WITH 1");
        jdbc.update("CREATE TABLE dt_parent (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        jdbc.update("CREATE TABLE dt_child (id BIGINT PRIMARY KEY, parent_id BIGINT, label VARCHAR(50))");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(DtParent.class)
                .addEntityClass(DtChild.class)
                .build();
    }

    @Test
    void detach_managed_엔티티를_준영속화() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent();
        p.name = "p1";
        em.persist(p);
        em.flush();   // managed + DB 반영

        assertThat(em.contains(p)).isTrue();
        em.detach(p);
        assertThat(em.contains(p)).isFalse();
    }

    @Test
    void detach_pk_null_신규엔티티는_no_op() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent();   // persist 안 함 → id null
        em.detach(p);                  // 예외 없이 통과
        assertThat(em.contains(p)).isFalse();
    }

    @Test
    void detach_펜딩_InsertAction_취소_시_flush해도_유령INSERT_없음() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent();
        p.name = "p1";
        em.persist(p);   // SEQUENCE → actionQueue에 InsertAction 대기 (flush 전)

        em.detach(p);    // D1: 펜딩 InsertAction 취소
        assertThat(em.context().actionQueue()).isEmpty();
    }

    @Test
    void detach_cascade_DETACH_시_자식도_준영속() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent();
        p.name = "p1";
        DtChild c = new DtChild();
        c.label = "c1"; c.parent = p;
        p.children.add(c);
        em.persist(p);
        em.flush();

        assertThat(em.contains(c)).isTrue();
        em.detach(p);
        assertThat(em.contains(c)).isFalse();   // cascade=ALL → DETACH 포함
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: 컴파일 실패 — `em.detach(p)` 메서드 없음.

- [ ] **Step 3: 인터페이스에 detach 추가**

`SfsEntityManager`에 추가 (clear는 Task 6에서):

```java
    /** managed → 준영속. snapshot 제거로 dirty checking 중단. cascade=DETACH 전파. */
    void detach(Object entity);
```

- [ ] **Step 4: `RealEntityManager.detach` + `detachInternal` 구현**

`remove`/`doRemove` 아래(또는 merge 근처)에 추가. import 확인: `java.util.IdentityHashMap`, `java.util.Map`은 이미 존재. `SfsPersistentList`도 import 존재.

```java
    /**
     * 엔티티를 준영속 상태로 전환한다. 1차 캐시·snapshot에서 제거(dirty checking 중단)하고,
     * 펜딩 액션을 취소하며(유령 INSERT 방지), cascade=DETACH 자식을 전파한다.
     * 미초기화 lazy 컬렉션은 로드하지 않고 detached로 마킹(정점 ③).
     *
     * @param entity 준영속화할 엔티티 (pk null이거나 이미 준영속이면 no-op)
     */
    @Override
    public void detach(Object entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) {
            throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        }
        detachInternal(entity, md, new IdentityHashMap<>());
    }

    /**
     * detach 그래프 순회 — doRemove와 대칭. visited로 사이클/중복 차단.
     *
     * @param entity  준영속화 대상
     * @param md      엔티티 메타데이터
     * @param visited 방문 추적 (참조 동등성)
     */
    private void detachInternal(Object entity, EntityMetadata md, Map<Object, Boolean> visited) {
        if (visited.put(entity, Boolean.TRUE) != null) return;   // 사이클/중복 가드
        Object pk;
        try {
            pk = md.idField().field().get(entity);
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Cannot read @SfsId for detach", e);
        }
        if (pk == null) return;   // 신규(미영속) → no-op
        EntityKey key = new EntityKey(entity.getClass(), pk);
        if (!context.contains(key)) return;   // 이미 준영속/미관리 → no-op

        context.removePendingActions(entity);   // D1: 펜딩 액션 취소 (유령 INSERT 방지)
        context.removeEntity(key);              // snapshot 제거 → dirty checking 끊김 (정점 ②)

        for (CollectionMetadata cm : md.oneToManies()) {
            Object coll = readField(cm.field(), entity);
            if (coll == null) continue;
            // 미초기화 lazy 컬렉션: 로드 트리거 없이 detached 마킹만 (cascade 독립 — 정점 ③)
            if (coll instanceof SfsPersistentList<?> pl && !pl.isInitialized()) {
                pl.markDetached();
                continue;
            }
            // 초기화된 컬렉션: cascade=DETACH면 이미 로드된 자식도 준영속화
            if (!cm.cascadesDetach()) continue;
            for (Object child : (Iterable<?>) coll) {
                EntityMetadata childMd = emf.metadataOf(child.getClass());
                if (childMd != null) detachInternal(child, childMd, visited);
            }
        }
    }
```

> 가시성: `SfsPersistentList.isInitialized()`/`markDetached()`는 Task 4 Step 3에서 이미 `public`으로 선언됨(cross-package 접근). 별도 조정 불필요.

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: 4 PASS.

- [ ] **Step 6: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS. (기존 SfsPersistentListTest의 markDetached 테스트도 public 변경 무영향.)

- [ ] **Step 7: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java
git commit -m "feat(sfs-orm): detach — 준영속 전환(snapshot 제거+D1 펜딩 취소)+cascade DETACH+미초기화 lazy 마킹"
```

---

## Task 6: `RealEntityManager.clear`

> TDD 적용(경량) — 캐시·펜딩 전량 폐기 + open 유지.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`RealEntityManagerDetachTest`에 추가:

```java
    @Test
    void clear_전체_준영속화하되_컨텍스트는_open_유지() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p1 = new DtParent(); p1.name = "p1";
        DtParent p2 = new DtParent(); p2.name = "p2";
        em.persist(p1);
        em.persist(p2);
        em.flush();

        em.clear();

        assertThat(em.contains(p1)).isFalse();
        assertThat(em.contains(p2)).isFalse();
        assertThat(em.context().actionQueue()).isEmpty();
        // open 유지 — 직후 persist 정상 동작 (close였다면 IllegalStateException)
        DtParent p3 = new DtParent(); p3.name = "p3";
        em.persist(p3);
        assertThat(em.contains(p3)).isTrue();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: 컴파일 실패 — `em.clear()` 없음.

- [ ] **Step 3: 인터페이스 + 구현 추가**

`SfsEntityManager`에 추가:

```java
    /** 전체 준영속 — 1차 캐시·snapshot·펜딩 액션 비우되 컨텍스트는 open 유지(close ≠ clear). */
    void clear();
```

`RealEntityManager`에 추가 (detach 아래):

```java
    /**
     * 영속성 컨텍스트 전체를 준영속화한다. 1차 캐시·snapshot·펜딩 액션을 모두 비우지만
     * close()와 달리 컨텍스트는 살아 있어 직후 persist/find가 정상 동작한다.
     */
    @Override
    public void clear() {
        context.detachAll();
        context.clearActionQueue();
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: 5 PASS (Task 5의 4 + clear 1).

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java
git commit -m "feat(sfs-orm): clear — 전체 준영속(캐시·펜딩 폐기) + open 유지(close ≠ clear)"
```

---

## Task 7: `RealEntityManager.merge` cascade 그래프 전파

> TDD 적용 — 그래프 순회 + 사이클 가드 + 컬렉션 재구성.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java` (`merge` → `doMerge` 재구성)
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java` (신설)

- [ ] **Step 1: 실패 테스트 작성 (신설)**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.RealEntityManager;
import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RealEntityManager.merge cascade — cascade=MERGE 컬렉션을 그래프 순회하며
 * 자식까지 managed 인스턴스로 치환. 반환 그래프 전체가 managed(정점 ①·④).
 */
class RealEntityManagerMergeCascadeTest {

    @SfsEntity(name = "mc_parent")
    static class McParent {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "mc_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.MERGE})
        List<McChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "mc_child")
    static class McChild {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "mc_child_seq")
        Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        McParent parent;
        @SfsColumn String label;
    }

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mergecascade-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("CREATE SEQUENCE mc_parent_seq START WITH 1");
        jdbc.update("CREATE SEQUENCE mc_child_seq START WITH 1");
        jdbc.update("CREATE TABLE mc_parent (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        jdbc.update("CREATE TABLE mc_child (id BIGINT PRIMARY KEY, parent_id BIGINT, label VARCHAR(50))");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(McParent.class)
                .addEntityClass(McChild.class)
                .build();
    }

    @Test
    void merge_cascade_MERGE_시_자식도_managed로_치환() {
        // 1) persist+flush로 부모/자식을 DB에 만든 뒤 clear로 전부 준영속화
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        McParent p = new McParent(); p.name = "p1";
        McChild c = new McChild(); c.label = "c1"; c.parent = p;
        p.children.add(c);
        em.persist(p);
        em.flush();
        em.clear();   // p, c 준영속

        // 2) 준영속 그래프를 수정 후 merge
        p.name = "p1-updated";
        McParent managed = em.merge(p);

        // 3) 반환 부모는 managed이고 인자와 다른 인스턴스
        assertThat(managed).isNotSameAs(p);
        assertThat(em.contains(managed)).isTrue();
        assertThat(em.contains(p)).isFalse();
        // 4) 자식도 managed로 치환됨
        assertThat(managed.children).hasSize(1);
        McChild managedChild = managed.children.get(0);
        assertThat(em.contains(managedChild)).isTrue();
    }
}
```

> **양방향 역참조 주의(spec §4.4):** `managedChild.parent`는 detached 값으로 복사될 수 있다(@ManyToOne 비치환). 본 테스트는 `managed.children`(부모→자식)만 검증한다. 자식→부모 일관성은 application 책임.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerMergeCascadeTest`
Expected: FAIL — 현재 merge는 컬렉션 미순회 → `managed.children`이 detached 자식 그대로 or 비어 `em.contains(managedChild)` false.

- [ ] **Step 3: `merge`를 `doMerge` 재귀로 재구성**

기존 `merge(T)` 본문(508-541)을 아래로 교체. import 확인: `java.util.ArrayList`, `java.util.IdentityHashMap`, `java.util.List`, `java.util.Map` 모두 존재.

```java
    @Override
    public <T> T merge(T entity) {
        @SuppressWarnings("unchecked")
        T result = (T) doMerge(entity, new IdentityHashMap<>());
        return result;
    }

    /**
     * merge 그래프 순회 — doPersist와 대칭(단 값 반환). visited로 사이클 차단.
     * 스칼라/@ManyToOne 복사 후 cascade=MERGE 컬렉션의 자식을 재귀 merge하여
     * managed 컬렉션을 managed 자식으로 재구성한다(정점 ①·④).
     *
     * @param entity  merge 대상 (detached)
     * @param visited 방문 추적 — 재방문 시 해당 managed 인스턴스 반환(양방향 일관)
     * @return 1차 캐시에 등재된 managed 인스턴스 (entity와 다를 수 있음)
     */
    private Object doMerge(Object entity, Map<Object, Boolean> visited) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        try {
            Object pk = md.idField().field().get(entity);
            EntityKey key = new EntityKey(entity.getClass(), pk);

            if (visited.put(entity, Boolean.TRUE) != null) {
                return context.getEntity(key);   // 사이클 — 이미 처리된 managed 반환
            }

            Object managed = context.getEntity(key);
            if (managed == null) {
                managed = find(entity.getClass(), pk);
                if (managed == null) {
                    throw new SfsPersistenceException("Cannot merge: entity not in DB " + key);
                }
            }

            // shallow copy: 스칼라 컬럼 + @ManyToOne 참조 (기존 동작)
            for (FieldMetadata col : md.columns()) {
                col.field().set(managed, col.field().get(entity));
            }
            for (RelationMetadata rel : md.manyToOnes()) {
                rel.field().set(managed, rel.field().get(entity));
            }

            // cascade MERGE: 자식 재귀 merge 후 managed 컬렉션 재구성
            for (CollectionMetadata cm : md.oneToManies()) {
                if (!cm.cascadesMerge()) continue;
                Object coll = readField(cm.field(), entity);
                if (coll == null) continue;
                List<Object> managedChildren = new ArrayList<>();
                for (Object child : (Iterable<?>) coll) {
                    managedChildren.add(doMerge(child, visited));
                }
                cm.field().set(managed, new ArrayList<>(managedChildren));
            }

            // snapshot 갱신 — 다음 flush의 dirty 체크 기준선
            context.putSnapshot(key, captureSnapshot(managed, md));
            return managed;
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Merge failed", e);
        }
    }
```

> **컬렉션 재구성 타입 주의:** `cm.field()`는 `List` 타입이므로 `new ArrayList<>(managedChildren)`로 세팅. 원본이 `SfsPersistentList`였더라도 merge 후엔 plain ArrayList(이미 managed 자식 보유 → lazy 불필요). 기존 merge Javadoc(489-507)은 `doMerge` 위에 유지하되 cascade 설명 한 줄 추가.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerMergeCascadeTest`
Expected: 1 PASS.

- [ ] **Step 5: 회귀 확인 (기존 merge 무손상)**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS. 특히 기존 `MergeIntegrationTest`(cascade 없는 merge) 그대로 통과 — cascade=MERGE 미설정 엔티티는 컬렉션 순회 skip.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java
git commit -m "feat(sfs-orm): merge cascade — doMerge 재귀로 자식 managed 치환 + 사이클 가드 (MP-3 대칭)"
```

---

## Task 8: 통합 테스트 — 정점 ① (merge 복사 의미론)

> TDD 적용 — 통합 시나리오로 정점 승격. (이미 merge Javadoc에 박제된 의미를 실행 검증.)

**Files:**
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java` (확장)

> **위치 결정:** 별도 통합 디렉토리 base class 의존을 피해 `RealEntityManagerMergeCascadeTest`(self-contained H2)에 정점 ① 시나리오를 추가한다.

- [ ] **Step 1: 실패 테스트 추가**

`RealEntityManagerMergeCascadeTest`에 추가:

```java
    @Test
    void 정점1_merge는_인자가_아니라_반환값이_managed() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        McParent p = new McParent(); p.name = "orig";
        em.persist(p);
        em.flush();
        em.clear();   // p 준영속

        p.name = "changed-on-detached";
        McParent managed = em.merge(p);

        // 인자 p는 여전히 detached, 반환 managed가 영속
        assertThat(em.contains(p)).isFalse();
        assertThat(em.contains(managed)).isTrue();
        assertThat(managed).isNotSameAs(p);
        assertThat(managed.name).isEqualTo("changed-on-detached");   // 변경분 복사됨

        // managed에 추가 변경 + flush → DB 반영, p에 변경 → 무시
        managed.name = "via-managed";
        p.name = "via-detached-ignored";
        em.flush();
        em.clear();
        McParent reloaded = em.find(McParent.class, managed.id);
        assertThat(reloaded.name).isEqualTo("via-managed");
    }
```

- [ ] **Step 2: 실패 확인 → 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerMergeCascadeTest`
Expected: Task 7 구현으로 이미 PASS 가능성 높음. PASS면 그대로 인정(characterization — [[feedback_characterization_test]] 정합: 가짜 RED 위해 production 훼손 금지). FAIL이면 원인 분석 후 수정.

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java
git commit -m "test(sfs-orm): 정점 ① merge 복사 의미론 — 인자는 detached, 반환만 managed (DB 반영 검증)"
```

---

## Task 9: 통합 테스트 — 정점 ② (detach → dirty checking 중단)

> TDD 적용 — 통합 시나리오.

**Files:**
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`RealEntityManagerDetachTest`에 추가:

```java
    @Test
    void 정점2_detach_후_변경은_flush해도_DB에_반영안됨() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent(); p.name = "orig";
        em.persist(p);
        em.flush();   // DB: name=orig

        em.detach(p);          // snapshot 제거 → dirty 추적 끊김
        p.name = "changed";    // 준영속 상태에서 변경
        em.flush();            // dirty check 대상 아님 → UPDATE 미발생

        em.clear();
        DtParent reloaded = em.find(DtParent.class, p.id);
        assertThat(reloaded.name).isEqualTo("orig");   // 변경 무시됨
    }
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: PASS (Task 5 detach 구현으로 성립).

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java
git commit -m "test(sfs-orm): 정점 ② detach 후 변경 → flush해도 UPDATE 미발생 (dirty checking 중단)"
```

---

## Task 10: 통합 테스트 — 정점 ③ (detach 후 lazy 접근 예외)

> TDD 적용 — 통합 시나리오. **초기화 타이밍 함정 박제**(spec §5.3).

**Files:**
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`RealEntityManagerDetachTest`에 추가 (import: `com.choisk.sfs.orm.exception.SfsLazyInitializationException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`):

```java
    @Test
    void 정점3_detach_후_미초기화_lazy컬렉션_접근시_예외() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        DtParent p = new DtParent(); p.name = "p1";
        DtChild c = new DtChild(); c.label = "c1"; c.parent = p;
        p.children.add(c);
        em.persist(p);
        em.flush();
        em.clear();   // p 준영속(컨텍스트는 open)

        // find로 다시 managed 로드 — children은 lazy(미초기화)
        DtParent loaded = em.find(DtParent.class, p.id);
        // ⚠️ 초기화 타이밍 함정: 여기서 loaded.children.size()를 호출하면 초기화되어 정점 미성립.
        //    건드리지 않은 채 detach해야 markDetached가 걸린다.
        em.detach(loaded);

        // 준영속 + 미초기화 lazy 접근 → 예외
        assertThatThrownBy(() -> loaded.children.size())
                .isInstanceOf(SfsLazyInitializationException.class)
                .hasMessageContaining("detached");
    }
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerDetachTest`
Expected: PASS (Task 4 markDetached + Task 5 detach 마킹으로 성립).

> 만약 `loaded.children`이 `SfsPersistentList`가 아니라면(find 경로가 lazy wrapper를 안 씌우면) markDetached가 안 걸린다. 그 경우 find의 컬렉션 lazy 래핑 여부를 확인 — MP-2에서 컬렉션 lazy는 SfsPersistentList로 래핑됨이 전제. 미성립 시 실행 기록에 원인 박제.

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerDetachTest.java
git commit -m "test(sfs-orm): 정점 ③ detach 후 미초기화 lazy 접근 → SfsLazyInitializationException (초기화 타이밍 함정 박제)"
```

---

## Task 11: 통합 테스트 — 정점 ④ (cascade MERGE 그래프 + 사이클 가드)

> TDD 적용 — 양방향 cascade=ALL 사이클 가드(MP-3 cycle 테스트 대칭).

**Files:**
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java` (확장)

> **선행 필독:** merge cascade는 컬렉션(부모→자식)만 재귀하고 @ManyToOne(자식→부모)은 복사만 한다. 따라서 *진짜* cascade 사이클(무한 재귀 위험)은 **양쪽 모두 cascade 컬렉션을 가질 때**만 닫힌다. 단순 부모↔자식(자식에 컬렉션 없음)은 visited 가드를 발화시키지 못한다. → 구현자는 **먼저 MP-3 `RealEntityManagerCascadeCycleTest`를 읽고** 그 사이클 fixture(양방향 cascade=ALL로 진짜 사이클을 만든 구조)를 그대로 차용해 cascade에 MERGE가 포함되도록 한 뒤 merge 변형 테스트를 작성한다.

- [ ] **Step 1: 실패 테스트 추가 (MP-3 사이클 fixture 차용)**

`RealEntityManagerMergeCascadeTest`에 자기참조 fixture 추가 — McNode는 자기 타입 컬렉션(cascade=ALL)을 가져 A→B→A 사이클을 닫는다:

```java
    // ─── 사이클 fixture: 자기참조 cascade=ALL (양쪽 모두 컬렉션 → 진짜 사이클) ───
    @SfsEntity(name = "mc_node")
    static class McNode {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "mc_node_seq")
        Long id;
        @SfsColumn String name;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        McNode parent;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.ALL})
        List<McNode> children = new ArrayList<>();
    }
```

setup의 emf builder에 `.addEntityClass(McNode.class)` 추가, DDL 추가:

```java
        jdbc.update("CREATE SEQUENCE mc_node_seq START WITH 1");
        jdbc.update("CREATE TABLE mc_node (id BIGINT PRIMARY KEY, parent_id BIGINT, name VARCHAR(50))");
```

테스트 — A의 children에 B, B의 children에 A를 넣어 merge 그래프에 닫힌 사이클을 만든다:

```java
    @Test
    void 정점4_자기참조_cascade_ALL_merge_사이클에서_무한루프_없이_완료() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        McNode a = new McNode(); a.name = "A";
        em.persist(a);
        em.flush();         // A를 DB에 먼저(FK 자기참조 회피 위해 단순 단일 노드 영속)
        em.clear();         // A 준영속

        // 준영속 그래프에 사이클 주입: A.children=[A] (자기 자신을 자식으로 — visited 가드 발화 경로)
        a.children.add(a);
        a.name = "A-updated";

        McNode managed = em.merge(a);   // visited 가드 없으면 StackOverflowError

        assertThat(em.contains(managed)).isTrue();
        assertThat(managed.name).isEqualTo("A-updated");
        // 사이클: managed.children[0]은 managed 자신(동일 EntityKey → 같은 managed 인스턴스)
        assertThat(managed.children).hasSize(1);
        assertThat(managed.children.get(0)).isSameAs(managed);
    }
```

> **WHY self-isSameAs:** doMerge가 A를 처리하며 visited에 A를 넣고 children 순회 중 다시 A를 만나면 `context.getEntity(key)`(=managed A)를 반환한다. 따라서 `managed.children[0] == managed`. 이것이 사이클 가드 반환값(spec §4.4)의 직접 검증이다. (MP-3 `RealEntityManagerCascadeCycleTest`가 persist/remove에서 동일 골격을 검증했고, 본 테스트는 merge 변형.)

- [ ] **Step 2: 실패/통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerMergeCascadeTest`
Expected: PASS (Task 7 visited 가드로 성립).

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerMergeCascadeTest.java
git commit -m "test(sfs-orm): 정점 ④ 양방향 cascade=ALL merge 사이클 정상 완료 (visited 가드)"
```

---

## Task 12: OrmDemoApplication DO~DS 시연

> TDD 제외 (demo — 컴파일 + 실행/기존 테스트 회귀로 검증). 통합 테스트(Task 8~11)가 동작 안전망.

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/UserService.java`
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/orm/OrmDemoApplication.java`

> **선행 확인:** `User`(@SfsEntity "users", SEQUENCE users_seq, orders=mappedBy"user" cascade=ALL orphanRemoval)와 `Order`는 MP-3에서 양방향 마이그레이션 완료. `User.addOrder/removeOrder` helper 존재. `UserService`/`OrmDemoApplication`의 기존 DK~DN 메서드 패턴(EntityManager 주입 + console 출력)을 그대로 따른다. **구현자는 먼저 기존 DK~DN 메서드 시그니처·출력 스타일을 읽고 동형으로 작성한다.**

- [ ] **Step 1: UserService에 DO~DS 시나리오 메서드 추가**

기존 DN 메서드 아래에 DO~DS 추가. 각 메서드는 EntityManager를 사용하고 결과 문자열/객체를 반환(OrmDemoApplication이 출력). 핵심 동작:

- `demoDetachDirtyStop()` (DO): persist+flush user → detach → setName → flush → find로 재조회해 이름 불변 확인. 반환: "detach 후 변경 → UPDATE 없음 (dirty checking 중단)" + 실제 이름.
- `demoMergeReturnsManaged()` (DP): user 준영속화(clear) → merge → `merged != detached`, `contains(detached)==false`. 반환: 두 boolean.
- `demoDetachLazyException()` (DQ): user+order persist+flush → clear → find(user) → detach(user) → user.getOrders() 접근 → SfsLazyInitializationException catch. 반환: 예외 메시지.
- `demoCascadeMerge()` (DR): user.orders 준영속 수정 → merge → 자식 변경 DB 반영 확인. 반환: 반영된 자식 라벨.
- `demoClearNotClose()` (DS): clear() 후 동일 user find 정상 동작. 반환: find 성공 여부.

> 트랜잭션 경계: 기존 DK~DN가 `@SfsTransactional` 서비스 메서드 패턴을 쓰면 동일 적용. SEQUENCE user/IDENTITY order FK 순서 함정(MP-3 실행 기록 2026-05-29)이 재현되면 동일하게 `persist(u)` 직후 `flush()` 삽입으로 해결.

- [ ] **Step 2: OrmDemoApplication에 DO~DS 콘솔 출력 추가**

기존 DN 출력 블록 아래에 DO~DS 호출 + 출력 추가. 출력 형식은 기존 데모 라인 스타일(`=== DO: ... ===` 헤더 + 결과)을 따른다. 예상 콘솔 박제:

```
=== DO: detach → dirty checking 중단 ===
detach 후 name 변경 + flush → DB name 불변: orig

=== DP: merge 복사 의미론 ===
merged != detached → true / contains(detached) → false

=== DQ: detach 후 lazy 접근 ===
SfsLazyInitializationException: Order#<pk>.collection (detached owner)

=== DR: cascade MERGE 전파 ===
준영속 자식 변경이 merge로 DB 반영됨

=== DS: clear ≠ close ===
clear() 직후 find 정상 동작 (컨텍스트 open 유지)
```

- [ ] **Step 3: 컴파일 + 데모 실행 확인**

Run: `./gradlew :sfs-samples:compileJava`
Expected: BUILD SUCCESSFUL.
Run (선택, 데모 실행): `./gradlew :sfs-samples:run` 또는 기존 데모 실행 태스크 — DO~DS 출력 육안 확인.

- [ ] **Step 4: 회귀 확인**

Run: `./gradlew :sfs-samples:test`
Expected: 기존 PASS 유지.

- [ ] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/UserService.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/orm/OrmDemoApplication.java
git commit -m "feat(sfs-samples): OrmDemoApplication DO~DS — detach/merge/clear 준영속 생명주기 시연"
```

---

## Task 13: 전체 빌드 + DoD 갱신 + 마감 게이트

> TDD 제외 (검증·문서).

**Files:**
- Modify: `docs/superpowers/specs/2026-05-30-mp1-merge-detach-lifecycle-design.md` (DoD 체크 + 회귀 실측)
- Modify: `docs/superpowers/plans/2026-05-30-mp1-merge-detach-lifecycle.md` (본 문서 — 게이트 기록)

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 회귀 카운트 확인(예상 343 → ~365). **실측치 기록.**

- [ ] **Step 2: spec DoD 14항목 체크 + 회귀 실측 기입**

spec §8의 14항목을 완료분에 맞춰 `[ ]` → `[x]`. 항목 13에 실측 회귀 카운트 기입. 항목 14는 게이트 완료 후 체크.

- [ ] **Step 3: 커밋 (DoD 1~13)**

```bash
git add docs/superpowers/specs/2026-05-30-mp1-merge-detach-lifecycle-design.md
git commit -m "docs(mp1): DoD 1~13 체크 + 회귀 카운트 실측 기입 (항목 14는 마감 게이트 후)"
```

- [ ] **Step 4: 마감 게이트 (CLAUDE.md "완료 후 품질 게이트")**

1. **다관점 코드리뷰** — `feat/mp1-merge-detach-lifecycle` 변경분(main `326d576`..HEAD)을 다음 관점으로 독립 검토 (3 reviewer 권장, MP-3 패턴 계승):
   - correctness 버그 스캔 (detach no-op 분기, merge 사이클, markDetached 가시성)
   - 아키텍처·라이프사이클·실패 복구 (detach/clear/close 경계, removePendingActions 참조 동등성)
   - 테스트 커버리지·가독성·주석 WHY (정점 4개 의도 명확성, TDD 판단 적정성)
2. **리팩토링** — "즉시 고칠"만 `refactor(sfs-orm): ...`로 반영. 기존 테스트 PASS 유지.
3. **`/simplify` 패스** — 재사용·중복 제거·데드코드. diff 단위 검토 후 반영분만 `refactor`/`chore` 커밋.
4. plan/spec 하단에 `> **품질 게이트 기록 (2026-05-30):**` 블록(지적 N / 반영 M / 보류 K) 박제.

게이트 통과(빌드 PASS + 회귀 카운트 일치 + DoD 14/14 [x]) 후 **main 머지는 사용자 직접** ([[project_mp3_design_resume_point]] 정합).

- [ ] **Step 5: DoD 14 확정 + 게이트 기록 커밋**

```bash
git add docs/superpowers/specs/2026-05-30-mp1-merge-detach-lifecycle-design.md \
        docs/superpowers/plans/2026-05-30-mp1-merge-detach-lifecycle.md
git commit -m "docs(mp1): 마감 게이트 기록 박제 + DoD 14/14 [x] — 최종 회귀 <실측>"
```

---

## DoD 매핑 (spec §8 ↔ plan task)

| spec DoD | plan task |
|---|---|
| 1 SfsCascadeType MERGE/DETACH | Task 1 |
| 2 cascadesMerge/Detach | Task 2 |
| 3 PersistenceContext 3메서드 | Task 3 |
| 4 인터페이스 detach/clear | Task 5, 6 |
| 5 detach 구현 | Task 5 |
| 6 clear 구현 | Task 6 |
| 7 merge cascade | Task 7 |
| 8 정점 ① | Task 8 |
| 9 정점 ② | Task 9 |
| 10 markDetached + 정점 ③ | Task 4, 10 |
| 11 정점 ④ | Task 11 |
| 12 DO~DS 데모 | Task 12 |
| 13 빌드+회귀 | Task 13 |
| 14 마감 게이트 | Task 13 Step 4 |

## 이월 박제 (spec §7 ↔ 후속)

- MP-1-β (EntityListener), MP-1-γ (refresh) — 본 phase에서 신설 분리. 향후 mini-phase.
