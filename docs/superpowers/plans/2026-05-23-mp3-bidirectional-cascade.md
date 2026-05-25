# MP-3 양방향 + cascade + orphanRemoval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@SfsOneToMany`에 양방향 `mappedBy` + `cascade`(PERSIST/REMOVE) + `orphanRemoval`을 도입해 MP-2 cascade 부재 학습 부채를 회수한다.

**Architecture:** EntityManager 중심 — `RealEntityManager.persist`/`remove`가 cascade 그래프를 순회하고, `flush`가 컬렉션 snapshot diff로 orphan을 탐지한다. owning side(@SfsManyToOne)와 inverse side(컬렉션)의 책임 분리로 양방향 일관성은 application 책임. `mappedBy` XOR `joinColumn`으로 MP-2 단방향을 무손상 공존시킨다.

**Tech Stack:** Java 25, Gradle 9.4.1, JUnit 5 + AssertJ (Mockito 미사용), H2 in-memory, byte-buddy 미사용.

**선행:** spec `docs/superpowers/specs/2026-05-23-mp3-bidirectional-cascade-design.md`. 브랜치 `feat/mp3-bidirectional-cascade`(MP-2 main 머지 후 rebase 예정). 현재 회귀 319 PASS.

---

## File Structure

| 파일 | 구분 | 책임 |
|---|---|---|
| `sfs-orm/.../annotation/SfsCascadeType.java` | 신설 | cascade 종류 enum |
| `sfs-orm/.../annotation/SfsOneToMany.java` | 변경 | mappedBy/cascade/orphanRemoval 속성 |
| `sfs-orm/.../support/CollectionMetadata.java` | 변경 | cascade/orphan 메타 + 헬퍼 |
| `sfs-orm/.../support/EntityMetadataAnalyzer.java` | 변경 | XOR 검증 + mappedBy FK 해석 |
| `sfs-orm/.../support/SfsPersistentList.java` | 변경 | storedSnapshot + findOrphans |
| `sfs-orm/.../RealEntityManager.java` | 변경 | cascade persist/remove + flush orphan |
| `sfs-samples/.../orm/domain/User.java` | 변경 | mappedBy 마이그레이션 + helper |
| `sfs-samples/.../orm/service/UserService.java` | 변경 | DK~DN 시나리오 메서드 |
| `sfs-samples/.../orm/OrmDemoApplication.java` | 변경 | DK~DN console 시연 |

**테스트 (신설):** `CollectionMetadataTest`, `EntityMetadataAnalyzerBidirectionalTest`(또는 기존 OneToMany 테스트 확장), `SfsPersistentListTest`(확장), `RealEntityManagerCascadeTest`, `RealEntityManagerOrphanTest`, `AbstractBidirectionalCascadeTest`(통합 베이스) + `BidirectionalConsistencyIntegrationTest`/`CascadePersistIntegrationTest`/`CascadeRemoveIntegrationTest`/`OrphanRemovalIntegrationTest`.

---

## Task 1: 어노테이션 표면 — `SfsCascadeType` + `@SfsOneToMany` 확장

> TDD 제외(CLAUDE.md "TDD 적용 가이드": enum 단순 정의 + 어노테이션 시그니처). 컴파일만 검증.

**Files:**
- Create: `sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsCascadeType.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsOneToMany.java`

- [x] **Step 1: `SfsCascadeType` enum 생성**

```java
package com.choisk.sfs.orm.annotation;

/**
 * cascade 전파 종류 — @SfsOneToMany.cascade()에서 사용.
 *
 * <p>MP-3 범위: PERSIST(persist 전파), REMOVE(remove 전파), ALL(둘 다).
 * MERGE/DETACH/REFRESH는 본 phase 범위 밖(MP-1/향후).
 */
public enum SfsCascadeType { PERSIST, REMOVE, ALL }
```

- [x] **Step 2: `@SfsOneToMany`에 mappedBy/cascade/orphanRemoval 추가 + joinColumn default 완화**

기존 `joinColumn()`을 `default ""`로 바꾸고(XOR 검증 위해) 3개 속성 추가:

```java
package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OneToMany 관계 — 컬렉션 필드에 적용.
 *
 * <p>매핑 모델은 {@code mappedBy} XOR {@code joinColumn} (정확히 하나).
 * <ul>
 *   <li>단방향(MP-2): {@code joinColumn} — 대상 테이블의 FK 컬럼명 직접 지정.</li>
 *   <li>양방향(MP-3): {@code mappedBy} — owning {@code @SfsManyToOne} 필드명. FK는 owning side가 보유.</li>
 * </ul>
 *
 * <p>spec § 3.1 정합: FetchType.LAZY only — EAGER collection은 MP-2-α 별도 mini-phase로 이월.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsOneToMany {

    /** lazy fetch 전략 — LAZY만 지원. */
    FetchType fetch() default FetchType.LAZY;

    /** 단방향: 대상 테이블의 FK 컬럼명 (예: "user_id"). mappedBy와 XOR. */
    String joinColumn() default "";

    /** 양방향: owning {@code @SfsManyToOne} 필드명 (예: "user"). joinColumn과 XOR. */
    String mappedBy() default "";

    /** cascade 전파 종류 — 기본 없음(JPA 기본값 정합). */
    SfsCascadeType[] cascade() default {};

    /** true이면 컬렉션에서 빠진 element를 DELETE (snapshot diff). */
    boolean orphanRemoval() default false;

    /** Fetch type — LAZY만 정의. EAGER는 MP-2-α 이월 박제. */
    enum FetchType { LAZY }
}
```

- [x] **Step 3: 컴파일 확인**

Run: `./gradlew :sfs-orm:compileJava`
Expected: BUILD SUCCESSFUL. (CollectionMetadata 생성자는 아직 `rel.joinColumn()` 1개만 받으므로 분석기 무영향 — joinColumn은 여전히 존재.)

- [x] **Step 4: 회귀 확인 (기존 단방향 무손상)**

Run: `./gradlew :sfs-orm:test`
Expected: PASS (기존 카운트 유지). joinColumn default `""` 완화는 기존 `@SfsOneToMany(joinColumn="...")` 사용처에 무영향.

- [x] **Step 5: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsCascadeType.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsOneToMany.java
git commit -m "feat(sfs-orm): SfsCascadeType enum + @SfsOneToMany mappedBy/cascade/orphanRemoval 속성"
```

---

## Task 2: `CollectionMetadata` 확장 + cascade 헬퍼

> TDD 적용 — `cascadesPersist()`/`cascadesRemove()`의 ALL 해석은 *매핑 분기*(CLAUDE.md "검증·파싱·매핑에 분기"). record 필드 자체는 데이터 컨테이너.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java:126` (생성자 호출부 pass-through)
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/CollectionMetadataTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionMetadataTest {

    private CollectionMetadata withCascade(SfsCascadeType... types) {
        return new CollectionMetadata(null, Object.class, "fk", "", Set.of(types), false);
    }

    @Test
    void cascadesPersist_PERSIST_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.PERSIST).cascadesPersist()).isTrue();
        assertThat(withCascade(SfsCascadeType.PERSIST).cascadesRemove()).isFalse();
    }

    @Test
    void cascadesRemove_REMOVE_포함_시_true() {
        assertThat(withCascade(SfsCascadeType.REMOVE).cascadesRemove()).isTrue();
        assertThat(withCascade(SfsCascadeType.REMOVE).cascadesPersist()).isFalse();
    }

    @Test
    void cascadesAll_ALL은_persist와_remove_모두_true() {
        CollectionMetadata all = withCascade(SfsCascadeType.ALL);
        assertThat(all.cascadesPersist()).isTrue();
        assertThat(all.cascadesRemove()).isTrue();
    }

    @Test
    void cascade_없으면_모두_false() {
        assertThat(withCascade().cascadesPersist()).isFalse();
        assertThat(withCascade().cascadesRemove()).isFalse();
    }
}
```

- [x] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :sfs-orm:test --tests CollectionMetadataTest`
Expected: 컴파일 실패 — `CollectionMetadata` 생성자가 6인자가 아님 / `cascadesPersist` 메서드 없음.

- [x] **Step 3: `CollectionMetadata` record 확장**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * @SfsOneToMany 필드 분석 결과를 담는 불변 record.
 *
 * @param field          컬렉션 필드 (예: User.orders)
 * @param elementType    컬렉션 element 타입 (예: Order.class)
 * @param joinColumnName 대상 테이블의 FK 컬럼명 — 단방향=직접 / 양방향=owning @SfsJoinColumn에서 도출
 * @param mappedBy       양방향 owning @SfsManyToOne 필드명 ("" = 단방향)
 * @param cascadeTypes   cascade 전파 종류 집합
 * @param orphanRemoval  컬렉션에서 빠진 element를 DELETE할지 여부
 */
public record CollectionMetadata(
        Field field,
        Class<?> elementType,
        String joinColumnName,
        String mappedBy,
        Set<SfsCascadeType> cascadeTypes,
        boolean orphanRemoval
) {
    /** PERSIST 또는 ALL 포함 시 persist 전파. */
    public boolean cascadesPersist() {
        return cascadeTypes.contains(SfsCascadeType.PERSIST) || cascadeTypes.contains(SfsCascadeType.ALL);
    }

    /** REMOVE 또는 ALL 포함 시 remove 전파. */
    public boolean cascadesRemove() {
        return cascadeTypes.contains(SfsCascadeType.REMOVE) || cascadeTypes.contains(SfsCascadeType.ALL);
    }
}
```

- [x] **Step 4: 분석기 생성자 호출부 pass-through 갱신**

`EntityMetadataAnalyzer.doAnalyze`의 `@SfsOneToMany` 분기(현재 126행 부근)를 raw 값 pass-through로 갱신. (XOR 검증 + mappedBy FK 해석은 Task 3/4. 여기선 단방향 기존 동작 유지 + 신규 필드 채우기만.) `import java.util.Set;` 추가.

```java
            } else if (f.isAnnotationPresent(SfsOneToMany.class)) {
                validateOneToMany(f);
                SfsOneToMany rel = f.getAnnotation(SfsOneToMany.class);
                Class<?> elementType = extractGenericType(f);
                oneToManies.add(new CollectionMetadata(
                        f, elementType, rel.joinColumn(),
                        rel.mappedBy(), Set.of(rel.cascade()), rel.orphanRemoval()));
            } else if (f.isAnnotationPresent(SfsColumn.class)) {
```

- [x] **Step 5: 테스트 통과 확인**

Run: `./gradlew :sfs-orm:test --tests CollectionMetadataTest`
Expected: 4 PASS.

- [x] **Step 6: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 기존 + 4 PASS. (단방향 경로: joinColumnName=rel.joinColumn(), mappedBy="" 그대로.)

- [x] **Step 7: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/CollectionMetadataTest.java
git commit -m "feat(sfs-orm): CollectionMetadata cascade/orphan 메타 + cascadesPersist/Remove 헬퍼"
```

---

## Task 3: 분석기 XOR 검증 (mappedBy/joinColumn 정확히 하나)

> TDD 적용 — fail-fast 분기.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java` (`validateOneToMany`)
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzerBidirectionalTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetadataAnalyzerBidirectionalTest {

    private final EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();

    @Test
    void mappedBy와_joinColumn_둘_다_지정_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(BothSpecified.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("정확히 하나");
    }

    @Test
    void mappedBy와_joinColumn_둘_다_누락_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(NeitherSpecified.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("정확히 하나");
    }

    // ─── fixture ───
    @SfsEntity(name = "child_x")
    static class ChildX {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") OwnerBoth owner;
    }

    @SfsEntity(name = "owner_both")
    static class BothSpecified {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "owner_id", mappedBy = "owner")
        List<ChildX> children;
    }

    @SfsEntity(name = "owner_neither")
    static class NeitherSpecified {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany
        List<ChildX> children;
    }

    // ChildX.owner가 참조하는 OwnerBoth (BothSpecified와 별개 — 컴파일용 최소 엔티티)
    @SfsEntity(name = "owner_both_ref")
    static class OwnerBoth {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerBidirectionalTest`
Expected: FAIL — 현재 XOR 검증 없어 예외 미발생(또는 다른 메시지).

- [x] **Step 3: `validateOneToMany`에 XOR 검증 추가**

`validateOneToMany(Field f)` 끝에 추가:

```java
        SfsOneToMany rel = f.getAnnotation(SfsOneToMany.class);
        boolean hasJoinColumn = !rel.joinColumn().isEmpty();
        boolean hasMappedBy = !rel.mappedBy().isEmpty();
        if (hasJoinColumn == hasMappedBy) {   // 둘 다 true(both) 또는 둘 다 false(neither)
            throw new SfsEntityMappingException(
                    "@SfsOneToMany field '" + f.getName()
                    + "' must specify 정확히 하나 of joinColumn / mappedBy");
        }
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerBidirectionalTest`
Expected: 2 PASS.

- [x] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 기존 + 2 PASS. (기존 단방향 fixture는 joinColumn만 지정 → XOR 통과.)

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzerBidirectionalTest.java
git commit -m "feat(sfs-orm): @SfsOneToMany mappedBy XOR joinColumn fail-fast 검증"
```

---

## Task 4: 분석기 mappedBy FK 해석 (owning @SfsManyToOne에서 도출)

> TDD 적용 — 매핑 해석 + fail-fast 분기.

> **실행 기록 (2026-05-24):** plan Step 3 코드는 `NoSuchFieldException` catch에서 `new SfsEntityMappingException(msg, e)`(cause 전파)를 가정했으나, `SfsEntityMappingException`은 `(String)` 생성자만 보유. cause 없이 메시지만 담는 생성자로 대체(`.hasMessageContaining("mappedBy")` 충족, 기능 무손상). cause 체인 손실은 사소(메시지에 누락 필드명·대상 클래스명 이미 포함). → 마감 게이트(Task 14) 후보: `SfsEntityMappingException(String, Throwable)` 생성자 추가 시 cause 보존 가능(저위험 개선).

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java` (생성자 호출부 + 신규 `resolveJoinColumn`)
- Test: `EntityMetadataAnalyzerBidirectionalTest` (확장)

- [x] **Step 1: 실패 테스트 추가**

`EntityMetadataAnalyzerBidirectionalTest`에 추가(+ `import` `EntityMetadata`, `CollectionMetadata`, `assertThat`, `SfsCascadeType`):

```java
    @Test
    void mappedBy_정상_시_owning_SfsJoinColumn에서_FK_도출_및_cascade_orphan_채움() {
        EntityMetadata md = analyzer.analyze(GoodOwner.class);
        CollectionMetadata cm = md.oneToManies().get(0);
        assertThat(cm.joinColumnName()).isEqualTo("owner_id");   // GoodChild.owner의 @SfsJoinColumn
        assertThat(cm.mappedBy()).isEqualTo("owner");
        assertThat(cm.cascadesPersist()).isTrue();
        assertThat(cm.orphanRemoval()).isTrue();
    }

    @Test
    void mappedBy가_가리키는_필드_부재_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(BadMappedByName.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("mappedBy");
    }

    @Test
    void mappedBy_대상이_owner타입과_불일치_시_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(MismatchOwner.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("targetEntity");
    }

    // ─── fixture (정상) ───
    @SfsEntity(name = "good_child")
    static class GoodChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") GoodOwner owner;
    }
    @SfsEntity(name = "good_owner")
    static class GoodOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "owner",
                cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
        List<GoodChild> children;
    }

    // ─── fixture (mappedBy 이름 오타) ───
    @SfsEntity(name = "bad_name_child")
    static class BadNameChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "owner_id") BadMappedByName owner;
    }
    @SfsEntity(name = "bad_name_owner")
    static class BadMappedByName {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "nonexistent") List<BadNameChild> children;
    }

    // ─── fixture (owning 타입 불일치) ───
    @SfsEntity(name = "other_entity")
    static class OtherEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
    }
    @SfsEntity(name = "mismatch_child")
    static class MismatchChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsManyToOne @SfsJoinColumn(name = "other_id") OtherEntity owner;  // MismatchOwner 아님
    }
    @SfsEntity(name = "mismatch_owner")
    static class MismatchOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY) Long id;
        @SfsOneToMany(mappedBy = "owner") List<MismatchChild> children;
    }
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerBidirectionalTest`
Expected: FAIL — 양방향 joinColumnName이 ""(미해석), 예외 미발생.

- [x] **Step 3: `resolveJoinColumn` 구현 + 생성자 호출부 갱신**

분석기의 `@SfsOneToMany` 분기를 양방향 해석으로 갱신(Task 2의 pass-through 대체):

```java
            } else if (f.isAnnotationPresent(SfsOneToMany.class)) {
                validateOneToMany(f);
                SfsOneToMany rel = f.getAnnotation(SfsOneToMany.class);
                Class<?> elementType = extractGenericType(f);
                String joinColumnName = resolveJoinColumn(rel, elementType, entityClass, f);
                oneToManies.add(new CollectionMetadata(
                        f, elementType, joinColumnName,
                        rel.mappedBy(), Set.of(rel.cascade()), rel.orphanRemoval()));
            } else if (f.isAnnotationPresent(SfsColumn.class)) {
```

신규 private 메서드 추가(`import java.lang.reflect.Field;`는 이미 존재):

```java
    /**
     * @SfsOneToMany의 FK 컬럼명을 해석한다.
     * - 단방향(joinColumn): 그대로 반환.
     * - 양방향(mappedBy): owning 엔티티(elementType)에서 mappedBy 이름의 @SfsManyToOne 필드를 찾아
     *   그 @SfsJoinColumn.name을 FK로 채택. targetEntity가 owner 클래스와 일치하는지 검증.
     *
     * @param rel         @SfsOneToMany 어노테이션
     * @param elementType 컬렉션 element(=owning) 엔티티 클래스
     * @param ownerClass  컬렉션을 소유한 inverse 엔티티 클래스
     * @param ownerField  컬렉션 필드(에러 메시지용)
     * @throws SfsEntityMappingException mappedBy 필드 부재/매핑 미비/타입 불일치 시
     */
    private String resolveJoinColumn(SfsOneToMany rel, Class<?> elementType,
                                     Class<?> ownerClass, Field ownerField) {
        if (!rel.joinColumn().isEmpty()) {
            return rel.joinColumn();   // 단방향
        }
        // 양방향: elementType에서 mappedBy 필드 탐색
        Field owningField;
        try {
            owningField = elementType.getDeclaredField(rel.mappedBy());
        } catch (NoSuchFieldException e) {
            throw new SfsEntityMappingException(
                    "@SfsOneToMany field '" + ownerField.getName() + "' mappedBy='" + rel.mappedBy()
                    + "' — no such field on " + elementType.getSimpleName(), e);
        }
        if (!owningField.isAnnotationPresent(SfsManyToOne.class)
                || !owningField.isAnnotationPresent(SfsJoinColumn.class)) {
            throw new SfsEntityMappingException(
                    "@SfsOneToMany mappedBy target '" + elementType.getSimpleName() + "." + rel.mappedBy()
                    + "' must be @SfsManyToOne + @SfsJoinColumn (owning side)");
        }
        if (!owningField.getType().equals(ownerClass)) {
            throw new SfsEntityMappingException(
                    "@SfsOneToMany mappedBy targetEntity mismatch: " + elementType.getSimpleName() + "."
                    + rel.mappedBy() + " points to " + owningField.getType().getSimpleName()
                    + " but owner is " + ownerClass.getSimpleName());
        }
        return owningField.getAnnotation(SfsJoinColumn.class).name();
    }
```

- [x] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerBidirectionalTest`
Expected: 5 PASS (Task 3의 2 + 신규 3).

- [x] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 기존 + 누적 PASS.

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzerBidirectionalTest.java
git commit -m "feat(sfs-orm): @SfsOneToMany mappedBy → owning @SfsJoinColumn FK 해석"
```

---

## Task 5: `SfsPersistentList` storedSnapshot + findOrphans

> TDD 적용 — 알고리즘(diff) + 상태 보관.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java` (확장)

- [x] **Step 1: 실패 테스트 추가**

`SfsPersistentListTest`에 추가:

```java
    @Test
    void findOrphans_초기화_후_변경_없으면_빈_리스트() {
        list.size();   // 초기화 → storedSnapshot = [a, b, c]
        assertThat(list.findOrphans()).isEmpty();
    }

    @Test
    void findOrphans_remove한_element를_orphan으로_반환() {
        list.size();          // 초기화 → ["a","b","c"]
        list.remove("b");     // delegate = ["a","c"]
        assertThat(list.findOrphans()).containsExactly("b");
    }

    @Test
    void findOrphans_미초기화_상태면_빈_리스트() {
        // 한 번도 메서드 호출 안 함 — delegate null
        assertThat(list.findOrphans()).isEmpty();
        assertThat(list.isInitialized()).isFalse();
    }
```

> 참조 동등성 정합: FakeCollectionLoader가 `new ArrayList<>(List.of("a","b","c"))`를 반환하므로 `remove("b")`는 String 동등성으로 제거된다. 실제 엔티티는 identityMap이 1:1을 보장해 동일 인스턴스 — 본 fake는 String이라 equals로 충분(테스트 단순화).

- [x] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 컴파일 실패 — `findOrphans` 없음.

- [x] **Step 3: 구현**

`SfsPersistentList`에 storedSnapshot 필드 + capture + findOrphans 추가. `initialize()` 수정:

```java
    /** null = 미초기화 상태. delegate != null 가드로 중복 loader 호출 방지. */
    private List<T> delegate;
    /** 로드 직후 element 사본 — orphanRemoval diff 기준선(Hibernate PersistentBag.storedSnapshot 동형). */
    private List<T> storedSnapshot;
```

```java
    private void initialize() {
        if (delegate != null) return;
        if (context.isClosed()) {
            throw new SfsLazyInitializationException(
                    elementType.getSimpleName() + "#" + ownerPk + ".collection");
        }
        delegate = loader.loadCollection(elementType, joinColumnName, ownerPk, context);
        storedSnapshot = new ArrayList<>(delegate);   // 로드 직후 사본
    }
```

`isInitialized()` 아래에 추가:

```java
    /**
     * orphan = storedSnapshot에는 있으나 현재 delegate에 없는 element.
     * 미초기화(delegate null)면 변경이 없으므로 빈 리스트.
     *
     * <p>WHY 참조 동등성: identityMap이 1 entity = 1 instance를 보장하므로 동일 인스턴스 비교로 충분.
     * 엔티티가 equals/hashCode를 재정의 안 했을 수 있어 참조 비교가 안전.
     */
    List<T> findOrphans() {
        if (delegate == null) return List.of();
        List<T> orphans = new ArrayList<>();
        for (T e : storedSnapshot) {
            boolean stillPresent = false;
            for (T d : delegate) {
                if (d == e) { stillPresent = true; break; }
            }
            if (!stillPresent) orphans.add(e);
        }
        return orphans;
    }
```

> 주의: `import java.util.ArrayList;` 추가 필요(현재 미import).

- [x] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 기존 4 + 신규 3 PASS.

> `findOrphans_remove한_element를_orphan으로_반환`은 String 동등성(`remove("b")`)으로 delegate에서 제거되지만, storedSnapshot의 "b"와 delegate에 남은 "a","c"를 *참조* 비교한다. storedSnapshot은 `new ArrayList<>(delegate)`로 같은 인스턴스를 담으므로 "b"(원본 인스턴스)는 delegate에서 사라져 orphan으로 잡힌다. PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java
git commit -m "feat(sfs-orm): SfsPersistentList storedSnapshot + findOrphans (PersistentBag 동형)"
```

---

## Task 6: `RealEntityManager` cascade PERSIST

> TDD 적용 — 그래프 순회 + 상태 + 사이클 가드.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java`
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerCascadeTest.java`

- [x] **Step 1: 실패 테스트 작성**

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
 * RealEntityManager cascade PERSIST — persist(parent)가 cascade 컬렉션을 그래프 순회하며
 * 자식까지 InsertAction에 등록하는지 검증. managed 재persist도 cascade 발화(MP-2 부채 회수).
 */
class RealEntityManagerCascadeTest {

    @SfsEntity(name = "cp_parent")
    static class CpParent {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.PERSIST})
        List<CpChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "cp_parent_nocascade")
    static class CpParentNoCascade {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_parent_seq")
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent2")   // cascade 없음
        List<CpChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "cp_child")
    static class CpChild {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.SEQUENCE, sequenceName = "cp_child_seq")
        Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        CpParent parent;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent2_id")
        CpParentNoCascade parent2;
        @SfsColumn String label;
    }

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cascadepersist-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("CREATE SEQUENCE cp_parent_seq START WITH 1");
        jdbc.update("CREATE SEQUENCE cp_child_seq START WITH 1");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(CpParent.class)
                .addEntityClass(CpParentNoCascade.class)
                .addEntityClass(CpChild.class)
                .build();
    }

    @Test
    void persist_parent_cascade_PERSIST_시_자식도_InsertAction_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParent p = new CpParent();
        p.name = "p1";
        CpChild c = new CpChild();
        c.label = "c1";
        c.parent = p;
        p.children.add(c);

        em.persist(p);

        // parent + child = InsertAction 2건 (SEQUENCE write-behind)
        assertThat(em.context().actionQueue()).hasSize(2);
        assertThat(em.context().actionQueue()).allMatch(a -> a instanceof InsertAction);
    }

    @Test
    void cascade_없으면_parent만_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParentNoCascade p = new CpParentNoCascade();
        p.name = "p1";
        CpChild c = new CpChild();
        c.label = "c1";
        c.parent2 = p;
        p.children.add(c);

        em.persist(p);

        assertThat(em.context().actionQueue()).hasSize(1);   // parent만
    }

    @Test
    void managed_parent_재persist_시_새_자식만_cascade_등록() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParent p = new CpParent();
        p.name = "p1";
        CpChild c1 = new CpChild();
        c1.label = "c1";
        c1.parent = p;
        p.children.add(c1);
        em.persist(p);   // queue: [Insert(p), Insert(c1)]

        // managed 상태에서 새 자식 추가 후 재persist
        CpChild c2 = new CpChild();
        c2.label = "c2";
        c2.parent = p;
        p.children.add(c2);
        em.persist(p);   // p, c1 managed → skip self-insert / c2만 신규

        // queue: [Insert(p), Insert(c1), Insert(c2)] — p/c1 중복 등록 없음
        assertThat(em.context().actionQueue()).hasSize(3);
        long parentInserts = em.context().actionQueue().stream()
                .filter(a -> a instanceof InsertAction ia && ia.entity() == p).count();
        assertThat(parentInserts).isEqualTo(1);
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerCascadeTest`
Expected: FAIL — cascade 미구현이라 actionQueue size 1(parent only).

- [x] **Step 3: `persist`를 doPersist/cascadePersist로 재구성**

`RealEntityManager`의 기존 `persist(Object)` 본문을 `doPersist`로 옮기고 cascade 추가. `import java.util.IdentityHashMap;`, `import java.util.Map;`, `import com.choisk.sfs.orm.support.CollectionMetadata;`, `import java.lang.reflect.Field;` 추가.

```java
    @Override
    public void persist(Object entity) {
        doPersist(entity, new IdentityHashMap<>());
    }

    /**
     * cascade 그래프 순회 진입점. visited는 단일 persist 호출 전체에서 공유(참조 기반)되어
     * 사이클(양방향 모두 cascade일 때)과 중복 방문을 차단한다.
     */
    private void doPersist(Object entity, Map<Object, Boolean> visited) {
        if (visited.put(entity, Boolean.TRUE) != null) return;   // 이미 방문 — 사이클/중복 가드

        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) {
            throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        }
        if (!isManaged(entity, md)) {
            insertNew(entity, md);   // 기존 self-insert (SEQUENCE/IDENTITY 분기)
        }
        // cascade PERSIST — managed든 아니든 진행(managed 재persist도 새 자식 전파)
        for (CollectionMetadata cm : md.oneToManies()) {
            if (!cm.cascadesPersist()) continue;
            Object coll = readField(cm.field(), entity);
            if (coll == null) continue;
            for (Object child : (Iterable<?>) coll) {
                doPersist(child, visited);
            }
        }
    }

    /** 엔티티가 이미 1차 캐시(managed)인지 — id가 채워졌고 contains true. */
    private boolean isManaged(Object entity, EntityMetadata md) {
        try {
            Object id = md.idField().field().get(entity);
            if (id == null) return false;
            return context.contains(new EntityKey(entity.getClass(), id));
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("@SfsId 읽기 실패 — managed 판정 불가", e);
        }
    }

    /** 미관리 신규 엔티티의 self-insert (기존 persist 본문). */
    private void insertNew(Object entity, EntityMetadata md) {
        EntityPersister persister = emf.persisterOf(entity.getClass());
        IdentifierGenerator gen = persister.idGenerator();
        if (gen.isPostInsert()) {
            gen.generate(entity, md);
            Object idValue;
            try {
                idValue = md.idField().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 읽기 실패 — EntityKey 구성 불가", e);
            }
            EntityKey key = new EntityKey(entity.getClass(), idValue);
            context.putEntity(key, entity);
            context.putSnapshot(key, captureSnapshot(entity, md));
        } else {
            Object id = gen.generate(entity, md);
            try {
                md.idField().field().set(entity, convertId(id, md.idField().javaType()));
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 세팅 실패", e);
            }
            Object idValue;
            try {
                idValue = md.idField().field().get(entity);
            } catch (IllegalAccessException e) {
                throw new SfsPersistenceException("@SfsId 필드 읽기 실패 — EntityKey 구성 불가", e);
            }
            EntityKey key = new EntityKey(entity.getClass(), idValue);
            context.putEntity(key, entity);
            context.putSnapshot(key, captureSnapshot(entity, md));
            context.enqueueAction(new InsertAction(entity, md));
        }
    }

    /** reflection 필드 읽기 — cascade/orphan 공통 헬퍼. */
    private Object readField(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("필드 접근 실패: " + f.getName(), e);
        }
    }
```

> 기존 `persist`의 SEQUENCE/IDENTITY 본문은 `insertNew`로 그대로 이동(Javadoc은 `persist`에 유지하거나 `insertNew`로 이전). `captureSnapshot`/`convertId`는 기존 메서드 재사용.

- [x] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerCascadeTest`
Expected: 3 PASS.

- [x] **Step 5: 회귀 확인 (기존 persist 동작 무손상)**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS. (RealEntityManagerPersistTest의 SEQUENCE/IDENTITY 단일 persist 동작 보존 — cascade 컬렉션 없으면 doPersist는 self-insert만.)

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerCascadeTest.java
git commit -m "feat(sfs-orm): cascade PERSIST — persist 그래프 순회 + managed 재persist 회수 + 사이클 가드"
```

---

## Task 7: `RealEntityManager` cascade REMOVE (자식 먼저)

> TDD 적용 — 그래프 순회 + 삭제 순서.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java` (`remove`)
- Test: `RealEntityManagerCascadeTest` (확장)

- [ ] **Step 1: 실패 테스트 추가**

`RealEntityManagerCascadeTest`에 `cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}` fixture가 필요하므로 CpParent의 cascade에 REMOVE 추가. 기존 `@SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.PERSIST})`를 `cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}`로 변경(Task 6 테스트는 PERSIST 포함 유지되어 영향 없음). 그 후 테스트 추가:

```java
    @Test
    void remove_parent_cascade_REMOVE_시_자식_DeleteAction이_부모보다_먼저() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        CpParent p = new CpParent();
        p.name = "p1";
        CpChild c1 = new CpChild();
        c1.label = "c1"; c1.parent = p;
        CpChild c2 = new CpChild();
        c2.label = "c2"; c2.parent = p;
        p.children.add(c1);
        p.children.add(c2);
        em.persist(p);
        em.flush();   // DB INSERT + actionQueue 비움 → p/c1/c2 managed

        em.remove(p);

        // queue: [Delete(c1), Delete(c2), Delete(p)] — 부모는 마지막
        List<EntityAction> q = em.context().actionQueue();
        assertThat(q).hasSize(3);
        assertThat(q).allMatch(a -> a instanceof DeleteAction);
        assertThat(((DeleteAction) q.get(q.size() - 1)).entity()).isSameAs(p);
    }
```

> 이 테스트는 flush를 호출하므로 setup에 테이블 DDL 추가 필요. setup의 시퀀스 생성 뒤에 추가:
> ```java
> jdbc.update("CREATE TABLE cp_parent (id BIGINT PRIMARY KEY, name VARCHAR(50))");
> jdbc.update("CREATE TABLE cp_parent_nocascade (id BIGINT PRIMARY KEY, name VARCHAR(50))");
> jdbc.update("CREATE TABLE cp_child (id BIGINT PRIMARY KEY, parent_id BIGINT, parent2_id BIGINT, label VARCHAR(50))");
> ```
> (Task 6 테스트들은 flush를 안 하지만 테이블이 있어도 무해.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerCascadeTest`
Expected: FAIL — `remove(p)`가 자식 cascade 없이 DeleteAction 1건만 등록.

- [ ] **Step 3: `remove`에 cascade REMOVE 추가**

기존 `remove(Object)`를 갱신 — managed 검증 후, 부모 enqueue 전에 자식 cascade:

```java
    @Override
    public void remove(Object entity) {
        EntityMetadata md = emf.metadataOf(entity.getClass());
        if (md == null) {
            throw new SfsPersistenceException("Unknown entity class: " + entity.getClass());
        }
        Object pk;
        try {
            pk = md.idField().field().get(entity);
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("Cannot read @SfsId for remove", e);
        }
        EntityKey key = new EntityKey(entity.getClass(), pk);
        if (!context.contains(key)) {
            throw new IllegalArgumentException("Entity not managed: " + entity);
        }
        // cascade REMOVE — 자식 DeleteAction을 부모보다 먼저 등록(FK 순서). flush의 stable sort가 순서 보존.
        for (CollectionMetadata cm : md.oneToManies()) {
            if (!cm.cascadesRemove()) continue;
            Object coll = readField(cm.field(), entity);
            if (coll == null) continue;
            for (Object child : (Iterable<?>) coll) {
                remove(child);
            }
        }
        context.enqueueAction(new DeleteAction(entity, md));
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerCascadeTest`
Expected: Task 6의 3 + 신규 1 = 4 PASS.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS. (RealEntityManagerRemoveTest: cascade 컬렉션 없는 엔티티는 기존 동작 — 미관리 예외 + DeleteAction 1건 유지.)

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerCascadeTest.java
git commit -m "feat(sfs-orm): cascade REMOVE — 자식 DeleteAction 먼저 등록(FK 순서)"
```

---

## Task 8: `RealEntityManager.flush` orphanRemoval

> TDD 적용 — 컬렉션 변화 감지 + 중복 가드.

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java` (`flush` + 헬퍼)
- Test: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerOrphanTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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
 * RealEntityManager.flush() orphanRemoval — 컬렉션에서 빠진 element가 flush 시 DELETE되는지 검증.
 */
class RealEntityManagerOrphanTest {

    @SfsEntity(name = "orp_parent")
    static class OrpParent {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @SfsColumn String name;
        @SfsOneToMany(mappedBy = "parent", cascade = {SfsCascadeType.PERSIST}, orphanRemoval = true)
        List<OrpChild> children = new ArrayList<>();
    }

    @SfsEntity(name = "orp_child")
    static class OrpChild {
        @SfsId @SfsGeneratedValue(strategy = GenerationType.IDENTITY) Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "parent_id")
        OrpParent parent;
        @SfsColumn String label;
    }

    private SfsEntityManagerFactory emf;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:orphan-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ThreadLocalTsm tsm = new ThreadLocalTsm();
        jdbc = new JdbcTemplate(ds, tsm);
        jdbc.update("CREATE TABLE orp_parent (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(50))");
        jdbc.update("CREATE TABLE orp_child (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, parent_id BIGINT, label VARCHAR(50))");
        // 데이터: parent 1 + child 2
        jdbc.update("INSERT INTO orp_parent (id, name) VALUES (1, 'p1')");
        jdbc.update("INSERT INTO orp_child (id, parent_id, label) VALUES (10, 1, 'c1')");
        jdbc.update("INSERT INTO orp_child (id, parent_id, label) VALUES (11, 1, 'c2')");
        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(OrpParent.class)
                .addEntityClass(OrpChild.class)
                .build();
    }

    @Test
    void 컬렉션에서_remove한_child가_flush_시_DELETE된다() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        OrpParent p = em.find(OrpParent.class, 1L);

        OrpChild first = p.children.get(0);   // 초기화 → storedSnapshot = [c1, c2]
        p.children.remove(first);             // delegate = [c2]

        em.flush();

        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM orp_child", Long.class);
        assertThat(remaining).isEqualTo(1L);   // orphan(c1) 삭제, c2 남음
    }

    @Test
    void 미초기화_컬렉션은_orphan_검사_skip() {
        RealEntityManager em = (RealEntityManager) emf.createEntityManager();
        em.find(OrpParent.class, 1L);   // children 미초기화(접근 안 함)

        em.flush();   // orphan 단계가 미초기화 컬렉션을 건너뛰어 DELETE 0

        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM orp_child", Long.class);
        assertThat(remaining).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerOrphanTest`
Expected: 첫 테스트 FAIL (remaining=2, orphan 미삭제). 둘째는 우연히 PASS 가능.

- [ ] **Step 3: `flush`에 orphanRemoval 단계 추가**

`flush()`의 Phase 1(dirty check) 루프와 Phase 2(action 실행) 사이에 orphan 단계 삽입:

```java
        // Phase 1.5: orphanRemoval — 컬렉션 snapshot diff (cascade와 다른 트리거)
        // identityMap 순회 중 enqueue하므로 별도 루프로 분리(ConcurrentModification 회피: actionQueue는 별도 리스트)
        for (var entry : context.identityMap().entrySet()) {
            EntityMetadata md = emf.metadataOf(entry.getKey().entityClass());
            Object owner = entry.getValue();
            for (CollectionMetadata cm : md.oneToManies()) {
                if (!cm.orphanRemoval()) continue;
                Object coll = readField(cm.field(), owner);
                if (!(coll instanceof SfsPersistentList<?> pl)) continue;
                for (Object orphan : pl.findOrphans()) {
                    EntityMetadata orphanMd = emf.metadataOf(orphan.getClass());
                    EntityKey ok = new EntityKey(orphan.getClass(), readId(orphan, orphanMd));
                    if (context.contains(ok) && !hasPendingDelete(orphan)) {
                        context.enqueueAction(new DeleteAction(orphan, orphanMd));
                    }
                }
            }
        }
```

헬퍼 2개 추가(`import com.choisk.sfs.orm.support.SfsPersistentList;` 추가):

```java
    /** @SfsId 값 읽기 — orphan EntityKey 구성용. */
    private Object readId(Object entity, EntityMetadata md) {
        try {
            return md.idField().field().get(entity);
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException("@SfsId 읽기 실패 — orphan key 구성 불가", e);
        }
    }

    /** 이미 같은 인스턴스에 대한 DeleteAction이 큐에 있으면 true(cascade REMOVE 중복 가드). */
    private boolean hasPendingDelete(Object entity) {
        for (EntityAction a : context.actionQueue()) {
            if (a instanceof DeleteAction da && da.entity() == entity) return true;
        }
        return false;
    }
```

> `var entry`의 `entry.getKey().entityClass()`는 `EntityKey.entityClass()` 사용(기존 flush의 dirty 루프와 동일 패턴).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sfs-orm:test --tests RealEntityManagerOrphanTest`
Expected: 2 PASS.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS. (orphanRemoval=false인 기존 fixture는 Phase 1.5에서 즉시 skip.)

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/RealEntityManagerOrphanTest.java
git commit -m "feat(sfs-orm): flush orphanRemoval — 컬렉션 snapshot diff DELETE + cascade 중복 가드"
```

---

## Task 9: 통합 베이스 + 양방향 일관성 함정 (FK null)

> 통합 — 학습 정점 ① 박제. 공유 베이스 `AbstractBidirectionalCascadeTest`를 신설하고 Task 10~12가 상속.

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/AbstractBidirectionalCascadeTest.java`
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/BidirectionalConsistencyIntegrationTest.java`

- [ ] **Step 1: 통합 베이스 작성 (fixture + 인프라)**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsCascadeType;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.SEQUENCE;

/**
 * MP-3 양방향 cascade/orphan 통합 테스트 공통 베이스.
 *
 * <p>양방향 fixture(CasUser ←→ CasOrder) + FK 제약(user_id REFERENCES cas_users)으로
 * cascade 삭제 순서가 DB 레벨에서 강제된다. 각 @BeforeEach마다 nanoTime URL로 격리
 * (SfsEntityManagerFactory에 close() 없음 — MP-2 발견 반영).
 */
abstract class AbstractBidirectionalCascadeTest {

    // public static + public 필드: EntityPersister가 다른 패키지에서 reflection newInstance/필드 접근(MP-2 I-1)
    @SfsEntity(name = "cas_users")
    public static class CasUser {
        @SfsId @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "cas_users_seq")
        public Long id;
        @SfsColumn public String name;
        @SfsOneToMany(mappedBy = "user",
                cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
        public List<CasOrder> orders = new ArrayList<>();

        /** 양방향 일관성 helper — 양쪽 세팅(application 책임 박제). */
        public void addOrder(CasOrder o) { orders.add(o); o.user = this; }
        public void removeOrder(CasOrder o) { orders.remove(o); o.user = null; }
        public List<CasOrder> getOrders() { return orders; }
    }

    @SfsEntity(name = "cas_orders")
    public static class CasOrder {
        @SfsId @SfsGeneratedValue(strategy = SEQUENCE, sequenceName = "cas_orders_seq")
        public Long id;
        @SfsManyToOne(fetch = SfsManyToOne.FetchType.LAZY) @SfsJoinColumn(name = "user_id")
        public CasUser user;
        @SfsColumn public String item;

        public CasUser getUser() { return user; }
    }

    protected JdbcDataSource ds;
    protected ThreadLocalTsm tsm;
    protected DataSourceTransactionManager tm;
    protected SfsEntityManagerFactory emf;
    protected SfsTransactionalEntityManager em;

    /** 하위 클래스가 jdbcTemplate 주입(spy 필요 시 override). 기본은 null = 빌더 기본 JdbcTemplate. */
    protected com.choisk.sfs.tx.jdbc.JdbcTemplate jdbcTemplateOverride() { return null; }

    @BeforeEach
    void setupBidirectional() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cas-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);

        com.choisk.sfs.tx.jdbc.JdbcTemplate ddl =
                new com.choisk.sfs.tx.jdbc.JdbcTemplate(ds, tsm);
        ddl.update("CREATE SEQUENCE cas_users_seq START WITH 1");
        ddl.update("CREATE SEQUENCE cas_orders_seq START WITH 1");
        ddl.update("CREATE TABLE cas_users (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        // FK 제약: cascade 삭제 순서가 틀리면 H2가 위반 예외 → "자식 먼저"가 통과 필요조건
        ddl.update("CREATE TABLE cas_orders (id BIGINT PRIMARY KEY, user_id BIGINT, item VARCHAR(50), "
                + "FOREIGN KEY (user_id) REFERENCES cas_users(id))");

        SfsEntityManagerFactory.Builder b = SfsEntityManagerFactory.builder()
                .dataSource(ds).transactionSynchronizationManager(tsm)
                .addEntityClass(CasUser.class)
                .addEntityClass(CasOrder.class);
        if (jdbcTemplateOverride() != null) b.jdbcTemplate(jdbcTemplateOverride());
        emf = b.build();
        em = new SfsTransactionalEntityManager(new SfsEntityManagerFactoryBean(emf, tsm), tsm);
    }
}
```

> **검증 포인트:** `SfsEntityManagerFactory.Builder` 타입명이 실제 빌더 반환 타입과 일치하는지 확인(`SfsEntityManagerFactory.builder()` 반환형). 다르면 `var b = SfsEntityManagerFactory.builder()...`로 대체.

- [ ] **Step 2: 양방향 일관성 함정 테스트 작성**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ① — 양방향 일관성은 application 책임.
 * inverse(컬렉션)만 건드리면 cascade가 INSERT해도 owning side(user) 미설정 → FK null.
 * helper(addOrder)로 양쪽 세팅하면 FK 정상.
 */
class BidirectionalConsistencyIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void inverse만_추가하면_cascade_INSERT되어도_FK가_null() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            em.persist(u);            // user INSERT 등록(SEQUENCE)

            CasOrder o = new CasOrder();
            o.item = "book";
            u.getOrders().add(o);     // ★ helper 미사용 — owning(o.user) 미설정
            em.persist(u);            // managed 재persist → cascade → o INSERT 등록

            em.flush();
            return null;
        });

        // o는 INSERT됐지만 user_id가 NULL (함정)
        Long nullFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cas_orders WHERE user_id IS NULL", Long.class);
        assertThat(nullFk).isEqualTo(1L);
    }

    @Test
    void helper로_양쪽_세팅하면_FK_정상() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            em.persist(u);

            CasOrder o = new CasOrder();
            o.item = "book";
            u.addOrder(o);            // ★ helper — orders.add + o.user=this
            em.persist(u);

            em.flush();
            return null;
        });

        Long correctFk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cas_orders WHERE user_id = 1", Long.class);
        assertThat(correctFk).isEqualTo(1L);
    }
}
```

- [ ] **Step 3: 실행 — 통과 확인**

Run: `./gradlew :sfs-orm:test --tests BidirectionalConsistencyIntegrationTest`
Expected: 2 PASS. (Task 1~8 구현이 cascade persist + 양방향 매핑을 지원하므로 GREEN. 만약 첫 실행이 빌더/타입 문제로 실패하면 Step 1의 검증 포인트 적용.)

- [ ] **Step 4: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/AbstractBidirectionalCascadeTest.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/integration/BidirectionalConsistencyIntegrationTest.java
git commit -m "test(sfs-orm): 양방향 일관성 함정(FK null) 통합 — 학습 정점 ① + cascade 베이스"
```

---

## Task 10: 통합 — cascade PERSIST E2E (SQL 카운트)

> 통합 — 학습 정점 ② (persist 전파). SqlCounting으로 INSERT 횟수 박제.

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/CascadePersistIntegrationTest.java`

- [ ] **Step 1: 테스트 작성 (spy 주입)**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ② — cascade PERSIST 전파. persist(user) 1회로 user + order들이 모두 INSERT.
 * MP-2 부채(add()+persist(user)만으론 order INSERT 안 됨)의 회수 박제.
 */
class CascadePersistIntegrationTest extends AbstractBidirectionalCascadeTest {

    private SqlCountingJdbcTemplate spy;

    @Override
    protected com.choisk.sfs.tx.jdbc.JdbcTemplate jdbcTemplateOverride() {
        if (spy == null) spy = new SqlCountingJdbcTemplate(ds, tsm);
        return spy;
    }

    @Test
    void persist_user_1회로_user와_order_2건이_cascade_INSERT() {
        TransactionTemplate.execute(tm, () -> {
            spy.reset();

            CasUser u = new CasUser();
            u.name = "alice";
            CasOrder o1 = new CasOrder(); o1.item = "book";
            CasOrder o2 = new CasOrder(); o2.item = "pen";
            u.addOrder(o1);
            u.addOrder(o2);

            em.persist(u);   // cascade PERSIST: user + o1 + o2 InsertAction 등록
            em.flush();      // 3건 INSERT 실행

            // INSERT INTO cas_users 1 + INSERT INTO cas_orders 2 = 3
            assertThat(spy.countMatching("INSERT INTO cas_users")).isEqualTo(1);
            assertThat(spy.countMatching("INSERT INTO cas_orders")).isEqualTo(2);
            return null;
        });
    }
}
```

> **주의:** `jdbcTemplateOverride()`가 `setupBidirectional()`의 `b.jdbcTemplate(...)` 시점에 호출되는데, 그 시점엔 `ds`/`tsm`이 이미 초기화돼 있다(베이스 setup 내 DDL 이후 빌더 단계). spy 생성 시 `ds`/`tsm` non-null 보장됨.

- [ ] **Step 2: 실행 — 통과 확인**

Run: `./gradlew :sfs-orm:test --tests CascadePersistIntegrationTest`
Expected: 1 PASS.

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/CascadePersistIntegrationTest.java
git commit -m "test(sfs-orm): cascade PERSIST E2E — persist(user) 1회로 order cascade INSERT (정점 ②)"
```

---

## Task 11: 통합 — cascade REMOVE E2E + 삭제 순서 (FK)

> 통합 — 학습 정점 ② (remove 전파). FK 제약이 "자식 먼저"를 강제.

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/CascadeRemoveIntegrationTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ② — cascade REMOVE 전파 + 삭제 순서. remove(user) 1회로 order까지 DELETE.
 * cas_orders.user_id FK 제약이 있으므로 자식(order)이 부모(user)보다 먼저 DELETE되어야 위반 없이 통과.
 */
class CascadeRemoveIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void remove_user_1회로_order까지_cascade_DELETE_FK위반_없음() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);

        // given: user 1 + order 2 영속화
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            CasOrder o1 = new CasOrder(); o1.item = "book";
            CasOrder o2 = new CasOrder(); o2.item = "pen";
            u.addOrder(o1);
            u.addOrder(o2);
            em.persist(u);
            em.flush();
            return null;
        });

        // when: 새 트랜잭션에서 user 조회 후 remove (cascade REMOVE)
        TransactionTemplate.execute(tm, () -> {
            CasUser u = em.find(CasUser.class, 1L);
            em.remove(u);     // 자식 order들 DeleteAction 먼저, user 마지막
            em.flush();       // FK 순서 — order DELETE → user DELETE
            return null;
        });

        // then: 양쪽 테이블 모두 비어야 함 (FK 위반 없이)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_users", Long.class)).isZero();
    }
}
```

> remove의 cascade가 `u.getOrders()`(SfsPersistentList)를 순회 → lazy 초기화 → 자식 2건 로드 + identityMap 등재 → 각 remove(child) → DeleteAction. find→remove→flush가 같은 트랜잭션 내라 PC가 열려 있어 lazy 발화 가능.

- [ ] **Step 2: 실행 — 통과 확인**

Run: `./gradlew :sfs-orm:test --tests CascadeRemoveIntegrationTest`
Expected: 1 PASS. (삭제 순서가 틀렸다면 H2 FK 위반으로 FAIL → "자식 먼저" 구현 검증.)

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/CascadeRemoveIntegrationTest.java
git commit -m "test(sfs-orm): cascade REMOVE E2E — FK 제약으로 자식 먼저 삭제 순서 검증 (정점 ②)"
```

---

## Task 12: 통합 — orphanRemoval E2E

> 통합 — 학습 정점 ③.

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/OrphanRemovalIntegrationTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학습 정점 ③ — orphanRemoval. 컬렉션에서 빠진 element가 flush 시 DELETE.
 * cascade(호출 전파)와 다른 트리거: 컬렉션 변화 감지(snapshot diff).
 */
class OrphanRemovalIntegrationTest extends AbstractBidirectionalCascadeTest {

    @Test
    void 컬렉션에서_removeOrder한_order만_flush_시_DELETE() {
        JdbcTemplate jdbc = new JdbcTemplate(ds, tsm);

        // given: user 1 + order 2
        TransactionTemplate.execute(tm, () -> {
            CasUser u = new CasUser();
            u.name = "alice";
            CasOrder o1 = new CasOrder(); o1.item = "book";
            CasOrder o2 = new CasOrder(); o2.item = "pen";
            u.addOrder(o1);
            u.addOrder(o2);
            em.persist(u);
            em.flush();
            return null;
        });

        // when: 컬렉션에서 한 건 제거 후 flush
        TransactionTemplate.execute(tm, () -> {
            CasUser u = em.find(CasUser.class, 1L);
            CasOrder first = u.getOrders().get(0);   // 초기화 → storedSnapshot=[o1,o2]
            u.removeOrder(first);                     // delegate=[o2], owning(first.user)=null
            em.flush();                               // orphan(first) DELETE
            return null;
        });

        // then: order 1건만 남음
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_orders", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cas_users", Long.class)).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 실행 — 통과 확인**

Run: `./gradlew :sfs-orm:test --tests OrphanRemovalIntegrationTest`
Expected: 1 PASS.

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :sfs-orm:test`
Expected: 누적 PASS.

- [ ] **Step 4: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/OrphanRemovalIntegrationTest.java
git commit -m "test(sfs-orm): orphanRemoval E2E — 컬렉션 제거 element flush 시 DELETE (정점 ③)"
```

---

## Task 13: 데모 도메인 마이그레이션 + 시연 DK~DN

> TDD 제외(demo — 컴파일 + 실행/기존 테스트 회귀로 검증). 통합 테스트(Task 9~12)가 동작 안전망.

**Files:**
- Modify: `sfs-samples/.../orm/domain/User.java`
- Modify: `sfs-samples/.../orm/service/UserService.java`
- Modify: `sfs-samples/.../orm/OrmDemoApplication.java`

- [ ] **Step 1: `User.orders`를 양방향 + helper로 마이그레이션**

`User.java`: `orders` 필드 어노테이션 교체 + 초기화 + helper. `import java.util.ArrayList;`, `import com.choisk.sfs.orm.annotation.SfsCascadeType;` 추가.

```java
    @SfsOneToMany(mappedBy = "user",
            cascade = {SfsCascadeType.PERSIST, SfsCascadeType.REMOVE}, orphanRemoval = true)
    private List<Order> orders = new ArrayList<>();
```

기존 getter/setter 유지 + helper 추가:

```java
    /** 양방향 일관성 helper — 양쪽 세팅(application 책임 박제). */
    public void addOrder(Order o) { orders.add(o); o.setUser(this); }
    public void removeOrder(Order o) { orders.remove(o); o.setUser(null); }
```

> `Order.setUser`는 이미 존재(Order.java:45). owning side(@SfsManyToOne user_id) 그대로.

- [ ] **Step 2: `UserService`에 DK~DN 시나리오 메서드 추가**

기존 `tryAddOrderWithoutCascade`는 *단방향 학습 산출물*로 존치(증상 측). 신규 메서드 추가(시그니처는 데모 흐름에 맞춰; 아래는 권장 형태):

```java
    /** DK — cascade PERSIST 회수: addOrder + persist(user) 1회로 order 자동 INSERT. */
    public void cascadePersistDemo(EntityManager em) {
        User u = new User();
        u.setName("dk-user"); u.setEmail("dk@x.com");
        em.persist(u);
        Order o = new Order();
        o.setAmount(new BigDecimal("10.00")); o.setStatus("NEW");
        u.addOrder(o);            // 양쪽 세팅
        em.persist(u);            // managed 재persist → cascade → o INSERT
        em.flush();
        System.out.println("[DK] addOrder + persist(user) → order 자동 INSERT (cascade PERSIST — MP-2 부채 회수)");
    }

    /** DM — cascade REMOVE: remove(user) 1회로 order까지 삭제. */
    public void cascadeRemoveDemo(EntityManager em, Long userId) {
        User u = em.find(User.class, userId);
        em.remove(u);
        em.flush();
        System.out.println("[DM] remove(user) → 자식 order까지 cascade DELETE (자식 먼저)");
    }

    /** DN — orphanRemoval: 컬렉션에서 제거한 order만 삭제. */
    public void orphanRemovalDemo(EntityManager em, Long userId) {
        User u = em.find(User.class, userId);
        if (!u.getOrders().isEmpty()) {
            u.removeOrder(u.getOrders().get(0));
        }
        em.flush();
        System.out.println("[DN] removeOrder → 해당 order만 orphanRemoval DELETE");
    }
```

> import: `com.choisk.sfs.orm.SfsEntityManager` 또는 데모가 쓰는 EM 타입(`OrmDemoApplication`의 기존 사용 패턴에 맞춤 — 실제 타입명 확인). `java.math.BigDecimal`. **DL(양방향 함정)**은 통합 테스트가 박제하므로 console에서는 DK에 "helper 안 쓰면 FK null" 한 줄 주석으로 갈음하거나, 함정 발화 메서드를 추가(선택).

- [ ] **Step 3: `OrmDemoApplication`에 DK~DN 호출 추가**

기존 MP-2 시연(DH~DJ) 블록 뒤에 MP-3 블록 추가. `tryAddOrderWithoutCascade`의 "MP-3에서 자동화 예정" 약속이 DK로 이행됨을 console에 명시:

```java
            System.out.println("\n=== MP-3: 양방향 + cascade + orphanRemoval 시연 ===\n");
            userService.cascadePersistDemo(em);
            // DM/DN은 사전 데이터(user+orders)가 있는 userId로 호출
            // (OrmDemoApplication의 기존 시드 데이터 흐름에 맞춰 userId 전달)
```

> 정확한 호출부는 `OrmDemoApplication`의 기존 `em`/`userService` 변수와 시드 흐름에 맞춰 작성. 본 step은 *컴파일되는 호출*을 추가하는 것이 목표.

- [ ] **Step 4: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-samples:compileJava :sfs-samples:test`
Expected: BUILD SUCCESSFUL. (User.orders 양방향 전환이 기존 sfs-samples 테스트에 영향 없는지 확인 — 영향 시 해당 테스트 조정.)

- [ ] **Step 5: 데모 실행 확인 (선택)**

Run: `./gradlew :sfs-samples:run` (OrmDemoApplication이 run 타깃일 경우) 또는 해당 main 실행.
Expected: console에 DK~DN 메시지 출력, 예외 없음.

- [ ] **Step 6: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/orm/domain/User.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/UserService.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/orm/OrmDemoApplication.java
git commit -m "feat(sfs-samples): User.orders 양방향 마이그레이션 + DK~DN 시연(cascade/orphan 박제)"
```

---

## Task 14: 전체 빌드 + DoD 갱신 + 마감 게이트 준비

> 코드 task 아님 — Plan/Phase 마감 의식 진입.

**Files:**
- Modify: `docs/superpowers/specs/2026-05-23-mp3-bidirectional-cascade-design.md` (DoD 체크박스)

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 회귀 카운트 확인(예상 319 → ~337). 실제 카운트를 기록.

- [ ] **Step 2: spec DoD 체크박스 갱신**

`spec § 8`의 14항목을 실제 완료분에 맞춰 `- [ ]` → `- [x]`. 회귀 카운트 실측치를 항목 14에 기입.

- [ ] **Step 3: 커밋**

```bash
git add docs/superpowers/specs/2026-05-23-mp3-bidirectional-cascade-design.md
git commit -m "docs(mp3): DoD 14항목 체크 + 회귀 카운트 실측 기입"
```

- [ ] **Step 4: 마감 게이트 (CLAUDE.md "완료 후 품질 게이트")**

전 task 완료 + DoD 만족 시점에 다음 3단계 수행(별도 실행, 본 plan 범위의 종료 의식):
1. **다관점 코드리뷰** — `feature-dev:code-reviewer` 에이전트 + `superpowers:requesting-code-review`. 결과 "남겨둘/즉시 고칠" 분류.
2. **리팩토링** — "즉시 고칠"만 `refactor(sfs-orm): ...`로 반영. 기존 테스트 PASS 유지.
3. **`/simplify` 패스** — 중복/데드코드 정리, diff 단위 검토 후 가치분만 `refactor`/`chore`.
4. plan 하단에 `> **품질 게이트 기록 (YYYY-MM-DD):**` 블록(지적 N / 반영 M / 보류 K) 박제.

게이트 통과(빌드 PASS + 회귀 카운트 일치 + DoD 14/14 [x]) 후 **main 머지는 사용자 직접**([[project_mp2_design_resume_point]] 정합).

---

## Self-Review

**1. Spec coverage:**
- § 3.1 SfsCascadeType → Task 1 ✓ / § 3.2 @SfsOneToMany → Task 1 ✓ / § 3.3 CollectionMetadata → Task 2 ✓ / § 3.4 XOR+mappedBy → Task 3,4 ✓ / § 3.6 SfsPersistentList → Task 5 ✓ / § 3.5 cascade persist/remove → Task 6,7 ✓ / § 3.7 flush orphan → Task 8 ✓ / § 4.4 양방향 함정 → Task 9 ✓ / § 5 demo → Task 13 ✓ / § 7.3 통합 4종 → Task 9~12 ✓ / § 8 DoD → Task 14 ✓.
- 모든 spec 섹션이 task에 매핑됨.

**2. Placeholder scan:** Task 13 Step 2~3은 "기존 흐름에 맞춤"으로 일부 호출부를 demo 실제 구조에 위임 — demo는 TDD 제외 + 통합 안전망 존재라 의도적. 그 외 모든 코드 step은 완전한 코드 포함.

**3. Type consistency:**
- `CollectionMetadata(field, elementType, joinColumnName, mappedBy, cascadeTypes, orphanRemoval)` 6인자 — Task 2 정의 / Task 2,4 호출 일치 ✓.
- `cascadesPersist()`/`cascadesRemove()` — Task 2 정의 / Task 6,7 사용 일치 ✓.
- `SfsPersistentList.findOrphans()` no-arg — Task 5 정의 / Task 8 사용 일치 ✓.
- `doPersist(entity, visited)` / `isManaged` / `insertNew` / `readField` / `readId` / `hasPendingDelete` — Task 6,7,8 간 시그니처 일치 ✓.
- `SfsCascadeType {PERSIST, REMOVE, ALL}` — 전 task 일관 ✓.

**검증 필요 1건:** Task 9 `SfsEntityManagerFactory.Builder` 내부 빌더 타입명 — 구현 시 실제 반환형 확인(불일치 시 `var`로 대체). executing 단계 첫 빌드에서 자연 노출.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-23-mp3-bidirectional-cascade.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — task마다 fresh subagent 디스패치, task 사이 2단계 리뷰, 빠른 반복. (CLAUDE.md 위임 표준 헤더 + `superpowers:subagent-driven-development`)

**2. Inline Execution** — 이 세션에서 `superpowers:executing-plans`로 체크포인트 배치 실행.

**구현은 별도 세션 권장**([[feedback_design_implementation_session_split]]) — 본 세션은 디자인(spec+plan)까지. 구현 세션 진입 전 **MP-2 → main 머지**(사용자 직접) 후 `feat/mp3-bidirectional-cascade`를 main에 rebase.

**어느 방식으로 진행할까요?**
