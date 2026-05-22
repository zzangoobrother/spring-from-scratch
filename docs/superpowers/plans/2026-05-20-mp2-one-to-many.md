# MP-2 `@SfsOneToMany` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (recommended) 또는 `superpowers:executing-plans`로 task-by-task 실행. Step은 체크박스(`- [ ]`) 형식.

**Goal:** sfs-orm에 단방향 `@SfsOneToMany` 어노테이션 + `SfsPersistentList<T>` collection wrapper(Hibernate 본가, byte-buddy 미사용) + `findAll(Class<T>)` API를 박제하여 *collection lazy 발화*와 *N+1 자연 노출* 두 학습 정점을 회수.

**Architecture:** Phase 4 ORM 인프라(`EntityMetadataAnalyzer`, `EntityPersister`, `PersistenceContext`) 재사용 + 신설 5컴포넌트(`@SfsOneToMany`, `CollectionMetadata`, `SfsPersistentList`, `CollectionLoader`, `DefaultCollectionLoader`). cascade · orphanRemoval은 의식적 미도입 — 학습 짝패는 MP-3가 회수. spy `SqlCountingJdbcTemplate`은 *테스트 전용 서브클래스*로 production 변경 0.

**Tech Stack:** Java 25 LTS · JUnit 5 + AssertJ · H2 (Phase 4 도입분 재사용) · sfs-tx `JdbcTemplate` (Phase 4 + PRE 도입분 재사용)

**선행 의존:** Phase 4 (ORM 핵심) — main `0336875` 머지 완료, 회귀 304 PASS / 0 FAIL.

**연관 spec:** `docs/superpowers/specs/2026-05-19-mp2-one-to-many-design.md`

**브랜치:** `feat/mp2-one-to-many` (main `0336875`에서 분기, 본 plan 작성 시점 HEAD `2dff939`)

---

> **플랜 정정 기록 (2026-05-22):** 구현 세션 진입 전 실제 Phase 4 코드(main `0336875`)와 plan 가정을 대조 검증해 4건 정정. 셀프 리뷰(시그니처 대조)가 못 잡은, *호출 그래프를 따라가야 보이는* 책임 배치 결함이 핵심.
>
> 1. **결함 ① (D1·E1·E2 관통) — identityMap 등록 책임 위치.** 현재 메인 엔티티의 identityMap 등록은 `EntityPersister.buildRowMapper`가 아니라 `RealEntityManager.find()`(RealEntityManager.java:179)에서 일어남. `findByForeignKey`(D1)·`findAll`(E2)은 EM 레이어를 우회하므로 로드된 엔티티가 등록 안 됨 → D1·E2 테스트의 `ctx.contains(...)` 단언 FAIL. 원래 E1 "cache hit 재사용 보강"은 READ만 있고 PUT(`putEntity`)이 빠져 있었고, 순서상 D1 < E1이라 D1 시점엔 캐시 로직 전무. **수정: `buildRowMapper`가 cache-hit read + `context.putEntity` 등록을 모두 수행하도록 하고, 이 변경을 D1로 이동(정점 ② "1 entity=1 instance"의 본가 hydration 위치). E1은 oneToManies(SfsPersistentList) 채우기만 담당.** `find()`의 putEntity는 멱등이라 안전, snapshot 부재는 flush가 graceful 처리(RealEntityManager.java:234~238) → 회귀 위험 낮음.
> 2. **결함 ② (E3) — `SfsTransactionalEntityManager` 위임 누락.** 이 클래스는 동적 프록시가 아니라 *손으로 위임 메서드를 작성한* 구현체(`implements SfsEntityManager`, 라인 53~81). "시그니처 추가만으로 자동 위임" 주장은 틀림 — 인터페이스에 `findAll` 추가 시 미구현으로 **컴파일 에러**. M1/M2가 `em.findAll`을 이 클래스에서 호출하므로 위임 메서드 필수. **수정: E3 파일 목록·단계에 `SfsTransactionalEntityManager.findAll` 추가.**
> 3. **G1 커밋 메시지 오타:** `DoD 14/13` → `14/14`.
> 4. **자율판단 영역 해소(M1 라인):** `TransactionTemplate.execute`는 실제로 `static <T> T execute(PlatformTransactionManager, Supplier<T>)` (TransactionTemplate.java:43) — plan의 `() -> {...; return null;}` / `() -> em.find(...)` 패턴과 정합 확인. implementer가 시그니처를 다시 의심할 필요 없음.

## 0. 실행 가이드

각 Task는 다음 사이클을 따른다 (CLAUDE.md "플랜 실행 루틴"):

1. 실패 테스트 작성 *(TDD 적용 대상에 한함, spec § 7.1 분류)*
2. `./gradlew :<module>:test --tests <TestName>` 로 FAIL 확인
3. 최소 구현
4. PASS 확인 *(TDD 제외 대상은 1~2 생략, 3 이후 컴파일 + 회귀 테스트만)*
5. 한국어 커밋 메시지로 커밋
6. Plan 문서의 해당 Step 체크박스 `- [ ]` → `- [x]` 업데이트
7. 실행 중 편차 발생 시 `> **실행 기록 (YYYY-MM-DD):**` 블록으로 박제

### 0.1 TDD 적용/제외 (spec § 7.1 정합)

**TDD 적용 (필수)**:
- `EntityMetadataAnalyzer` `@SfsOneToMany` 분기 + fail-fast 3종
- `SfsPersistentList<T>` (라이프사이클 + 모든 메서드 trigger + closed 예외 + identity 보장)
- `CollectionLoader` / `DefaultCollectionLoader` (SQL 패턴 박제 — D1 task 1건 + persister 위임)
- `EntityPersister.findAll` (새 SQL 패턴)
- 모든 통합 시나리오 (M1, M2)

**TDD 제외 (회귀 검증만)**:
- `@SfsOneToMany` 어노테이션 (단순 마커 + LAZY enum)
- `CollectionMetadata` record (DTO)
- `EntityMetadata` 필드 추가 (record 시그니처 변경 — 호출지 1곳만 정정)
- `EntityPersister.buildRowMapper` 분기 추가 (구현만, 통합 M1이 회수)
- `EntityPersister.findByForeignKey` (구현만, `DefaultCollectionLoader` 테스트가 회수)
- `RealEntityManager.findAll` + `SfsEntityManager.findAll` 시그니처 (구현만, M2가 회수)
- `User.orders` 도메인 확장 (POJO)
- `OrmDemoApplication` 시연 3건 (manual run)
- `SqlCountingJdbcTemplate` (test 전용 spy, 사용처가 검증)

### 0.2 커밋 컨벤션 (CLAUDE.md 정합)

- 한국어 커밋 메시지
- `test(sfs-orm): ...` (테스트 추가)
- `feat(sfs-orm): ...` (동작 변경)
- `refactor(sfs-orm): ...` (단순화)
- `docs: ...` (plan 체크박스 / spec 갱신)

### 0.3 회귀 카운트

| 시작 | 목표 | 차이 |
|---|---|---|
| 304 PASS (Phase 4 마감) | **318 PASS** | **+14** (단위 10 / 통합 4 — spec § 7.2) |

±2 자연 변동 허용 (Phase 4 추정 +52 → 실측 +60 패턴 정합).

### 0.4 모듈 의존 그래프 (spec § 2.1 — 불변)

```
sfs-samples ──► sfs-orm ──► sfs-tx ──► sfs-aop ──► sfs-beans ──► sfs-core
                  │
                  └─► byte-buddy  (entity proxy에만, collection wrapper에는 미사용)
```

byte-buddy 의존성 *유지*. `SfsPersistentList`는 *byte-buddy 없이* 직접 작성.

---

## 1. 섹션 구조 (Task 한 줄 요약)

| Task | 내용 | 모듈 | TDD | DoD 매핑 | 회귀 |
|---|---|---|---|---|---|
| **A1** | `@SfsOneToMany` 어노테이션 (LAZY only) | sfs-orm | 제외 | 1 | +0 |
| **A2** | `CollectionMetadata` record | sfs-orm | 제외 | 2 | +0 |
| **A3** | `EntityMetadata` 필드 2개(oneToManies + selectAllSql) + `Analyzer.buildSelectAllSql` 헬퍼 | sfs-orm | 제외 | 3 | +0 |
| **B1** | `EntityMetadataAnalyzer` `@SfsOneToMany` 분기 + generic 추출 + fail-fast 3종 | sfs-orm | 적용 | 4, 10 | +4 |
| **C1** | `SfsPersistentList<T>` wrapper | sfs-orm | 적용 | 5, 9 | +4 |
| **D1** | `DefaultCollectionLoader` + `EntityPersister.findByForeignKey` + `buildRowMapper` cache-hit read·identityMap 등록(정점 ②, 정정 ①) | sfs-orm | 적용 | 6, 8 | +1 |
| **E1** | `EntityPersister.buildRowMapper` oneToManies(SfsPersistentList) 채우기 *(cache-hit·등록은 D1로 이동)* | sfs-orm | 제외(통합 회수) | 7 | +0 |
| **E2** | `EntityPersister.findAll` (SELECT *) | sfs-orm | 적용 | 8 | +1 |
| **E3** | `SfsEntityManager.findAll` 시그니처 + `RealEntityManager.findAll` + `SfsTransactionalEntityManager.findAll` 위임(정정 ②) | sfs-orm | 제외(통합 회수) | 8 | +0 |
| **F1** | `User.orders: List<Order>` + `@SfsOneToMany` 도메인 확장 | sfs-samples | 제외 | 11 | +0 |
| **M1** | `SqlCountingJdbcTemplate` 신설 + `OneToManyLazyIntegrationTest` (lazy 발화 + closed 예외) | sfs-orm | 적용 | 12 | +2 |
| **M2** | `NPlusOneIntegrationTest` (findAll + spy SQL count) | sfs-orm | 적용 | 12 | +2 |
| **F2** | `OrmDemoApplication` 시연 3건 (DH/DI/DJ) + UserService 확장 + manual run | sfs-samples | 제외 | 11 | +0 |
| **G1** | 전체 빌드 + 회귀 +14 검증 + DoD 체크박스 갱신 | (전체) | 제외 | 13 | +0 |
| **G2** | 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify) | (전체) | 제외 | 14 | +0 (별도) |

**총 14 task / +10 단위 + +4 통합 = +14 PASS / DoD 14 항목 모두 매핑**

---

## 2. File Structure (생성/수정 파일 매핑)

### 2.1 신설 파일

```
sfs-orm/src/main/java/com/choisk/sfs/orm/
├── annotation/
│   └── SfsOneToMany.java                                   # A1
└── support/
    ├── CollectionMetadata.java                             # A2
    ├── SfsPersistentList.java                              # C1
    ├── CollectionLoader.java                               # D1 (interface)
    └── DefaultCollectionLoader.java                        # D1 (구현)

sfs-orm/src/test/java/com/choisk/sfs/orm/
├── support/
│   ├── EntityMetadataAnalyzerOneToManyTest.java            # B1
│   ├── SfsPersistentListTest.java                          # C1
│   ├── DefaultCollectionLoaderTest.java                    # D1
│   └── EntityPersisterFindAllTest.java                     # E2
└── integration/
    ├── SqlCountingJdbcTemplate.java                        # M1 (test 인프라)
    ├── OneToManyLazyIntegrationTest.java                   # M1
    └── NPlusOneIntegrationTest.java                        # M2
```

### 2.2 수정 파일

```
sfs-orm/src/main/java/com/choisk/sfs/orm/
├── support/
│   ├── EntityMetadata.java                  # A3 (oneToManies + selectAllSql 필드 2개 추가)
│   ├── EntityMetadataAnalyzer.java          # A3 (buildSelectAllSql 헬퍼), B1 (@SfsOneToMany 분기 + fail-fast)
│   └── EntityPersister.java                 # D1 (findByForeignKey + buildRowMapper cache-read/등록), E1 (buildRowMapper oneToManies), E2 (findAll)
├── RealEntityManager.java                   # E3 (findAll 메서드 추가)
├── SfsEntityManager.java                    # E3 (findAll 시그니처 추가)
├── SfsEntityManagerFactory.java             # D1 (collectionLoader 빈 노출 + Builder.jdbcTemplate 옵션)
└── boot/SfsTransactionalEntityManager.java  # E3 (findAll 위임 메서드 추가 — 손수 위임 구현체이므로 자동 위임 안 됨)

sfs-samples/src/main/java/com/choisk/sfs/samples/orm/
├── domain/User.java                         # F1 (orders 필드 + getter/setter)
├── service/UserService.java                 # F2 (dumpAllUserOrders / describeUserOrders / tryAddOrderWithoutCascade)
└── OrmDemoApplication.java                  # F2 (DH/DI/DJ 시나리오 3건 추가)
```

### 2.3 갱신 (Phase 마감 시)

```
docs/superpowers/plans/2026-05-20-mp2-one-to-many.md     # 모든 task 체크박스 [x] 갱신
docs/superpowers/specs/2026-05-19-mp2-one-to-many-design.md  # § 8 DoD 14/14 [x] + 회귀 실측 박제
```

---

## 섹션 A: 어노테이션 + record + EntityMetadata 확장

### Task A1: `@SfsOneToMany` 어노테이션 (LAZY only)

**Files:**
- Create: `sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsOneToMany.java`

- [x] **Step 1: 어노테이션 신설**

```java
package com.choisk.sfs.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 단방향 OneToMany 관계 — 컬렉션 필드에 적용.
 *
 * <p>spec § 3.1 정합: FetchType.LAZY only — EAGER collection은 MP-2-α 별도 mini-phase로 이월.
 * targetEntity는 generic erasure 자동 추출({@code ParameterizedType.getActualTypeArguments[0]}).
 *
 * <p>예시:
 * <pre>{@code
 * @SfsOneToMany(joinColumn = "user_id")
 * private List<Order> orders;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SfsOneToMany {

    /** lazy fetch 전략 — MP-2는 LAZY만 지원. */
    FetchType fetch() default FetchType.LAZY;

    /** 대상 테이블의 FK 컬럼명 (예: "user_id"). */
    String joinColumn();

    /** Fetch type — MP-2는 LAZY만 정의. EAGER는 MP-2-α 이월 박제. */
    enum FetchType { LAZY }
}
```

- [x] **Step 2: 컴파일 확인**

Run: `./gradlew :sfs-orm:compileJava`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/annotation/SfsOneToMany.java
git commit -m "feat(sfs-orm): @SfsOneToMany 어노테이션 신설 — LAZY only + joinColumn 속성 (A1)"
```

- [x] **Step 4: plan 체크박스 갱신**

Plan 문서의 Task A1 모든 Step `- [x]` → `- [x]`.

---

### Task A2: `CollectionMetadata` record

**Files:**
- Create: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java`

- [x] **Step 1: record 신설**

```java
package com.choisk.sfs.orm.support;

import java.lang.reflect.Field;

/**
 * @SfsOneToMany 필드 분석 결과를 담는 불변 record.
 *
 * <p>spec § 3.2 정합. RelationMetadata와 분리 유지 — joinColumnName이 ManyToOne(내 테이블의 FK)과
 * OneToMany(대상 테이블의 FK)에서 의미가 다르므로 한 record로 묶으면 의미 오버로드 발생.
 *
 * @param field             컬렉션 필드 (예: User.orders)
 * @param elementType       컬렉션 element 타입 (ParameterizedType 추출 결과, 예: Order.class)
 * @param joinColumnName    대상 테이블의 FK 컬럼명 (예: "user_id")
 */
public record CollectionMetadata(
        Field field,
        Class<?> elementType,
        String joinColumnName
) { }
```

- [x] **Step 2: 컴파일 확인**

Run: `./gradlew :sfs-orm:compileJava`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionMetadata.java
git commit -m "feat(sfs-orm): CollectionMetadata record 신설 — RelationMetadata와 분리, 의미 오버로드 회피 (A2)"
```

- [x] **Step 4: plan 체크박스 갱신**

---

### Task A3: `EntityMetadata` 필드 2개 추가 + `Analyzer.buildSelectAllSql` 헬퍼

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadata.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java`

- [x] **Step 1: `EntityMetadata` record에 `oneToManies` + `selectAllSql` 필드 추가**

기존 record:
```java
public record EntityMetadata(
        Class<?> entityClass,
        String tableName,
        FieldMetadata idField,
        IdGeneratorSpec idGeneratorSpec,
        List<FieldMetadata> columns,
        List<RelationMetadata> manyToOnes,
        String insertSql,
        String selectByIdSql,
        String deleteSql
) { ... }
```

신 record (필드 2개 추가, hasLazyFields() 메서드 그대로 유지):
```java
public record EntityMetadata(
        Class<?> entityClass,
        String tableName,
        FieldMetadata idField,
        IdGeneratorSpec idGeneratorSpec,
        List<FieldMetadata> columns,
        List<RelationMetadata> manyToOnes,
        List<CollectionMetadata> oneToManies,    // 신설 — manyToOnes 인접 (관계 도메인 묶음)
        String insertSql,
        String selectByIdSql,
        String selectAllSql,                      // 신설 — SELECT * 사전 빌드 (Phase 4 SQL 캐싱 패턴)
        String deleteSql
) {
    /** LAZY fetch 연관 필드가 하나라도 있으면 true — 프록시 생성 여부 판단 기준 */
    public boolean hasLazyFields() {
        return manyToOnes.stream().anyMatch(r -> r.fetch() == FetchType.LAZY);
    }
}
```

- [x] **Step 2: `EntityMetadataAnalyzer.doAnalyze` 호출지 정정 — `oneToManies` + `selectAllSql` 추가**

기존 `doAnalyze` 끝부분:
```java
String insertSql = buildInsertSql(tableName, idField, columns, manyToOnes, idGeneratorSpec);
String selectSql = buildSelectByIdSql(tableName, idField, columns, manyToOnes);
String deleteSql = buildDeleteSql(tableName, idField);

return new EntityMetadata(entityClass, tableName, idMeta, idGeneratorSpec,
        columns, manyToOnes, insertSql, selectSql, deleteSql);
```

신 코드 (oneToManies는 B1에서 채워질 빈 List, selectAllSql 신설):
```java
List<CollectionMetadata> oneToManies = new ArrayList<>();  // B1에서 분기 추가 (A3는 빈 리스트로만 시작)

String insertSql = buildInsertSql(tableName, idField, columns, manyToOnes, idGeneratorSpec);
String selectByIdSql = buildSelectByIdSql(tableName, idField, columns, manyToOnes);
String selectAllSql = buildSelectAllSql(tableName, idField, columns, manyToOnes);  // 신설
String deleteSql = buildDeleteSql(tableName, idField);

return new EntityMetadata(entityClass, tableName, idMeta, idGeneratorSpec,
        columns, manyToOnes, oneToManies,
        insertSql, selectByIdSql, selectAllSql, deleteSql);
```

- [x] **Step 3: `Analyzer.buildSelectAllSql` 헬퍼 신설**

기존 `buildSelectByIdSql` 옆에:
```java
/** SELECT * FROM <table> SQL 생성 — findAll(Class<T>)에서 사용. */
private String buildSelectAllSql(String table, Field idField,
                                  List<FieldMetadata> cols,
                                  List<RelationMetadata> rels) {
    List<String> colNames = allColumnNames(idField, cols, rels, true);
    return "SELECT " + String.join(", ", colNames) + " FROM " + table;
}
```

(`buildSelectByIdSql`과 거의 동일하나 WHERE 절 없음 — Phase 4 *SQL 사전 빌드 패턴* 정합.)

- [x] **Step 4: 컴파일 + 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: BUILD SUCCESSFUL — Phase 4 회귀 304 PASS 그대로 유지. record 시그니처 변경 호출지가 `doAnalyze` 1곳뿐이라 무손실.

- [x] **Step 5: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadata.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java
git commit -m "feat(sfs-orm): EntityMetadata에 oneToManies + selectAllSql 필드 2개 추가 + Analyzer.buildSelectAllSql 헬퍼 (A3)"
```

- [x] **Step 6: plan 체크박스 갱신**

---

## 섹션 B: EntityMetadataAnalyzer `@SfsOneToMany` 분기

### Task B1: `@SfsOneToMany` 분기 + generic 추출 + fail-fast 3종

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java`
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzerOneToManyTest.java`

- [x] **Step 1: 실패 테스트 작성 (4건: 성공 1 + fail-fast 3)**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.exception.SfsEntityMappingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityMetadataAnalyzerOneToManyTest {

    private final EntityMetadataAnalyzer analyzer = new EntityMetadataAnalyzer();

    @Test
    void analyze_OneToMany_성공_시_oneToManies에_CollectionMetadata_등재() {
        EntityMetadata md = analyzer.analyze(ParentEntity.class);

        assertThat(md.oneToManies()).hasSize(1);
        CollectionMetadata col = md.oneToManies().get(0);
        assertThat(col.field().getName()).isEqualTo("children");
        assertThat(col.elementType()).isEqualTo(ChildEntity.class);
        assertThat(col.joinColumnName()).isEqualTo("parent_id");
    }

    @Test
    void analyze_OneToMany_List_외_타입은_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(SetCollectionEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("must be List<T>");
    }

    @Test
    void analyze_OneToMany_raw_List는_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(RawListEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("generic type parameter");
    }

    @Test
    void analyze_OneToMany_elementType이_비엔티티이면_fail_fast() {
        assertThatThrownBy(() -> analyzer.analyze(NonEntityElementEntity.class))
                .isInstanceOf(SfsEntityMappingException.class)
                .hasMessageContaining("not annotated with @SfsEntity");
    }

    // ─── 테스트 fixture 엔티티 ──────────────────────────────────────────

    @SfsEntity(name = "parent")
    static class ParentEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "parent_id")
        List<ChildEntity> children;
    }

    @SfsEntity(name = "child")
    static class ChildEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
    }

    @SfsEntity(name = "set_owner")
    static class SetCollectionEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsOneToMany(joinColumn = "owner_id")
        Set<ChildEntity> tags;
    }

    @SfsEntity(name = "raw_owner")
    static class RawListEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SuppressWarnings("rawtypes")
        @SfsOneToMany(joinColumn = "owner_id")
        List orders;
    }

    @SfsEntity(name = "nonentity_owner")
    static class NonEntityElementEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsOneToMany(joinColumn = "owner_id")
        List<String> tags;
    }
}
```

- [x] **Step 2: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerOneToManyTest`
Expected: 4건 모두 FAIL — `EntityMetadataAnalyzer.doAnalyze`가 `@SfsOneToMany` 분기 미보유.

- [x] **Step 3: `EntityMetadataAnalyzer.doAnalyze` 분기 추가 + validateOneToMany + extractGenericType 신설**

기존 `doAnalyze`의 *4) 일반 컬럼 + 연관 관계 필드 수집* 루프 안에 `else if` 추가:

```java
for (Field f : entityClass.getDeclaredFields()) {
    f.setAccessible(true);
    if (f.equals(idField)) continue;
    if (f.isAnnotationPresent(SfsManyToOne.class)) {
        validateManyToOne(f);
        SfsManyToOne rel = f.getAnnotation(SfsManyToOne.class);
        SfsJoinColumn joinCol = f.getAnnotation(SfsJoinColumn.class);
        manyToOnes.add(new RelationMetadata(f, rel.fetch(), f.getType(), joinCol.name()));
    } else if (f.isAnnotationPresent(SfsOneToMany.class)) {
        // 신설 — MP-2 B1
        validateOneToMany(f);
        SfsOneToMany rel = f.getAnnotation(SfsOneToMany.class);
        Class<?> elementType = extractGenericType(f);
        oneToManies.add(new CollectionMetadata(f, elementType, rel.joinColumn()));
    } else if (f.isAnnotationPresent(SfsColumn.class)) {
        columns.add(new FieldMetadata(f, columnNameOf(f), f.getType()));
    }
}
```

신 헬퍼 메서드 2개 (`validateManyToOne` 옆에 배치):

```java
/**
 * @SfsOneToMany 필드 fail-fast 검증:
 * - List 외 타입(Set, Collection 등) → 예외 (MP-2는 List<T> only)
 * - raw List (generic 미명시) → 예외
 *
 * elementType 비엔티티 검증은 extractGenericType 내부에서 처리.
 */
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
}

/**
 * Hibernate 본가 패턴 정합 — ParameterizedType.getActualTypeArguments[0]로
 * generic erasure를 우회해 element 타입 자동 추출.
 *
 * @throws SfsEntityMappingException elementType이 @SfsEntity 미소유 시
 */
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

추가 import:
```java
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import java.lang.reflect.ParameterizedType;
import java.util.List;
```

- [x] **Step 4: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests EntityMetadataAnalyzerOneToManyTest`
Expected: 4건 모두 PASS.

- [x] **Step 5: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 304 + 4 = **308 PASS / 0 FAIL**.

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzer.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityMetadataAnalyzerOneToManyTest.java
git commit -m "feat(sfs-orm): EntityMetadataAnalyzer @SfsOneToMany 분기 + generic 추출 + fail-fast 3종 (B1)"
```

- [x] **Step 7: plan 체크박스 갱신**

---

## 섹션 C: SfsPersistentList<T> wrapper

### Task C1: `SfsPersistentList<T>` (lazy 발화 + closed 예외 + identity 보장)

> **실행 기록 (2026-05-22):** plan의 `FakeCollectionLoader`가 `List.of(...)`(불변)를 반환해 `add_write_메서드도_lazy_init_trigger` 테스트가 `UnsupportedOperationException`으로 실패. fake를 `new ArrayList<>(List.of("a","b","c"))`(가변)로 정정 — 테스트 의도(write 메서드도 init trigger)는 그대로, fake 구현 버그만 수정. 커밋 `914ced6`, 회귀 312 PASS.

**Files:**
- Create: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java`
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java`

- [x] **Step 1: 실패 테스트 작성 (4건: lazy 발화 / closed 예외 / write 메서드도 trigger / 캐시 hit)**

```java
package com.choisk.sfs.orm.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SfsPersistentListTest {

    private PersistenceContext context;
    private FakeCollectionLoader loader;
    private SfsPersistentList<String> list;

    @BeforeEach
    void setUp() {
        context = new PersistenceContext();
        loader = new FakeCollectionLoader();
        list = new SfsPersistentList<>(String.class, 1L, "user_id", loader, context);
    }

    @Test
    void 첫_메서드_호출_전에는_isInitialized_false() {
        assertThat(list.isInitialized()).isFalse();
        assertThat(loader.callCount.get()).isZero();
    }

    @Test
    void size_첫_호출_시_loader_1회_호출되고_캐시() {
        // when: size() 두 번 호출
        list.size();
        list.size();

        // then: loader는 1회만 호출 (캐시 hit)
        assertThat(loader.callCount.get()).isEqualTo(1);
        assertThat(list.isInitialized()).isTrue();
    }

    @Test
    void add_write_메서드도_lazy_init_trigger() {
        // when: add() 호출 (write 메서드)
        list.add("new");

        // then: loader 호출됨 (모든 메서드가 trigger — write-only optim 미도입)
        assertThat(loader.callCount.get()).isEqualTo(1);
        assertThat(list.isInitialized()).isTrue();
    }

    @Test
    void context_closed_후_호출_시_SfsLazyInitializationException() {
        context.close();

        assertThatThrownBy(() -> list.size())
                .isInstanceOf(com.choisk.sfs.orm.exception.SfsLazyInitializationException.class)
                .hasMessageContaining("String#1");
    }

    // ─── fake loader ──────────────────────────────────────────
    static class FakeCollectionLoader implements CollectionLoader {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                            Object fkValue, PersistenceContext ctx) {
            callCount.incrementAndGet();
            return (List<T>) List.of("a", "b", "c");
        }
    }
}
```

- [x] **Step 2: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 4건 모두 FAIL — `SfsPersistentList` 클래스 미존재.

- [x] **Step 3: `SfsPersistentList<T>` 구현**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.exception.SfsLazyInitializationException;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * 컬렉션 lazy 발화 wrapper — Hibernate {@code PersistentBag} 동형 패턴.
 *
 * <p>spec § 3.4 정합 — byte-buddy 미사용, 직접 List<T> 구현. 모든 메서드가 lazy init trigger
 * (write-only optimization 미도입 — 학습 정점 집중).
 *
 * <p>학습 정점 ①: {@code initialize()}가 첫 메서드 호출 시점에 정확히 1회 SELECT 발생시킴.
 * size()/iterator()/get(0)/contains(x)/add(e) 어느 것이든 동일 시점.
 *
 * <p>학습 정점 ②: element들이 모두 identityMap에 등재(loader 책임) → 같은 PK는 같은 인스턴스 보장.
 */
public class SfsPersistentList<T> implements List<T> {

    private final Class<T> elementType;
    private final Object ownerPk;
    private final String joinColumnName;
    private final CollectionLoader loader;
    private final PersistenceContext context;
    private List<T> delegate;     // null = uninitialized

    public SfsPersistentList(Class<T> elementType, Object ownerPk, String joinColumnName,
                              CollectionLoader loader, PersistenceContext context) {
        this.elementType = elementType;
        this.ownerPk = ownerPk;
        this.joinColumnName = joinColumnName;
        this.loader = loader;
        this.context = context;
    }

    /** lazy init 1회 — 모든 List 메서드의 첫 진입점. */
    private void initialize() {
        if (delegate != null) return;
        if (context.isClosed()) {
            throw new SfsLazyInitializationException(
                    elementType.getSimpleName() + "#" + ownerPk + ".collection");
        }
        delegate = loader.loadCollection(elementType, joinColumnName, ownerPk, context);
    }

    /** 테스트 헬퍼 — 초기화 여부 박제용. */
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

C1 시점에는 `CollectionLoader` 인터페이스가 D1에서 신설될 예정 — 본 task는 임시 stub interface로 컴파일 통과 후 D1에서 본격 구현. 단순 stub:

```java
// sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionLoader.java
package com.choisk.sfs.orm.support;

import java.util.List;

/** 컬렉션 lazy init 시 호출되는 loader (D1에서 DefaultCollectionLoader 신설). */
public interface CollectionLoader {
    <T> List<T> loadCollection(Class<T> elementType, String fkColumn,
                                Object fkValue, PersistenceContext ctx);
}
```

- [x] **Step 4: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests SfsPersistentListTest`
Expected: 4건 모두 PASS.

- [x] **Step 5: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 308 + 4 = **312 PASS / 0 FAIL**.

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/SfsPersistentList.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/support/CollectionLoader.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/SfsPersistentListTest.java
git commit -m "feat(sfs-orm): SfsPersistentList<T> wrapper + CollectionLoader 인터페이스 stub — 모든 메서드 lazy init trigger + closed 예외 (C1)"
```

- [x] **Step 7: plan 체크박스 갱신**

---

## 섹션 D: CollectionLoader + DefaultCollectionLoader + EntityPersister.findByForeignKey

### Task D1: `DefaultCollectionLoader` + `EntityPersister.findByForeignKey`

> **실행 기록 (2026-05-22):** 커밋 `4b9cbab`, 회귀 **313 PASS**(312 무손실 + 1). buildRowMapper rewrite(정정 ①)는 orchestrator가 직접 라인 리뷰로 behavior 보존 검증(EAGER 재귀/LAZY proxy 분기 그대로 + 끝에 putEntity 등록, cache-hit read가 EAGER 순환도 보호). **plan-wide 발견: `SfsEntityManagerFactory.close()`가 존재하지 않는다** (Phase 4 미도입, 통합 테스트는 `@BeforeEach` + nanoTime DB URL 격리로 정리). plan의 D1·E2·M1·M2 테스트가 가정한 `@AfterEach { emf.close(); }`는 **전부 삭제**하고 nanoTime URL 격리에 의존해야 함(새 lifecycle 메서드 도입은 Phase 4 범위 밖 — scope creep 회피). D1 테스트는 본 정정 적용 완료.

**Files:**
- Create: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/DefaultCollectionLoader.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java` (findByForeignKey 추가 + buildRowMapper cache-hit read·identityMap 등록 보강 — 정정 ①)
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManagerFactory.java` (collectionLoader 빈)
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/DefaultCollectionLoaderTest.java`

- [x] **Step 1: 실패 테스트 작성 (1건: SELECT WHERE fk = ? + identityMap 등재 검증)**

`DefaultCollectionLoader`는 *실제 DB와 연동*되므로 *통합성*이 강함. 단위 테스트는 H2 임베디드 + 미니 schema로 박제.

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsJoinColumn;
import com.choisk.sfs.orm.annotation.SfsManyToOne;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType.LAZY;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultCollectionLoaderTest {

    private DataSource dataSource;
    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dcl-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        TransactionSynchronizationManager tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource, tsm);

        // 미니 schema
        jdbc.update("CREATE TABLE parents (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        jdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "parent_id BIGINT, label VARCHAR(50))");
        jdbc.update("INSERT INTO parents (name) VALUES ('p1')");
        jdbc.update("INSERT INTO children (parent_id, label) VALUES (1, 'c1')");
        jdbc.update("INSERT INTO children (parent_id, label) VALUES (1, 'c2')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(dataSource)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(ParentEntity.class)
                .addEntityClass(ChildEntity.class)
                .build();
    }

    @AfterEach
    void tearDown() { emf.close(); }

    @Test
    void loadCollection_SELECT_WHERE_fk_실행_후_element들이_identityMap_등재() {
        CollectionLoader loader = new DefaultCollectionLoader(emf);
        PersistenceContext ctx = new PersistenceContext();

        List<ChildEntity> result = loader.loadCollection(ChildEntity.class, "parent_id", 1L, ctx);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(c -> c.label).containsExactlyInAnyOrder("c1", "c2");
        // identityMap 등재 검증 — 정점 ② 정합
        assertThat(ctx.contains(new EntityKey(ChildEntity.class, result.get(0).id))).isTrue();
        assertThat(ctx.contains(new EntityKey(ChildEntity.class, result.get(1).id))).isTrue();
    }

    // ─── fixture ──────────────────────────────────────────
    @SfsEntity(name = "parents")
    static class ParentEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
    }

    @SfsEntity(name = "children")
    static class ChildEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsManyToOne(fetch = LAZY)
        @SfsJoinColumn(name = "parent_id")
        ParentEntity parent;
        @SfsColumn String label;
    }
}
```

- [x] **Step 2: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests DefaultCollectionLoaderTest`
Expected: FAIL — `DefaultCollectionLoader` 클래스 미존재, `EntityPersister.findByForeignKey` 메서드 미존재.

- [x] **Step 3: `EntityPersister.findByForeignKey` 신설 + `buildRowMapper` identityMap 등록 보강 (정정 ①)**

먼저 `findByForeignKey` — 기존 `loadById` 옆에 추가:

```java
/**
 * 대상 테이블의 FK 컬럼이 일치하는 모든 행을 SELECT. OneToMany 컬렉션 lazy init이 호출.
 *
 * <p>spec § 3.5 정합 — SQL 캡슐화는 persister가 담당. loader는 위임만.
 * 결과 row들은 buildRowMapper를 거치며 identityMap에 등재되어 정점 ② 정합.
 *
 * @param fkColumn FK 컬럼명 (예: "user_id")
 * @param fkValue  FK 값 (부모 entity의 PK)
 * @param context  영속성 컨텍스트
 * @return 조회된 entity 리스트 (빈 리스트 가능)
 */
public List<Object> findByForeignKey(String fkColumn, Object fkValue, PersistenceContext context) {
    // selectAllSql에서 컬럼 리스트만 재활용 — Phase 4 SQL 캐싱 일관성
    // 형식: "SELECT id, name, ... FROM <table>"
    String columnsAndFrom = md.selectAllSql();  // 예: "SELECT id, name, email FROM users"
    String sql = columnsAndFrom + " WHERE " + fkColumn + " = ?";
    return jdbc.query(sql, buildRowMapper(context), fkValue);
}
```

**그다음 `buildRowMapper` rewrite (정정 ① — 등록 책임을 본가 위치로 이동).** 위 javadoc이 "buildRowMapper를 거치며 identityMap에 등재"라고 약속하지만, Phase 4의 `buildRowMapper`는 메인 엔티티를 *등록하지 않는다* (등록은 `RealEntityManager.find()`가 사후 수행, RealEntityManager.java:179). `findByForeignKey`/`findAll`은 EM 레이어를 우회하므로 이 약속을 직접 지켜야 함. 또한 cache-hit 시 같은 PK는 같은 인스턴스를 반환해야 정점 ② 충족.

기존 `int idx = 1` 후 id를 매핑하던 부분을 다음으로 교체 (id를 idx=1에서 미리 추출 → cache-hit read → 컬럼은 idx=2부터):

```java
private RowMapper<Object> buildRowMapper(PersistenceContext context) {
    return (rs, rowNum) -> {
        try {
            // id를 먼저 추출 — cache-hit read의 키
            Object pkValue = rs.getObject(1, toBoxedType(md.idField().javaType()));
            if (context != null) {
                // 정점 ②: 이미 로드된 PK면 같은 인스턴스 반환 (findAll/find 혼용 시에도 1 entity=1 instance)
                Object existing = context.getEntity(new EntityKey(md.entityClass(), pkValue));
                if (existing != null) return existing;
            }
            Object instance = md.entityClass().getDeclaredConstructor().newInstance();
            md.idField().field().set(instance, pkValue);

            int idx = 2;  // id는 위에서 처리 → 일반 컬럼은 2부터
            for (FieldMetadata col : md.columns()) {
                col.field().set(instance, rs.getObject(idx++, toBoxedType(col.javaType())));
            }
            for (RelationMetadata rel : md.manyToOnes()) {
                Object fk = rs.getObject(idx++);
                if (fk == null || context == null) continue;
                // ... 기존 LAZY/EAGER 분기 그대로 ...
            }
            // (E1에서 oneToManies(SfsPersistentList) 채우기 루프가 여기 삽입됨)

            // 정점 ② 등록 — findByForeignKey/findAll/loadById 모두 이 한 곳으로 일관 등록
            if (context != null) {
                context.putEntity(new EntityKey(md.entityClass(), pkValue), instance);
            }
            return instance;
        } catch (Exception e) {
            throw new SfsPersistenceException("Row mapping 실패: " + md.entityClass().getName(), e);
        }
    };
}
```

> **회귀 안전성:** `find()`(라인 179)·EAGER 분기(라인 254)는 이미 putEntity를 호출하므로 멱등 — 기존 304 PASS 유지. snapshot 부재 엔티티는 `flush()`가 graceful 처리(RealEntityManager.java:234~238)하므로 spurious UPDATE 없음. `context == null`(LazyProxyFactory fallback, SfsEntityManagerFactory.java:54)은 가드로 등록 생략 → 순환 회피.

- [x] **Step 4: `DefaultCollectionLoader` 구현**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;

import java.util.List;

/**
 * spec § 3.6 정합 — production CollectionLoader 구현.
 *
 * <p>책임 분담: SQL 실행은 {@link EntityPersister#findByForeignKey}가 캡슐화,
 * loader는 *대상 persister lookup + 위임*만. jdbc 의존 없음.
 * (Phase 1A gap의 책임 분담 패턴과 동형.)
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
        EntityPersister persister = emf.persisterOf(elementType);
        return (List<T>) persister.findByForeignKey(fkColumn, fkValue, ctx);
    }
}
```

- [x] **Step 5: `SfsEntityManagerFactory` Builder 확장 — `collectionLoader` 빈 + `jdbcTemplate(JdbcTemplate)` optional 옵션**

기존 빈 노출 영역에 추가:

```java
// SfsEntityManagerFactory 본체에 추가
private CollectionLoader collectionLoader;

// build() 안에서 후기 초기화 — persisterByClass 완성 후
this.collectionLoader = new DefaultCollectionLoader(this);

public CollectionLoader collectionLoader() { return collectionLoader; }
```

Builder에 *jdbcTemplate optional 옵션* 추가 — M1/M2 spy 주입용. default null이면 내부에서 `new JdbcTemplate(ds, tsm)` 자동 생성 (기존 동작 유지).

```java
// SfsEntityManagerFactory.Builder 안에 추가
private JdbcTemplate jdbcTemplate;     // optional, default null

public Builder jdbcTemplate(JdbcTemplate jt) {
    this.jdbcTemplate = jt;
    return this;
}
```

`build()` 내부에서:
```java
// 변경 전: JdbcTemplate jdbc = new JdbcTemplate(b.dataSource, b.tsm);
// 변경 후:
JdbcTemplate jdbc = (b.jdbcTemplate != null)
        ? b.jdbcTemplate
        : new JdbcTemplate(b.dataSource, b.tsm);
```

(EntityPersister가 emf 역참조하는 2단계 초기화 패턴과 동일 — Phase 4 `setEmf` 정합. spy 주입은 Phase 4 패턴의 *작은 자연 확장*으로 미니멀.)

- [x] **Step 6: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests DefaultCollectionLoaderTest`
Expected: 1건 PASS.

- [x] **Step 7: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 312 + 1 = **313 PASS / 0 FAIL**.

- [x] **Step 8: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/DefaultCollectionLoader.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManagerFactory.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/DefaultCollectionLoaderTest.java
git commit -m "feat(sfs-orm): DefaultCollectionLoader + EntityPersister.findByForeignKey + buildRowMapper identityMap 등록 보강 — 책임 분담 패턴 + 정점 ② (D1)"
```

- [x] **Step 9: plan 체크박스 갱신**

---

## 섹션 E: EntityPersister 확장 + findAll API

### Task E1: `EntityPersister.buildRowMapper` oneToManies(SfsPersistentList) 채우기

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java`

> **TDD 제외** — 통합 테스트(M1)가 회수. 단위 테스트는 fixture 복잡도가 큼.

> **정정 ①: cache-hit read·identityMap 등록·idx rewrite는 D1으로 이동됨.** E1은 *이미 D1에서 rewrite된* `buildRowMapper`에 oneToManies 채우기 루프만 삽입한다. (cache-hit `return existing` 직후 fresh instance에만 컬렉션 stub이 주입되도록, manyToOnes 루프 뒤 · putEntity 등록 앞에 위치.)

- [x] **Step 1: `buildRowMapper`에 oneToManies 채우기 분기 추가**

D1에서 rewrite된 `buildRowMapper`의 manyToOnes 루프 뒤, `context.putEntity(...)` 등록 *앞*에 삽입 (코드 주석 `// (E1에서 oneToManies ... 삽입됨)` 위치):

```java
// 신설: @SfsOneToMany 필드 — SfsPersistentList stub 주입 (DB 호출 0)
for (CollectionMetadata col : md.oneToManies()) {
    Object ownerPk = pkValue;  // D1 rewrite에서 idx=1로 미리 추출한 pk 재사용
    SfsPersistentList<?> proxy = new SfsPersistentList<>(
            col.elementType(), ownerPk, col.joinColumnName(),
            emf.collectionLoader(), context);
    col.field().set(instance, proxy);
}
```

(cache-hit으로 `existing`을 반환한 경로는 이 루프를 거치지 않으므로, 이미 초기화된 컬렉션을 stub으로 덮어쓰는 일이 없다 — 정점 ② 일관.)

- [x] **Step 3: 컴파일 + 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 313 PASS 유지 (분기 추가만, 회귀 영향 0). 만약 *findAll 통합 흐름*에서 cache hit 검증이 회귀로 잡힌다면 M2가 회수.

- [x] **Step 4: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java
git commit -m "feat(sfs-orm): EntityPersister.buildRowMapper oneToManies(SfsPersistentList) 채우기 (E1)"
```

- [x] **Step 5: plan 체크박스 갱신**

---

### Task E2: `EntityPersister.findAll` (SELECT *)

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java`
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityPersisterFindAllTest.java`

> **의존(정정 ①):** 본 테스트의 `ctx.contains(...)` 단언은 D1에서 보강한 `buildRowMapper`의 `context.putEntity` 등록에 의존한다. D1이 선행되어야 PASS.

- [x] **Step 1: 실패 테스트 작성 (1건: findAll 반환 + identityMap 등재)**

```java
package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

class EntityPersisterFindAllTest {

    private SfsEntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fap-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;
        TransactionSynchronizationManager tsm = new ThreadLocalTsm();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource, tsm);

        jdbc.update("CREATE TABLE simples (id BIGINT PRIMARY KEY AUTO_INCREMENT, label VARCHAR(50))");
        jdbc.update("INSERT INTO simples (label) VALUES ('a')");
        jdbc.update("INSERT INTO simples (label) VALUES ('b')");
        jdbc.update("INSERT INTO simples (label) VALUES ('c')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(dataSource)
                .transactionSynchronizationManager(tsm)
                .addEntityClass(SimpleEntity.class)
                .build();
    }

    @AfterEach
    void tearDown() { emf.close(); }

    @Test
    void findAll_SELECT_모든_행_반환_후_identityMap_등재() {
        EntityPersister persister = emf.persisterOf(SimpleEntity.class);
        PersistenceContext ctx = new PersistenceContext();

        List<Object> result = persister.findAll(ctx);

        assertThat(result).hasSize(3);
        // 정점 ② 정합 — 모든 result entity가 identityMap 등재
        for (Object e : result) {
            Long id = ((SimpleEntity) e).id;
            assertThat(ctx.contains(new EntityKey(SimpleEntity.class, id))).isTrue();
        }
    }

    @SfsEntity(name = "simples")
    static class SimpleEntity {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String label;
    }
}
```

- [x] **Step 2: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests EntityPersisterFindAllTest`
Expected: FAIL — `EntityPersister.findAll` 메서드 미존재.

- [x] **Step 3: `EntityPersister.findAll` 신설**

기존 `loadById` 또는 `findByForeignKey` 옆에:

```java
/**
 * SELECT * FROM <table> 실행 — 모든 entity 반환. spec § 3.5 정합.
 *
 * <p>각 row → buildRowMapper → context.putEntity (cache hit이면 재사용, 정점 ② 정합).
 *
 * @param context 영속성 컨텍스트
 * @return entity 리스트 (빈 리스트 가능)
 */
public List<Object> findAll(PersistenceContext context) {
    return jdbc.query(md.selectAllSql(), buildRowMapper(context));
}
```

- [x] **Step 4: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests EntityPersisterFindAllTest`
Expected: 1건 PASS.

- [x] **Step 5: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 313 + 1 = **314 PASS / 0 FAIL**.

- [x] **Step 6: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/support/EntityPersister.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/support/EntityPersisterFindAllTest.java
git commit -m "feat(sfs-orm): EntityPersister.findAll — SELECT * + identityMap 등재 (E2)"
```

- [x] **Step 7: plan 체크박스 갱신**

---

### Task E3: `SfsEntityManager.findAll` 시그니처 + `RealEntityManager.findAll` + `SfsTransactionalEntityManager.findAll` 위임

**Files:**
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java`
- Modify: `sfs-orm/src/main/java/com/choisk/sfs/orm/boot/SfsTransactionalEntityManager.java` (정정 ② — 위임 메서드 추가)

> **TDD 제외** — M2 통합 테스트가 회수.
> **정정 ②:** `SfsEntityManager` 구현체는 `RealEntityManager` + `SfsTransactionalEntityManager` 둘뿐이며, 후자는 *동적 프록시가 아니라 손수 위임 메서드를 작성한* 구현체다. 인터페이스에 `findAll`을 추가하면 후자가 미구현이 되어 **컴파일 에러** → 반드시 위임 메서드를 함께 추가해야 한다. (M1/M2가 `em.findAll`을 이 클래스 인스턴스에서 호출.)

- [x] **Step 1: `SfsEntityManager` 인터페이스에 시그니처 추가**

```java
public interface SfsEntityManager {
    void persist(Object entity);
    <T> T find(Class<T> entityClass, Object pk);
    <T> List<T> findAll(Class<T> entityClass);   // 신설
    void remove(Object entity);
    <T> T merge(T entity);
    void flush();
    PersistenceContext context();
}
```

- [x] **Step 2: `RealEntityManager.findAll` 구현**

```java
@Override
@SuppressWarnings("unchecked")
public <T> List<T> findAll(Class<T> entityClass) {
    EntityPersister persister = emf.persisterOf(entityClass);
    return (List<T>) persister.findAll(context);
}
```

- [x] **Step 2.5: `SfsTransactionalEntityManager.findAll` 위임 추가 (정정 ②)**

기존 `find` 위임 메서드(SfsTransactionalEntityManager.java:58~61) 옆에 추가:

```java
@Override
public <T> List<T> findAll(Class<T> entityClass) {
    return currentEm().findAll(entityClass);
}
```

추가 import: `import java.util.List;`. (이 메서드 없이는 `:sfs-orm:compileJava`가 *abstract method not implemented*로 실패.)

- [x] **Step 3: 컴파일 + 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 314 PASS 유지 (시그니처 추가만, 회귀 영향 0).

- [x] **Step 4: 커밋**

```bash
git add sfs-orm/src/main/java/com/choisk/sfs/orm/SfsEntityManager.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/RealEntityManager.java \
        sfs-orm/src/main/java/com/choisk/sfs/orm/boot/SfsTransactionalEntityManager.java
git commit -m "feat(sfs-orm): SfsEntityManager.findAll 시그니처 + RealEntityManager 구현 + SfsTransactionalEntityManager 위임 (E3)"
```

- [x] **Step 5: plan 체크박스 갱신**

---

## 섹션 F: sfs-samples 도메인 + demo

### Task F1: `User.orders` 도메인 확장

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/orm/domain/User.java`

> **TDD 제외** — 단순 POJO + 어노테이션 추가. demo가 회수.

- [x] **Step 1: `User` 도메인에 `orders` 필드 + getter 추가**

기존 User.java에 import + 필드 + getter 추가:

```java
package com.choisk.sfs.samples.orm.domain;

import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;   // 신설

import java.util.List;                                // 신설

@SfsEntity(name = "users")
public class User {

    @SfsId
    @SfsGeneratedValue(strategy = SfsGeneratedValue.GenerationType.SEQUENCE, sequenceName = "users_seq")
    private Long id;

    @SfsColumn
    private String name;

    @SfsColumn
    private String email;

    @SfsOneToMany(joinColumn = "user_id")            // 신설 (default LAZY)
    private List<Order> orders;                       // 신설

    public User() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public List<Order> getOrders() { return orders; }
    public void setOrders(List<Order> orders) { this.orders = orders; }
}
```

- [x] **Step 2: 컴파일 + 회귀 검증**

Run: `./gradlew :sfs-orm:test :sfs-samples:compileJava`
Expected: 314 PASS 유지. sfs-samples 컴파일 성공.

- [x] **Step 3: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/orm/domain/User.java
git commit -m "feat(sfs-samples): User.orders: List<Order> + @SfsOneToMany 도메인 확장 — MP-2 demo 토대 (F1)"
```

- [x] **Step 4: plan 체크박스 갱신**

---

## 섹션 M: 통합 테스트

### Task M1: `SqlCountingJdbcTemplate` + `OneToManyLazyIntegrationTest`

> **실행 기록 (2026-05-22):** 커밋 `50839de`, 회귀 **316 PASS**(314+2). 학습 정점 ① 실측: 첫 `size()`=1 SELECT, 둘째=0(캐시 hit); tx 종료 후 `SfsLazyInitializationException`("TestChild#1"). **발견(M2에도 적용):** `integration` 패키지 fixture 엔티티는 `public static class` + (직접 접근하는) 필드도 `public`이어야 한다 — `EntityPersister`(support 패키지)가 `getDeclaredConstructor().newInstance()`로 인스턴스화하는데 생성자에 setAccessible을 안 하므로, 다른 패키지의 package-private fixture는 `IllegalAccessException`. (support 패키지 단위 테스트는 같은 패키지라 package-private OK였음.) plan 원본의 `@AfterEach { emf.close(); }`는 emf.close() 부재로 제거.

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/SqlCountingJdbcTemplate.java`
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/OneToManyLazyIntegrationTest.java`

- [x] **Step 1: `SqlCountingJdbcTemplate` spy 신설**

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.jdbc.RowMapper;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 실행 횟수를 카운트하는 spy JdbcTemplate — *테스트 전용 서브클래스*.
 * production JdbcTemplate은 변경 0.
 *
 * <p>spec § 7.3 정합. MP-2 N+1 박제 + lazy 발화 시점 검증에 사용.
 */
public class SqlCountingJdbcTemplate extends JdbcTemplate {

    public final List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());

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

    public int countMatching(String pattern) {
        return (int) executedSqls.stream().filter(s -> s.contains(pattern)).count();
    }

    public void reset() { executedSqls.clear(); }
}
```

- [x] **Step 2: 실패 테스트 작성 (2건: lazy 발화 시점 + closed 예외)**

> **Phase 4 통합 패턴 정합**: `AbstractOrmIntegrationTest` 미상속(Phase 4 코드 건드림 회피) + 자체 setup. `TransactionTemplate.execute(tm, () -> {})` 패턴 + `SfsTransactionalEntityManager em` + 자체 fixture (sfs-orm 내부 정의 — sfs-samples 도메인 사용 불가, 모듈 의존 역방향).

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneToManyLazyIntegrationTest {

    private JdbcDataSource ds;
    private SqlCountingJdbcTemplate spyJdbc;
    private TransactionSynchronizationManager tsm;
    private DataSourceTransactionManager tm;
    private SfsEntityManagerFactory emf;
    private SfsTransactionalEntityManager em;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:o2mlazy-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);
        spyJdbc = new SqlCountingJdbcTemplate(ds, tsm);

        // schema 준비 (spy로 직접 — DDL은 트랜잭션 외 autocommit)
        spyJdbc.update("CREATE TABLE owners (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        spyJdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "owner_id BIGINT, label VARCHAR(50))");
        // 사전 데이터: Owner 1명 + Children 2개
        spyJdbc.update("INSERT INTO owners (name) VALUES ('alice')");
        spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (1, 'c1')");
        spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (1, 'c2')");

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                .jdbcTemplate(spyJdbc)               // spy 주입 — D1 step 5 도입 옵션 활용
                .addEntityClass(TestOwner.class)
                .addEntityClass(TestChild.class)
                .build();
        em = new SfsTransactionalEntityManager(new SfsEntityManagerFactoryBean(emf, tsm), tsm);
    }

    @AfterEach
    void tearDown() { emf.close(); }

    @Test
    void getChildren_size_첫_호출_시_정확히_1_SELECT_발생() {
        TransactionTemplate.execute(tm, () -> {
            TestOwner owner = em.find(TestOwner.class, 1L);
            spyJdbc.reset();   // 사전 setup + find SELECT 제외

            int count = owner.children.size();
            assertThat(count).isEqualTo(2);
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(1);

            // 두 번째 호출은 캐시 hit — 추가 SELECT 0
            owner.children.size();
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(1);
            return null;
        });
    }

    @Test
    void TSM_경계_종료_후_getChildren_size_호출_시_SfsLazyInitializationException() {
        // tx 안에서 owner 로드 — SfsPersistentList stub 주입
        TestOwner owner = TransactionTemplate.execute(tm, () -> em.find(TestOwner.class, 1L));
        // tx 경계 종료 → afterCompletion 콜백으로 PC close

        assertThatThrownBy(() -> owner.children.size())
                .isInstanceOf(SfsLazyInitializationException.class)
                .hasMessageContaining("TestChild#1");
    }

    // ─── 자체 fixture (sfs-orm 내부 — sfs-samples 의존 회피) ───────────
    @SfsEntity(name = "owners")
    static class TestOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "owner_id")
        List<TestChild> children;
    }

    @SfsEntity(name = "children")
    static class TestChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String label;
    }
}
```

> **자율 판단 영역** (spec § 0.5 정합):
> - ~~`TransactionTemplate.execute` 시그니처 확인 필요~~ **확인 완료(정정 ④, 2026-05-22):** `public static <T> T execute(PlatformTransactionManager tm, Supplier<T> action)` (TransactionTemplate.java:43). plan의 `() -> {...; return null;}` 및 `() -> em.find(...)` 반환값 capture 패턴과 정합 — 재확인 불필요. (`DataSourceTransactionManager extends AbstractPlatformTransactionManager`이므로 `tm` 인자 타입도 정합.)
> - spy 주입 *Builder.jdbcTemplate(spy)*은 D1 step 5에서 도입한 옵션 활용.

- [x] **Step 3: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests OneToManyLazyIntegrationTest`
Expected: FAIL — `EntityPersister.buildRowMapper`가 `oneToManies` 채우기 미수행(E1 미통과)이거나, D1 step 5의 `Builder.jdbcTemplate` 옵션 미도입 시 컴파일 실패.

- [x] **Step 4: 통합 흐름 보강 — A1~E3 모든 task 통과 + Builder.jdbcTemplate 옵션 검증**

본 통합 테스트는 A1~E3 누적 결과의 회귀망. 각 task 통과 후 본 단계 진입.

- [x] **Step 5: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests OneToManyLazyIntegrationTest`
Expected: 2건 모두 PASS.

- [x] **Step 6: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 314 + 2 = **316 PASS / 0 FAIL**.

- [x] **Step 7: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/SqlCountingJdbcTemplate.java \
        sfs-orm/src/test/java/com/choisk/sfs/orm/integration/OneToManyLazyIntegrationTest.java
git commit -m "test(sfs-orm): OneToManyLazyIntegrationTest 2건 + SqlCountingJdbcTemplate spy 인프라 — lazy 발화 시점 + closed 예외 박제 (M1)"
```

- [x] **Step 8: plan 체크박스 갱신**

---

### Task M2: `NPlusOneIntegrationTest` (findAll + spy SQL count)

**Files:**
- Create: `sfs-orm/src/test/java/com/choisk/sfs/orm/integration/NPlusOneIntegrationTest.java`

- [ ] **Step 1: 실패 테스트 작성 (2건: N+1 정확 카운트 + 재실행 캐시 hit)**

> M1과 동일 패턴 (`TransactionTemplate.execute(tm, () -> {})` + `SfsTransactionalEntityManager em` + 자체 fixture).

```java
package com.choisk.sfs.orm.integration;

import com.choisk.sfs.orm.SfsEntityManagerFactory;
import com.choisk.sfs.orm.annotation.SfsColumn;
import com.choisk.sfs.orm.annotation.SfsEntity;
import com.choisk.sfs.orm.annotation.SfsGeneratedValue;
import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.annotation.SfsOneToMany;
import com.choisk.sfs.orm.boot.SfsEntityManagerFactoryBean;
import com.choisk.sfs.orm.boot.SfsTransactionalEntityManager;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import com.choisk.sfs.tx.support.TransactionTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.choisk.sfs.orm.annotation.SfsGeneratedValue.GenerationType.IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

class NPlusOneIntegrationTest {

    private JdbcDataSource ds;
    private SqlCountingJdbcTemplate spyJdbc;
    private TransactionSynchronizationManager tsm;
    private DataSourceTransactionManager tm;
    private SfsEntityManagerFactory emf;
    private SfsTransactionalEntityManager em;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:nplus1-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        tsm = new ThreadLocalTsm();
        tm = new DataSourceTransactionManager(ds, tsm);
        spyJdbc = new SqlCountingJdbcTemplate(ds, tsm);

        spyJdbc.update("CREATE TABLE owners (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
        spyJdbc.update("CREATE TABLE children (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "owner_id BIGINT, label VARCHAR(50))");

        // 사전 데이터: Owner 3명 + 각 1 Child
        for (int i = 1; i <= 3; i++) {
            spyJdbc.update("INSERT INTO owners (name) VALUES (?)", "owner" + i);
            spyJdbc.update("INSERT INTO children (owner_id, label) VALUES (?, ?)", i, "c" + i);
        }

        emf = SfsEntityManagerFactory.builder()
                .dataSource(ds)
                .transactionSynchronizationManager(tsm)
                .jdbcTemplate(spyJdbc)
                .addEntityClass(TestOwner.class)
                .addEntityClass(TestChild.class)
                .build();
        em = new SfsTransactionalEntityManager(new SfsEntityManagerFactoryBean(emf, tsm), tsm);
    }

    @AfterEach
    void tearDown() { emf.close(); }

    @Test
    void findAll_owner_3명_for_loop_children_size_시_정확히_N_plus_1_SELECT() {
        TransactionTemplate.execute(tm, () -> {
            spyJdbc.reset();  // 사전 setup INSERT 제외

            List<TestOwner> owners = em.findAll(TestOwner.class);
            for (TestOwner o : owners) {
                o.children.size();
            }

            // 정점 ② N+1: findAll 1 SELECT + 3 owners 각각 1 SELECT = 총 4 SELECT
            assertThat(owners).hasSize(3);
            assertThat(spyJdbc.countMatching("SELECT")).isEqualTo(4);
            return null;
        });
    }

    @Test
    void for_loop_재실행_시_추가_SELECT_0_캐시_hit() {
        TransactionTemplate.execute(tm, () -> {
            List<TestOwner> owners = em.findAll(TestOwner.class);
            for (TestOwner o : owners) o.children.size();  // 첫 N+1
            spyJdbc.reset();

            // 재실행 — SfsPersistentList.delegate 캐시 hit으로 추가 SELECT 0
            for (TestOwner o : owners) o.children.size();

            assertThat(spyJdbc.countMatching("SELECT")).isZero();
            return null;
        });
    }

    // ─── 자체 fixture ──────────────────────────────────────────
    @SfsEntity(name = "owners")
    static class TestOwner {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String name;
        @SfsOneToMany(joinColumn = "owner_id")
        List<TestChild> children;
    }

    @SfsEntity(name = "children")
    static class TestChild {
        @SfsId @SfsGeneratedValue(strategy = IDENTITY)
        Long id;
        @SfsColumn String label;
    }
}
```

- [ ] **Step 2: FAIL 확인**

Run: `./gradlew :sfs-orm:test --tests NPlusOneIntegrationTest`
Expected: FAIL — `em.findAll` 미구현 또는 spy 주입 미완성 (M1에서 박제된 patterns 사용).

- [ ] **Step 3: 구현은 E2 + E3에서 완성 — 본 task는 통합 시나리오 PASS만 검증**

만약 FAIL 원인이 *spy 주입 패턴*이면 M1과 같은 패턴 재사용. *findAll API*는 E2+E3에서 완성됨.

- [ ] **Step 4: PASS 확인**

Run: `./gradlew :sfs-orm:test --tests NPlusOneIntegrationTest`
Expected: 2건 모두 PASS.

- [ ] **Step 5: 회귀 검증**

Run: `./gradlew :sfs-orm:test`
Expected: 316 + 2 = **318 PASS / 0 FAIL**. spec § 7.2 추정 +14 정확히 정합.

- [ ] **Step 6: 커밋**

```bash
git add sfs-orm/src/test/java/com/choisk/sfs/orm/integration/NPlusOneIntegrationTest.java
git commit -m "test(sfs-orm): NPlusOneIntegrationTest 2건 — findAll + for-loop = N+1 정확 카운트 + 재실행 캐시 hit (M2)"
```

- [ ] **Step 7: plan 체크박스 갱신**

---

### Task F2: `OrmDemoApplication` 시연 3건 (DH/DI/DJ) + UserService 확장

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/UserService.java`
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/orm/OrmDemoApplication.java`

> **TDD 제외** — demo는 manual run 검증. M1+M2가 회귀망.

- [ ] **Step 1: `UserService`에 시나리오 3 메서드 추가**

```java
package com.choisk.sfs.samples.orm.service;

import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.samples.orm.domain.Order;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.tx.annotation.SfsTransactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class UserService {

    private final SfsEntityManager em;

    public UserService(SfsEntityManager em) { this.em = em; }

    // 기존 메서드 (createUser 등) — 그대로

    /**
     * DH: findAll(User) + for-loop u.getOrders().size() → N+1 자연 노출.
     * 사전 setup: User 3명 + 각 1~2 Order INSERT/flush 후 호출 권장.
     */
    @SfsTransactional
    public void dumpAllUserOrders() {
        List<User> users = em.findAll(User.class);
        System.out.println("[DH] findAll(User) → " + users.size() + "명 조회 (1 SELECT)");
        for (User u : users) {
            int orderCount = u.getOrders().size();    // each lazy init = 1 SELECT
            System.out.println("  user=" + u.getName() + ", orders=" + orderCount);
        }
        System.out.println("[DH] 총 N+1 = " + (users.size() + 1) + " SELECT 발생 (spy로 회귀 검증됨)");
    }

    /**
     * DI: user.getOrders().iterator() 첫 호출 시점에 정확히 1 SELECT 발생.
     */
    @SfsTransactional
    public void describeUserOrders(Long userId) {
        User user = em.find(User.class, userId);
        System.out.println("[DI] user.getOrders().iterator().next() 호출 직전 (SELECT 미발생)");
        if (!user.getOrders().isEmpty()) {
            Order first = user.getOrders().iterator().next();  // 정확히 1 SELECT
            System.out.println("[DI] 첫 호출 시점에 1 SELECT 발생 — firstOrderId=" + first.getId());
        }
    }

    /**
     * DJ: user.getOrders().add(newOrder) + persist(user)만 호출 → newOrder INSERT 안 됨.
     * cascade 부재 자연 노출 — MP-3에서 cascade=PERSIST로 회수 예정.
     */
    @SfsTransactional
    public void tryAddOrderWithoutCascade(Long userId) {
        User user = em.find(User.class, userId);
        Order newOrder = new Order();
        newOrder.setAmount(new BigDecimal("999.99"));
        newOrder.setStatus("ATTEMPT");
        newOrder.setCreatedAt(LocalDateTime.now());
        newOrder.setUser(user);

        user.getOrders().add(newOrder);  // lazy init + add (단방향이라 DB 영향 없음)
        em.persist(user);                 // user는 이미 managed, no-op

        em.flush();
        System.out.println("[DJ] add() + persist(user)만 호출 → newOrder는 INSERT 안 됨 (단방향 + cascade 미도입)");
        System.out.println("[DJ] 해결: em.persist(newOrder) 별도 호출 필요 — MP-3에서 cascade=PERSIST로 자동화");
    }
}
```

- [ ] **Step 2: `OrmDemoApplication` main에 시나리오 3건 추가**

```java
// 기존 OrmDemoApplication.java의 main 끝부분에 추가 — DA~DG 그대로 보존

// ── MP-2 시연 (DH/DI/DJ) ──────────────────────────────────────────
System.out.println("\n=== MP-2: @SfsOneToMany 시연 ===\n");

// 사전 데이터: User 3명 + 각 1~2 Order INSERT/flush
userService.createUser("alice", "a@x.com");
userService.createUser("bob", "b@x.com");
userService.createUser("carol", "c@x.com");
// (각 user에 order 1~2개 INSERT — orderService.placeOrder 활용)

// DH: N+1 자연 노출
userService.dumpAllUserOrders();

// DI: lazy 발화 시점
userService.describeUserOrders(1L);

// DJ: cascade 미도입 자연 노출
userService.tryAddOrderWithoutCascade(1L);
```

- [ ] **Step 3: manual run 검증**

Run: `./gradlew :sfs-samples:ormDemo`
Expected: 정상 시작 + DA~DG 시연 + 신설 DH/DI/DJ console 출력 확인:
- DH: "총 N+1 = 4 SELECT 발생" 메시지
- DI: "첫 호출 시점에 1 SELECT 발생" 메시지
- DJ: "newOrder는 INSERT 안 됨" 메시지

- [ ] **Step 4: 컴파일 + 회귀 검증**

Run: `./gradlew build`
Expected: 318 PASS 유지.

- [ ] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/orm/service/UserService.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/orm/OrmDemoApplication.java
git commit -m "feat(sfs-samples): OrmDemoApplication MP-2 시연 3건(DH/DI/DJ) + UserService 확장 — collection lazy + N+1 + cascade 부재 박제 (F2)"
```

- [ ] **Step 6: plan 체크박스 갱신**

---

## 섹션 G: 마감

### Task G1: 전체 빌드 + 회귀 +14 검증 + DoD 체크박스 갱신

**Files:**
- Modify: `docs/superpowers/plans/2026-05-20-mp2-one-to-many.md` (모든 Task 체크박스 [x])
- Modify: `docs/superpowers/specs/2026-05-19-mp2-one-to-many-design.md` (§ 8 DoD 14항목 [x] + 회귀 실측 박제)

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL. 318 PASS / 0 FAIL / 0 errors.

- [ ] **Step 2: DoD 14 항목 체크박스 갱신**

spec § 8 표에 *상태* 컬럼 추가 후 14/14 [x] 갱신. Phase 4 spec § 8 패턴 정합.

- [ ] **Step 3: 회귀 카운트 정정** (실측 vs 추정)

추정 +14 vs 실측 결과 정합 여부 plan § 12 회귀 표에 박제. ±2 자연 변동 허용. 초과/미달 시 plan에 *실행 기록 (YYYY-MM-DD)* 블록으로 사유 박제.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/plans/2026-05-20-mp2-one-to-many.md \
        docs/superpowers/specs/2026-05-19-mp2-one-to-many-design.md
git commit -m "docs: MP-2 DoD 14/14 [x] 갱신 + 회귀 318 PASS 박제 (G1)"
```

- [ ] **Step 5: plan 체크박스 갱신**

---

### Task G2: 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify)

CLAUDE.md *"완료 후 품질 게이트"* 정합. Phase 4 패턴 그대로 (3 reviewer + 3 simplify).

- [ ] **Step 1: 다관점 코드리뷰** — 아래 5관점 각각 독립적으로:
  - 아키텍처·설계 일관성 (spec § 2 모듈 의존 / spec § 3 컴포넌트 boundary 정합)
  - 가독성·네이밍·주석 WHY (CLAUDE.md 주석 규칙)
  - 테스트 커버리지 (TDD 적용/제외 판단 정합)
  - 동시성·라이프사이클·실패 복구 (SfsPersistentList close/lazy init 경로)
  - 보안·리소스 누수 (Connection 누수, spy SQL 카운트 race)

  실행: `feature-dev:code-reviewer` 에이전트 (자동 스캔) + `superpowers:requesting-code-review` (외부 관점). 결과 *"남겨둘 / 즉시 고칠"* 분류.

- [ ] **Step 2: 리팩토링** — 즉시 고칠 이슈만 반영. 동작 변경 없음. 커밋 `refactor(sfs-orm): ...`

- [ ] **Step 3: `/simplify` 패스** — 재사용 패턴 추출, 중복 제거, 데드 코드 정리. diff 단위 검토 후 가치 있는 것만 반영. 커밋 `refactor(sfs-orm): ...` 또는 `chore: ...`

- [ ] **Step 4: 게이트 통과 기준**:
  - `./gradlew build` 전체 PASS
  - 회귀 카운트가 plan DoD 예상치와 일치 (~318 ±2)
  - 본 plan + spec DoD 14항목 모두 `[x]`
  - 본 plan 하단에 `> **품질 게이트 기록 (YYYY-MM-DD):**` 블록 박제 (지적사항 N건 / 반영 M건 / 보류 K건)

- [ ] **Step 5: 메모리 박제 + main 머지 준비**

```bash
git commit -m "docs: Plan § 11 품질 게이트 기록 박제 + DoD 14 [x] — MP-2 마감"
```

`MEMORY.md` + `project_mp2_resume_point.md` 갱신:
- MP-2 마감 — 회귀 304 → ~318 PASS, DoD 14/14, 마감 게이트 통과
- 학습 정점 박제: ①(collection lazy 발화) + ②(N+1) + 학습 짝패(cascade 부재 자연 노출 → MP-3 회수)
- 다음 mini-phase 후보 (MP-3 / MP-4 / MP-2-α / 기타) 활성

main 머지는 *사용자 직접 진행* (Phase 4 동일 패턴).

- [ ] **Step 6: plan 체크박스 갱신** (모두 [x])

---

## 11. Plan ↔ Spec ↔ DoD 매핑 점검

| spec § | plan task | DoD |
|---|---|---|
| § 2.1~2.2 (모듈/패키지) | A1, A2, A3 | 1, 2, 3 |
| § 3.1 (@SfsOneToMany) | A1 | 1 |
| § 3.2 (CollectionMetadata) | A2 | 2 |
| § 3.3 (Analyzer 분기) | B1 | 4, 10 |
| § 3.4 (SfsPersistentList) | C1 | 5, 9 |
| § 3.5 (EntityPersister 확장) | E1, E2 | 7, 8 |
| § 3.6 (CollectionLoader) | D1 | 6, 8 |
| § 4.1 (find collection stub 주입) | E1 | 7 |
| § 4.2 (lazy init flow) | C1, M1 | 5, 12 |
| § 4.3 (N+1 시나리오) | E2, E3, M2 | 8, 12 |
| § 5 (demo) | F1, F2 | 11 |
| § 6 (예외 재사용 / fail-fast 3종) | C1, B1, M1 | 9, 10 |
| § 7 (테스트 + spy) | M1, M2 | 12 |
| § 8 (DoD) | G1 | 13 (마감) |
| § 9 (이월 박제) | G2에서 갱신 | 14 |

---

## 12. 회귀 카운트 진행 표

| Task 완료 | 추정 누적 | 실측 누적 | 비고 |
|---|---|---|---|
| 시작 (Phase 4 마감) | 304 | — | main 0336875 |
| A1 (TDD 제외) | 304 | — | 어노테이션 — 회귀 영향 0 |
| A2 (TDD 제외) | 304 | — | record DTO |
| A3 (TDD 제외) | 304 | — | EntityMetadata 필드 추가 — 호출지 1곳 정정 |
| B1 (+4) | 308 | — | EntityMetadataAnalyzer @SfsOneToMany 분기 + fail-fast 3종 |
| C1 (+4) | 312 | — | SfsPersistentList — lazy 발화 + closed 예외 + write trigger + 캐시 hit |
| D1 (+1) | 313 | — | DefaultCollectionLoader + findByForeignKey + buildRowMapper cache-read/identityMap 등록(정정 ①) |
| E1 (구현만) | 313 | — | buildRowMapper oneToManies(SfsPersistentList) 채우기 — M1 회수 (cache-hit·등록은 D1로 이동) |
| E2 (+1) | 314 | — | findAll SELECT * |
| E3 (구현만) | 314 | — | RealEntityManager.findAll — M2 회수 |
| F1 (TDD 제외) | 314 | — | User.orders 도메인 |
| M1 (+2) | 316 | — | OneToManyLazyIntegrationTest + SqlCountingJdbcTemplate |
| M2 (+2) | 318 | — | NPlusOneIntegrationTest — 정점 ② 회귀 |
| F2 (TDD 제외) | 318 | — | demo 시연 3건 + manual run |
| **G1 마감** | **318** | — | 빌드 + DoD + 회귀 정정 박제 |
| G2 (마감 게이트) | 318 | — (대기) | 다관점 리뷰 + 리팩토링 + simplify |

목표: **318 PASS** (추정 +14 = 304 + 14). ±2 자연 변동 허용. G1에서 실측 박제.

---

## 13. 학습 부채 표 (spec § 9.1 정합)

본 mini-phase가 *의도적으로 만든 학습 부채*:

| 부채 | MP-2 시연 | 회수 위치 |
|---|---|---|
| cascade 부재 | DJ: `add() + persist()만` → orphan INSERT 누락 | MP-3 cascade=PERSIST |
| N+1 폭발 | DH: `findAll + for-loop` → N+1 SELECT | MP-4 fetch JOIN |
| EAGER collection 부재 | (시연 없음, 박제만) | MP-2-α |
| 양방향 일관성 | (시연 없음, MP-3 주제) | MP-3 |
| collection의 dirty 처리 | (시연 없음, 단방향이라 DB 영향 없음이 의미 정합) | MP-3 (양방향에서 부모 dirty 처리) |

> **mini-phase는 *학습 부채를 의도적으로 만들고 다음 phase가 회수***. G2 마감 시 spec § 9 이월 박제와 동기화 검증.

---

## 14. 실행 가이드 요약 (next-session prompt 예시)

본 plan은 별도 *구현 세션*에서 task-by-task 실행:

```
MP-2 @SfsOneToMany 구현 세션 진입. 
plan docs/superpowers/plans/2026-05-20-mp2-one-to-many.md / 
spec docs/superpowers/specs/2026-05-19-mp2-one-to-many-design.md / 
브랜치 feat/mp2-one-to-many. 
superpowers:subagent-driven-development (추천) 또는 
superpowers:executing-plans 스킬로 Task A1부터 task-by-task 실행. 
회귀 304 → 318 목표 (±2 변동 허용). 
구현 완료 후 G2 마감 게이트 + main 머지 (사용자 직접).
```
