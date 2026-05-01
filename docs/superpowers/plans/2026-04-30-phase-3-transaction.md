# Phase 3 트랜잭션 추상화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (recommended) 또는 `superpowers:executing-plans`로 task-by-task 실행. Step은 체크박스(`- [ ]`) 형식.

**Goal:** `PlatformTransactionManager` 인터페이스 + `@Transactional` advice + 두 TSM 구현체(ThreadLocal/ScopedValue) + JDBC 통합으로 트랜잭션 추상화의 본질을 박제하고, REQUIRED/REQUIRES_NEW propagation 시연을 H2 임베디드 DB 위에서 검증한다.

**Architecture:** sfs-tx 모듈 신설 (sfs-aop/sfs-beans/sfs-core 의존). Phase 2B BPP 패턴 회수 (`AspectEnhancingBeanPostProcessor` → `TransactionalBeanPostProcessor`, `AdviceInterceptor` → `TransactionInterceptor`). Phase 1A 3-level cache 자산 회수 (SIABPP `getEarlyBeanReference` 훅 첫 의미 있는 사용). Spring 본가 `spring-tx` 시그니처 그대로 — 라인 단위 비교 학습.

**Tech Stack:** Java 25 LTS · JUnit 5 + AssertJ · byte-buddy 1.14 · ASM 9.9 · H2 2.2.x (신규 외부 의존)

**선행 의존:** Phase 1 (IoC), Phase 2A/2B (AOP) — 모두 완료 (회귀 185 PASS / 0 FAIL)

**연관 spec:** `docs/superpowers/specs/2026-04-30-phase-3-transaction-design.md`

---

## 0. 실행 가이드

각 Task는 다음 사이클을 따른다 (CLAUDE.md "플랜 실행 루틴"):

1. 실패 테스트 작성 *(TDD 적용 대상에 한함, spec § 6.1 분류)*
2. `./gradlew :<module>:test --tests <TestName>` 로 FAIL 확인
3. 최소 구현
4. PASS 확인 *(TDD 제외 대상은 1~2 생략, 3 이후 컴파일 + 회귀 테스트만)*
5. 한국어 커밋 메시지로 커밋
6. Plan 문서의 해당 Step 체크박스 `- [ ]` → `- [x]` 업데이트
7. 실행 중 편차 발생 시 `> **실행 기록 (YYYY-MM-DD):**` 블록으로 박제

**TDD 제외 대상** (spec § 6.1):
- 추상 골격: `AbstractPlatformTransactionManager` (Task A3)
- 데이터 컨테이너: `TransactionDefinition` record, `Order`/`AuditLog` record (Task A2, C4)
- 시그니처만: `@Transactional`, `Propagation` enum, `PlatformTransactionManager`/`TransactionStatus` 인터페이스 (Task A2)
- JDBC 얇은 래퍼: `OrderRepository`/`AuditRepository` (Task C4)
- 설정 파일: `build.gradle.kts`, `settings.gradle.kts` (Task A1)

---

## 1. 섹션 구조 (Task 한 줄 요약)

| Task | 내용 | 주 산출물 | 회귀 추정 |
|---|---|---|---|
| **A1** | sfs-tx 모듈 신설 + Gradle | `settings.gradle.kts`, `sfs-tx/build.gradle.kts` | +0 |
| **A2** | 애노테이션/enum/record + 인터페이스 시그니처 | `@Transactional`, `Propagation`, `TransactionDefinition`, `PlatformTransactionManager`, `TransactionStatus` | +0 (TDD 제외) |
| **A3** | `AbstractPlatformTransactionManager` 추상 골격 — propagation 분기 알고리즘 | `AbstractPlatformTransactionManager` | +0 (TDD 제외, 통합 테스트로 간접 검증) |
| **A4** | `TransactionSynchronizationManager` 인터페이스 + `ThreadLocalTsm` + 단위 테스트 | TSM 인터페이스, `ThreadLocalTsm`, `ThreadLocalTsmTest` | +4 |
| **B1** | `MockTransactionManager` + 단위 테스트 — 콘솔 출력만으로 추상화 본질 박제 | `MockTransactionManager`, `MockTransactionManagerTest` | +4 |
| **B2** | `TransactionInterceptor` (advice 본체) + 단위 테스트 (B-2 흡수) | `TransactionInterceptor`, `TransactionInterceptorTest` | +6 |
| **B3** | `TransactionalBeanPostProcessor` + 단위 테스트 (A-1/A-3 흡수) | `TransactionalBeanPostProcessor`, `TransactionalBeanPostProcessorTest` | +5 |
| **B4** | 첫 살아있는 시연 — Mock TM + AppConfig 등록 + Mock 통합 테스트 (vertical slice 1) | `MockTransactionIntegrationTest` 추가 | +3 |
| **C1** | H2 의존 추가 + DataSource 빈 + schema.sql | `libs.versions.toml`, `sfs-tx/build.gradle.kts` 갱신, `schema.sql` | +0 |
| **C2** | `DataSourceTransactionManager` + 단위 테스트 | `DataSourceTransactionManager`, 테스트 | +5 |
| **C3** | `JdbcTemplate` mini + 단위 테스트 | `JdbcTemplate`, `RowMapper`, `JdbcTemplateTest` | +4 |
| **C4** | Order/AuditLog 도메인 + JDBC Repository 2종 | record 2 + Repository 2 | +0 (TDD 제외) |
| **C5** | OrderService/AuditService/Controller — propagation 시연 도메인 | service 2 + controller 1 | +0 |
| **C6** | `TransactionPropagationIntegrationTest` 6건 + `TransactionDemoApplicationTest` 2건 — 시연 박제 | 통합 테스트 2 | +8 |
| **D1** | `ScopedValueTsm` + 단위 테스트 + `TsmComparisonTest` 비교 박제 | `ScopedValueTsm`, 테스트 2 | +6 |
| **D2** | `EarlyReferenceIntegrationTest` 2건 (A-2 흡수) + `EnhancedBeanDestroyTest` 1건 (C-3 흡수) | 통합 테스트 2 | +3 |
| **E1** | README 신설/갱신 + 빌드 + 회귀 + DoD 갱신 | `sfs-tx/README.md`, `sfs-samples/README.md` | +0 |
| **E2** | 마감 게이트 (다관점 리뷰 + 리팩토링 + simplify) | (커밋만) | +0 |

**합계 추정: +48 PASS** (185 → ~233). 17 task.

---

## 2. File Structure (생성/수정 파일 매핑)

### 신설 파일

```
sfs-tx/
├── build.gradle.kts                                                 [Task A1]
├── README.md                                                        [Task E1]
└── src/
    ├── main/java/com/choisk/sfs/tx/
    │   ├── annotation/
    │   │   ├── Transactional.java                                   [Task A2]
    │   │   └── Propagation.java                                     [Task A2]
    │   ├── PlatformTransactionManager.java                          [Task A2]
    │   ├── TransactionStatus.java                                   [Task A2]
    │   ├── TransactionDefinition.java                               [Task A2]
    │   ├── TransactionException.java                                [Task A2]
    │   ├── support/
    │   │   ├── DefaultTransactionStatus.java                        [Task A3]
    │   │   ├── AbstractPlatformTransactionManager.java              [Task A3]
    │   │   ├── MockTransactionManager.java                          [Task B1]
    │   │   ├── DataSourceTransactionManager.java                    [Task C2]
    │   │   ├── ConnectionHolder.java                                [Task C2]
    │   │   ├── TransactionSynchronizationManager.java               [Task A4]
    │   │   ├── ThreadLocalTsm.java                                  [Task A4]
    │   │   ├── ScopedValueTsm.java                                  [Task D1]
    │   │   └── TransactionInterceptor.java                          [Task B2]
    │   ├── jdbc/
    │   │   ├── JdbcTemplate.java                                    [Task C3]
    │   │   └── RowMapper.java                                       [Task C3]
    │   └── boot/
    │       └── TransactionalBeanPostProcessor.java                  [Task B3]
    └── test/java/com/choisk/sfs/tx/
        ├── support/
        │   ├── ThreadLocalTsmTest.java                              [Task A4]
        │   ├── MockTransactionManagerTest.java                      [Task B1]
        │   ├── TransactionInterceptorTest.java                      [Task B2]
        │   ├── DataSourceTransactionManagerTest.java                [Task C2]
        │   ├── ScopedValueTsmTest.java                              [Task D1]
        │   └── TsmComparisonTest.java                               [Task D1]
        ├── jdbc/
        │   └── JdbcTemplateTest.java                                [Task C3]
        └── boot/
            └── TransactionalBeanPostProcessorTest.java              [Task B3]

sfs-samples/src/main/java/com/choisk/sfs/samples/
├── order/
│   ├── domain/
│   │   ├── Order.java                                               [Task C4]
│   │   └── AuditLog.java                                            [Task C4]
│   ├── repository/
│   │   ├── OrderRepository.java                                     [Task C4]
│   │   └── AuditRepository.java                                     [Task C4]
│   ├── service/
│   │   ├── OrderService.java                                        [Task C5]
│   │   ├── AuditService.java                                        [Task C5]
│   │   └── BusinessException.java                                   [Task C5]
│   ├── controller/
│   │   └── OrderController.java                                     [Task C5]
│   └── TransactionDemoApplication.java                              [Task C5]

sfs-samples/src/main/resources/
└── schema.sql                                                       [Task C1]

sfs-samples/src/test/java/com/choisk/sfs/samples/order/
├── MockTransactionIntegrationTest.java                              [Task B4]
├── TransactionPropagationIntegrationTest.java                       [Task C6]
├── TransactionDemoApplicationTest.java                              [Task C6]
├── EarlyReferenceIntegrationTest.java                               [Task D2]
└── EnhancedBeanDestroyTest.java                                     [Task D2]
```

### 수정 파일

| 파일 | 변경 내용 | Task |
|---|---|---|
| `settings.gradle.kts` | `include("sfs-tx")` 추가 | A1 |
| `gradle/libs.versions.toml` | `h2 = "2.2.224"` 추가 | C1 |
| `sfs-samples/build.gradle.kts` | `implementation(project(":sfs-tx"))` 추가 + H2 runtimeOnly | A1, C1 |
| `sfs-samples/.../config/AppConfig.java` | `DataSource`/`PlatformTransactionManager`/`TransactionalBeanPostProcessor` `@Bean` 추가 | B4, C5 |
| `sfs-samples/README.md` | Phase 3 갱신 사항 섹션 추가 | E1 |

---

## 섹션 A: 인프라 bottom-up

### Task A1: `sfs-tx` 모듈 신설 + Gradle 설정

**Files:**
- Create: `sfs-tx/build.gradle.kts`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/.gitkeep` (디렉토리 placeholder)
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/.gitkeep`
- Modify: `settings.gradle.kts`
- Modify: `sfs-samples/build.gradle.kts`

**TDD 적용:** ❌ 제외 — 설정 파일 + 디렉토리. 컴파일 + 회귀 테스트만 검증.

- [x] **Step 1: `settings.gradle.kts`에 sfs-tx 모듈 추가**

수정: `settings.gradle.kts`

```kotlin
rootProject.name = "spring-from-scratch"

include(
    "sfs-core",
    "sfs-beans",
    "sfs-context",
    "sfs-aop",
    "sfs-tx",
    "sfs-samples",
)
```

- [x] **Step 2: `sfs-tx/build.gradle.kts` 신설**

생성: `sfs-tx/build.gradle.kts`

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-aop"))
    implementation(libs.bytebuddy)
}
```

> sfs-aop가 transitive로 sfs-context, sfs-beans, sfs-core를 가져옴. byte-buddy는 BPP에서 enhance용 (Phase 2B 패턴 그대로).

- [x] **Step 3: `sfs-samples/build.gradle.kts`에 sfs-tx 의존 추가**

수정: `sfs-samples/build.gradle.kts` — 기존 `implementation(project(":sfs-aop"))` 줄 아래에 추가

```kotlin
implementation(project(":sfs-tx"))
```

- [x] **Step 4: 디렉토리 구조 생성**

```bash
mkdir -p sfs-tx/src/main/java/com/choisk/sfs/tx/{annotation,support,jdbc,boot}
mkdir -p sfs-tx/src/test/java/com/choisk/sfs/tx/{support,jdbc,boot}
touch sfs-tx/src/main/java/com/choisk/sfs/tx/.gitkeep
touch sfs-tx/src/test/java/com/choisk/sfs/tx/.gitkeep
```

- [x] **Step 5: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-tx:compileJava :sfs-samples:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 185 PASS / 0 FAIL (Phase 2B 누적 유지)

- [x] **Step 6: 커밋**

```bash
git add settings.gradle.kts sfs-tx/ sfs-samples/build.gradle.kts
git commit -m "chore(sfs-tx): 모듈 신설 + Gradle 설정 — Phase 3 진입 준비

- settings.gradle.kts에 sfs-tx 추가
- sfs-tx → sfs-aop 의존 (transitive로 context/beans/core)
- sfs-samples → sfs-tx 의존 추가
- 디렉토리 구조: annotation/support/jdbc/boot
"
```

---

### Task A2: 애노테이션/enum/record + 인터페이스 시그니처

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/annotation/Transactional.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/annotation/Propagation.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionDefinition.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/PlatformTransactionManager.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionStatus.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionException.java`

**TDD 적용:** ❌ 제외 — 시그니처 + 데이터 컨테이너 + sealed 예외. 동작 없음. CLAUDE.md "TDD 적용 가이드" 제외 대상.

- [x] **Step 1: `Propagation` enum 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/annotation/Propagation.java`

```java
package com.choisk.sfs.tx.annotation;

/**
 * 트랜잭션 전파 동작. 본 phase는 REQUIRED + REQUIRES_NEW 두 종류만 박제.
 *
 * <p>SUPPORTS, NOT_SUPPORTED, MANDATORY, NEVER, NESTED는 의도된 비목표
 * (spec § 7 한계). 후속 phase 회수 후보.
 */
public enum Propagation {
    /**
     * 현재 트랜잭션이 있으면 join, 없으면 새로 시작. Spring 본가 default.
     */
    REQUIRED,

    /**
     * 항상 새 트랜잭션 시작. 현재 트랜잭션이 있으면 suspend.
     * suspend/resume 메커니즘이 transaction synchronization의 본질.
     */
    REQUIRES_NEW
}
```

- [x] **Step 2: `@Transactional` 애노테이션 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/annotation/Transactional.java`

```java
package com.choisk.sfs.tx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 또는 클래스에 트랜잭션 경계를 부여한다.
 *
 * <p>{@code transactionManager}로 다중 TM 빈 환경에서 라우팅 가능.
 * {@code isolation}, {@code rollbackFor}는 시그니처만 박제 — 동작 검증은
 * 본 phase 비목표 (spec § 7 한계).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Transactional {

    /** 빈 이름. 비어있으면 type 기반 lookup으로 fallback. */
    String transactionManager() default "";

    Propagation propagation() default Propagation.REQUIRED;

    /** 시그니처만, 동작 미검증. */
    int isolation() default -1;

    /** 시그니처만, 동작은 default(RuntimeException rollback)와 동일. */
    Class<? extends Throwable>[] rollbackFor() default {};
}
```

- [x] **Step 3: `TransactionDefinition` record 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionDefinition.java`

```java
package com.choisk.sfs.tx;

import com.choisk.sfs.tx.annotation.Propagation;

/**
 * 트랜잭션 시작 시 의도. {@link com.choisk.sfs.tx.annotation.Transactional}에서
 * 추출되어 TM에 전달된다.
 *
 * <p>{@code isolation}은 시그니처만 (spec § 7 한계).
 */
public record TransactionDefinition(Propagation propagation, int isolation) {

    public static TransactionDefinition required() {
        return new TransactionDefinition(Propagation.REQUIRED, -1);
    }

    public static TransactionDefinition requiresNew() {
        return new TransactionDefinition(Propagation.REQUIRES_NEW, -1);
    }
}
```

- [x] **Step 4: `TransactionStatus` 인터페이스 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionStatus.java`

```java
package com.choisk.sfs.tx;

/**
 * 현재 트랜잭션 상태 핸들. {@link PlatformTransactionManager#getTransaction}이
 * 발급, {@link PlatformTransactionManager#commit}/{@link PlatformTransactionManager#rollback}이 소비.
 */
public interface TransactionStatus {

    /** {@code true}이면 새 트랜잭션, {@code false}이면 외부에 join. */
    boolean isNewTransaction();

    /** outer가 inner의 실패를 받아 commit 시점에 rollback 결정 (REQUIRED join 경로). */
    void setRollbackOnly();

    boolean isRollbackOnly();

    /** {@link com.choisk.sfs.tx.annotation.Propagation#REQUIRES_NEW} 시 outer 보관. {@code null} 가능. */
    Object getSuspendedResources();
}
```

- [x] **Step 5: `PlatformTransactionManager` 인터페이스 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/PlatformTransactionManager.java`

```java
package com.choisk.sfs.tx;

/**
 * 모든 TM의 공통 인터페이스. Spring 본가 {@code org.springframework.transaction.PlatformTransactionManager}와
 * 동명/다른 패키지 (의사결정 #3).
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link com.choisk.sfs.tx.support.MockTransactionManager} — 콘솔 출력만</li>
 *   <li>{@link com.choisk.sfs.tx.support.DataSourceTransactionManager} — JDBC Connection 기반</li>
 * </ul>
 */
public interface PlatformTransactionManager {

    /** 트랜잭션 시작 또는 join. */
    TransactionStatus getTransaction(TransactionDefinition definition);

    /** 정상 커밋. status가 rollback-only면 내부적으로 rollback으로 분기. */
    void commit(TransactionStatus status);

    /** 강제 rollback. */
    void rollback(TransactionStatus status);
}
```

- [x] **Step 6: `TransactionException` sealed 클래스 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/TransactionException.java`

```java
package com.choisk.sfs.tx;

/**
 * 모든 트랜잭션 예외의 루트. sealed로 hierarchy 제한 — sfs-core의
 * {@code BeansException} 패턴과 동일.
 */
public sealed class TransactionException extends RuntimeException
        permits TransactionException.CommitFailedException,
                TransactionException.RollbackFailedException,
                TransactionException.NoTransactionManagerException {

    public TransactionException(String message) { super(message); }

    public TransactionException(String message, Throwable cause) { super(message, cause); }

    public static final class CommitFailedException extends TransactionException {
        public CommitFailedException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class RollbackFailedException extends TransactionException {
        public RollbackFailedException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class NoTransactionManagerException extends TransactionException {
        public NoTransactionManagerException(String message) { super(message); }
    }
}
```

- [x] **Step 7: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-tx:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 185 PASS / 0 FAIL

- [x] **Step 8: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/
git commit -m "feat(sfs-tx): 애노테이션 + enum + 인터페이스 시그니처 박제

- @Transactional (transactionManager/propagation/isolation/rollbackFor)
- Propagation enum (REQUIRED, REQUIRES_NEW)
- TransactionDefinition record + 정적 팩토리 2개
- PlatformTransactionManager 인터페이스 (3 메서드)
- TransactionStatus 인터페이스 (4 메서드)
- TransactionException sealed (CommitFailed/RollbackFailed/NoTransactionManager)

isolation/rollbackFor는 시그니처만 (spec § 7 한계).
"
```

---

### Task A3: `AbstractPlatformTransactionManager` 추상 골격 — propagation 분기 알고리즘

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/DefaultTransactionStatus.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/AbstractPlatformTransactionManager.java`

**TDD 적용:** ❌ 제외 — 추상 골격 (CLAUDE.md "추상 골격 클래스" 예외). 서브클래스 없이 인스턴스화 불가. Task B1 (`MockTransactionManager`) 단위 테스트 + Task C2 (`DataSourceTransactionManager`) 단위 테스트로 간접 검증.

- [x] **Step 1: `DefaultTransactionStatus` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/DefaultTransactionStatus.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionStatus;

/**
 * {@link TransactionStatus} 기본 구현. {@link AbstractPlatformTransactionManager}가 발급.
 *
 * @param transaction TM별 트랜잭션 객체 (Mock은 String, DataSource는 ConnectionHolder)
 * @param newTransaction {@code true}이면 새 트랜잭션, {@code false}이면 join
 * @param suspendedResources REQUIRES_NEW 시 outer 보관본, 아니면 {@code null}
 */
public final class DefaultTransactionStatus implements TransactionStatus {

    private final Object transaction;
    private final boolean newTransaction;
    private final Object suspendedResources;
    private boolean rollbackOnly = false;

    public DefaultTransactionStatus(Object transaction, boolean newTransaction, Object suspendedResources) {
        this.transaction = transaction;
        this.newTransaction = newTransaction;
        this.suspendedResources = suspendedResources;
    }

    public Object getTransaction() { return transaction; }

    @Override public boolean isNewTransaction() { return newTransaction; }

    @Override public void setRollbackOnly() { this.rollbackOnly = true; }

    @Override public boolean isRollbackOnly() { return rollbackOnly; }

    @Override public Object getSuspendedResources() { return suspendedResources; }
}
```

- [x] **Step 2: `AbstractPlatformTransactionManager` 추상 골격 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/AbstractPlatformTransactionManager.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;
import com.choisk.sfs.tx.TransactionStatus;
import com.choisk.sfs.tx.annotation.Propagation;

/**
 * propagation 분기 알고리즘 박제. 구현체는 {@link #doBegin}/{@link #doCommit}/{@link #doRollback}/{@link #doSuspend}/{@link #doResume}만 override.
 *
 * <p>본 추상 골격은 Spring 본가 {@code AbstractPlatformTransactionManager}의 핵심 알고리즘만
 * 발췌 박제. 5종 propagation은 의도적 비목표 (spec § 7 한계).
 */
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager {

    @Override
    public final TransactionStatus getTransaction(TransactionDefinition definition) {
        Object existing = doGetExisting();

        if (existing == null) {
            // 신규 트랜잭션: REQUIRED + REQUIRES_NEW 모두 동일 처리
            Object newTx = doBegin(definition);
            return new DefaultTransactionStatus(newTx, true, null);
        }

        // 존재하는 트랜잭션이 있을 때
        if (definition.propagation() == Propagation.REQUIRES_NEW) {
            Object suspended = doSuspend(existing);
            Object newTx = doBegin(definition);
            return new DefaultTransactionStatus(newTx, true, suspended);
        }

        // REQUIRED — join
        return new DefaultTransactionStatus(existing, false, null);
    }

    @Override
    public final void commit(TransactionStatus status) {
        DefaultTransactionStatus dts = (DefaultTransactionStatus) status;

        if (dts.isRollbackOnly()) {
            // outer가 inner 실패를 받아 rollback (REQUIRED join 경로의 정점)
            rollback(status);
            return;
        }

        if (dts.isNewTransaction()) {
            try {
                doCommit(dts.getTransaction());
            } catch (Throwable t) {
                throw new TransactionException.CommitFailedException("commit failed", t);
            }
        }
        // join인 경우 outer가 commit 책임 — 여기서 아무것도 안 함

        if (dts.getSuspendedResources() != null) {
            doResume(dts.getSuspendedResources());
        }
    }

    @Override
    public final void rollback(TransactionStatus status) {
        DefaultTransactionStatus dts = (DefaultTransactionStatus) status;

        if (dts.isNewTransaction()) {
            try {
                doRollback(dts.getTransaction());
            } catch (Throwable t) {
                throw new TransactionException.RollbackFailedException("rollback failed", t);
            }
        } else {
            // join인 경우 outer에게 rollback 요청
            dts.setRollbackOnly();
        }

        if (dts.getSuspendedResources() != null) {
            doResume(dts.getSuspendedResources());
        }
    }

    /** 현재 thread/scope에 묶인 트랜잭션이 있으면 반환, 없으면 {@code null}. */
    protected abstract Object doGetExisting();

    /** 새 트랜잭션 시작 + thread/scope에 bind. 반환값은 트랜잭션 객체. */
    protected abstract Object doBegin(TransactionDefinition definition);

    /** 트랜잭션 커밋 + bind 해제. */
    protected abstract void doCommit(Object transaction);

    /** 트랜잭션 롤백 + bind 해제. */
    protected abstract void doRollback(Object transaction);

    /** 현재 트랜잭션을 thread/scope에서 분리 + 반환 (REQUIRES_NEW 진입). */
    protected abstract Object doSuspend(Object transaction);

    /** 보관된 리소스를 thread/scope에 다시 bind (REQUIRES_NEW 종료). */
    protected abstract void doResume(Object suspendedResources);
}
```

- [x] **Step 3: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-tx:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 185 PASS / 0 FAIL

- [x] **Step 4: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/
git commit -m "feat(sfs-tx): AbstractPlatformTransactionManager 추상 골격 — propagation 분기 박제

- DefaultTransactionStatus 구현 (transaction/newTransaction/suspendedResources/rollbackOnly)
- AbstractPlatformTransactionManager:
  * getTransaction: 신규/REQUIRES_NEW suspend/REQUIRED join 3분기
  * commit: rollbackOnly → rollback 위임 / newTransaction → doCommit / suspended → resume
  * rollback: newTransaction → doRollback / join → setRollbackOnly / suspended → resume
- 6 추상 메서드 (doGetExisting/doBegin/doCommit/doRollback/doSuspend/doResume)

추상 골격은 TDD 제외 (CLAUDE.md 예외) — Task B1/C2 단위 테스트로 간접 검증.
"
```

---

### Task A4: `TransactionSynchronizationManager` 인터페이스 + `ThreadLocalTsm` + 단위 테스트 4건

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/TransactionSynchronizationManager.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ThreadLocalTsm.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/ThreadLocalTsmTest.java`

**TDD 적용:** ✅ 적용 — TSM은 *resource binding + stack push/pop* 동작이 본질. 동시성/순서 의존이라 TDD 필수.

- [x] **Step 1: 실패 테스트 작성 — `ThreadLocalTsmTest` 4건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/ThreadLocalTsmTest.java`

```java
package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadLocalTsmTest {

    private final ThreadLocalTsm tsm = new ThreadLocalTsm();

    @AfterEach
    void clearThreadState() {
        tsm.clearAll();
    }

    @Test
    void bindResourceIsRetrievable() {
        Object key = new Object();
        Object value = "conn#1";

        tsm.bindResource(key, value);

        assertThat(tsm.getResource(key)).isEqualTo("conn#1");
    }

    @Test
    void unbindResourceReturnsAndRemoves() {
        Object key = new Object();
        tsm.bindResource(key, "conn#1");

        Object removed = tsm.unbindResource(key);

        assertThat(removed).isEqualTo("conn#1");
        assertThat(tsm.getResource(key)).isNull();
    }

    @Test
    void getResourceReturnsNullWhenNotBound() {
        assertThat(tsm.getResource(new Object())).isNull();
    }

    @Test
    void resourcesAreIsolatedAcrossThreads() throws Exception {
        Object key = new Object();
        tsm.bindResource(key, "main-thread-conn");

        Object[] otherThreadValue = new Object[1];
        Thread other = new Thread(() -> otherThreadValue[0] = tsm.getResource(key));
        other.start();
        other.join();

        assertThat(otherThreadValue[0]).isNull();
        assertThat(tsm.getResource(key)).isEqualTo("main-thread-conn");
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.ThreadLocalTsmTest`
Expected: FAIL — `ThreadLocalTsm` 클래스/`TransactionSynchronizationManager` 인터페이스 없음

- [x] **Step 3: `TransactionSynchronizationManager` 인터페이스 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/TransactionSynchronizationManager.java`

```java
package com.choisk.sfs.tx.support;

/**
 * 현재 thread/scope에 트랜잭션 리소스(Connection 등)를 bind/unbind/getResource. Spring 본가
 * {@code org.springframework.transaction.support.TransactionSynchronizationManager}와 동명/다른 패키지.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link ThreadLocalTsm} — 메인 (Spring 본가 정합)</li>
 *   <li>{@link ScopedValueTsm} — Java 25 idiom 비교 박제 (Task D1)</li>
 * </ul>
 */
public interface TransactionSynchronizationManager {

    void bindResource(Object key, Object value);

    Object getResource(Object key);

    Object unbindResource(Object key);

    /** 테스트 셋업/티어다운용. */
    void clearAll();
}
```

- [x] **Step 4: `ThreadLocalTsm` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ThreadLocalTsm.java`

```java
package com.choisk.sfs.tx.support;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link ThreadLocal} 기반 TSM. Spring 본가 {@code TransactionSynchronizationManager} 동일 패턴.
 *
 * <p>가변 Map을 ThreadLocal에 보관 — synchronization 추가 등록 자연 가능. {@link ScopedValueTsm}의
 * immutable 제약과 대조되는 박제.
 */
public class ThreadLocalTsm implements TransactionSynchronizationManager {

    private final ThreadLocal<Map<Object, Object>> resources = ThreadLocal.withInitial(HashMap::new);

    @Override
    public void bindResource(Object key, Object value) {
        resources.get().put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        return resources.get().get(key);
    }

    @Override
    public Object unbindResource(Object key) {
        return resources.get().remove(key);
    }

    @Override
    public void clearAll() {
        resources.remove();
    }
}
```

- [x] **Step 5: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.ThreadLocalTsmTest`
Expected: PASS — 4건

- [x] **Step 6: 회귀 확인**

Run: `./gradlew test`
Expected: 189 PASS / 0 FAIL (185 + 4)

- [x] **Step 7: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/{TransactionSynchronizationManager,ThreadLocalTsm}.java sfs-tx/src/test/java/com/choisk/sfs/tx/support/ThreadLocalTsmTest.java
git commit -m "feat(sfs-tx): TransactionSynchronizationManager 인터페이스 + ThreadLocalTsm 구현 + 단위 테스트 4건

- TransactionSynchronizationManager 인터페이스 (bind/get/unbind/clearAll)
- ThreadLocalTsm: ThreadLocal<HashMap> — Spring 본가 정합
- 테스트 4건: bind+get / unbind / 미bind 시 null / thread isolation

회귀: 185 → 189 PASS.
"
```

---

## 섹션 B: 첫 살아있는 시연 (vertical slice 1 — Mock TM end-to-end)

### Task B1: `MockTransactionManager` + 단위 테스트 4건

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/MockTransactionManager.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/MockTransactionManagerTest.java`

**TDD 적용:** ✅ 적용 — begin/commit/rollback/suspend/resume 5개 동작이 본질. 콘솔 출력은 학습 박제이지만, 호출 *순서*와 *횟수*가 핵심 어서션.

- [x] **Step 1: 실패 테스트 작성 — `MockTransactionManagerTest` 4건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/MockTransactionManagerTest.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class MockTransactionManagerTest {

    private final ThreadLocalTsm tsm = new ThreadLocalTsm();
    private MockTransactionManager tm;
    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void captureStdout() {
        tm = new MockTransactionManager(tsm);
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(original);
        tsm.clearAll();
    }

    @Test
    void beginThenCommitProducesBeginAndCommitLines() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        tm.commit(status);

        String log = stdout.toString();
        assertThat(log).contains("[TX] BEGIN");
        assertThat(log).contains("[TX] COMMIT");
        assertThat(log).doesNotContain("[TX] ROLLBACK");
    }

    @Test
    void beginThenRollbackProducesBeginAndRollbackLines() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        tm.rollback(status);

        String log = stdout.toString();
        assertThat(log).contains("[TX] BEGIN");
        assertThat(log).contains("[TX] ROLLBACK");
        assertThat(log).doesNotContain("[TX] COMMIT");
    }

    @Test
    void requiresNewWhileExistingProducesSuspendAndResume() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.requiresNew());
        tm.commit(inner);
        tm.commit(outer);

        String log = stdout.toString();
        assertThat(log).contains("[TX] SUSPEND");
        assertThat(log).contains("[TX] RESUME");
        // 순서 확인
        assertThat(log.indexOf("[TX] SUSPEND"))
                .isLessThan(log.indexOf("[TX] RESUME"));
    }

    @Test
    void requiredJoinDoesNotProduceNewBegin() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.required());

        assertThat(inner.isNewTransaction()).isFalse();
        tm.commit(inner);
        tm.commit(outer);

        // BEGIN은 outer 1회만 — 콘솔에 BEGIN 라인이 정확히 1번
        long beginCount = stdout.toString().lines().filter(l -> l.contains("[TX] BEGIN")).count();
        assertThat(beginCount).isEqualTo(1L);
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.MockTransactionManagerTest`
Expected: FAIL — `MockTransactionManager` 없음

- [x] **Step 3: `MockTransactionManager` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/MockTransactionManager.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 콘솔 출력만 하는 Mock TM. 추상화의 본질이 인터페이스에 있음을 박제하기 위한 학습용.
 *
 * <p>실제 DB 없이 propagation 분기 알고리즘({@link AbstractPlatformTransactionManager})만으로
 * suspend/resume 시연 가능. 출력 라인은 {@code [TX] BEGIN/COMMIT/ROLLBACK/SUSPEND/RESUME #N} 형식.
 */
public class MockTransactionManager extends AbstractPlatformTransactionManager {

    private static final Object TX_KEY = MockTransactionManager.class;

    private final TransactionSynchronizationManager tsm;
    private final AtomicLong txIdGen = new AtomicLong(0L);

    public MockTransactionManager(TransactionSynchronizationManager tsm) {
        this.tsm = tsm;
    }

    @Override
    protected Object doGetExisting() {
        return tsm.getResource(TX_KEY);
    }

    @Override
    protected Object doBegin(TransactionDefinition definition) {
        long id = txIdGen.incrementAndGet();
        String txObj = "tx#" + id;
        tsm.bindResource(TX_KEY, txObj);
        System.out.println("[TX] BEGIN     #" + id);
        return txObj;
    }

    @Override
    protected void doCommit(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] COMMIT    " + transaction);
    }

    @Override
    protected void doRollback(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] ROLLBACK  " + transaction);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        tsm.unbindResource(TX_KEY);
        System.out.println("[TX] SUSPEND   " + transaction);
        return transaction;
    }

    @Override
    protected void doResume(Object suspendedResources) {
        tsm.bindResource(TX_KEY, suspendedResources);
        System.out.println("[TX] RESUME    " + suspendedResources);
    }
}
```

- [x] **Step 4: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.MockTransactionManagerTest`
Expected: PASS — 4건

- [x] **Step 5: 회귀 확인**

Run: `./gradlew test`
Expected: 193 PASS / 0 FAIL (189 + 4)

- [x] **Step 6: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/MockTransactionManager.java sfs-tx/src/test/java/com/choisk/sfs/tx/support/MockTransactionManagerTest.java
git commit -m "feat(sfs-tx): MockTransactionManager + 단위 테스트 4건 — 추상화 본질 박제

- AbstractPlatformTransactionManager 상속, 6 추상 메서드 override
- TSM에 'tx#N' 문자열 binding으로 doGetExisting 구현
- 콘솔 출력: [TX] BEGIN/COMMIT/ROLLBACK/SUSPEND/RESUME #N
- 테스트 4건: begin+commit / begin+rollback / requires_new suspend+resume / required join 시 BEGIN 1회

회귀: 189 → 193 PASS. 추상 골격(A3)도 이 테스트로 간접 검증.
"
```

---

### Task B2: `TransactionInterceptor` (advice 본체) + 단위 테스트 6건 (B-2 흡수)

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/TransactionInterceptor.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/TransactionInterceptorTest.java`

**TDD 적용:** ✅ 적용 — try/commit/rollback 분기, RuntimeException vs checked, setRollbackOnly, TM 이름 라우팅(B-2)이 모두 동작 분기. TDD 필수.

- [x] **Step 1: 실패 테스트 작성 — `TransactionInterceptorTest` 6건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/TransactionInterceptorTest.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionInterceptorTest {

    private DefaultListableBeanFactory beanFactory;
    private RecordingTm primaryTm;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        primaryTm = new RecordingTm("primary");
        beanFactory.registerSingleton("primaryTm", primaryTm);
    }

    @Test
    void normalReturnTriggersCommit() throws Exception {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void runtimeExceptionTriggersRollback() throws Exception {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        assertThatThrownBy(() ->
                interceptor.invoke(new SampleService(), m, () -> { throw new IllegalStateException("biz"); })
        ).isInstanceOf(IllegalStateException.class);

        assertThat(primaryTm.events).containsExactly("getTransaction", "rollback");
    }

    @Test
    void checkedExceptionTriggersCommit() throws Exception {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        assertThatThrownBy(() ->
                interceptor.invoke(new SampleService(), m, () -> { throw new java.io.IOException("io"); })
        ).isInstanceOf(java.io.IOException.class);

        // checked는 commit (Spring 본가 정합)
        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void namedTransactionManagerRouting() throws Exception {
        RecordingTm secondary = new RecordingTm("secondary");
        beanFactory.registerSingleton("secondaryTm", secondary);

        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWorkWithSecondary");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(secondary.events).containsExactly("getTransaction", "commit");
        assertThat(primaryTm.events).isEmpty();
    }

    @Test
    void typeBasedFallbackWhenTmNameNotSpecified() throws Exception {
        // primary만 등록되어 있고, @Transactional은 transactionManager 미지정
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doWork");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.events).containsExactly("getTransaction", "commit");
    }

    @Test
    void requiresNewPropagationPassedToTm() throws Exception {
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);
        Method m = SampleService.class.getMethod("doRequiresNew");

        interceptor.invoke(new SampleService(), m, () -> "ok");

        assertThat(primaryTm.lastDefinition.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ===== test fixtures =====

    static class SampleService {
        @Transactional public String doWork() { return "ok"; }
        @Transactional(transactionManager = "secondaryTm") public String doWorkWithSecondary() { return "ok"; }
        @Transactional(propagation = Propagation.REQUIRES_NEW) public String doRequiresNew() { return "ok"; }
    }

    static class RecordingTm implements PlatformTransactionManager {
        final String name;
        final List<String> events = new ArrayList<>();
        com.choisk.sfs.tx.TransactionDefinition lastDefinition;

        RecordingTm(String name) { this.name = name; }

        @Override public com.choisk.sfs.tx.TransactionStatus getTransaction(com.choisk.sfs.tx.TransactionDefinition def) {
            events.add("getTransaction");
            this.lastDefinition = def;
            return new DefaultTransactionStatus(name + "-tx", true, null);
        }
        @Override public void commit(com.choisk.sfs.tx.TransactionStatus status) { events.add("commit"); }
        @Override public void rollback(com.choisk.sfs.tx.TransactionStatus status) { events.add("rollback"); }
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.TransactionInterceptorTest`
Expected: FAIL — `TransactionInterceptor` 없음

- [x] **Step 3: `TransactionInterceptor` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/TransactionInterceptor.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;
import com.choisk.sfs.tx.TransactionStatus;
import com.choisk.sfs.tx.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * {@link Transactional} advice 본체. Phase 2B {@code AdviceInterceptor}와 동일 구조.
 *
 * <p>분기:
 * <ul>
 *   <li>정상 반환 → commit</li>
 *   <li>RuntimeException/Error → rollback</li>
 *   <li>Checked Exception → commit (Spring 본가 정합)</li>
 * </ul>
 *
 * <p>{@link Transactional#transactionManager()}로 다중 TM 빈 라우팅 (Phase 2B B-2 흡수).
 */
public class TransactionInterceptor {

    private final BeanFactory beanFactory;

    public TransactionInterceptor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Object invoke(Object target, Method method, Callable<Object> proceed) throws Throwable {
        Transactional anno = findAnnotation(method);
        PlatformTransactionManager tm = resolveTransactionManager(anno);
        TransactionDefinition def = new TransactionDefinition(anno.propagation(), anno.isolation());

        TransactionStatus status = tm.getTransaction(def);
        try {
            Object result = proceed.call();
            tm.commit(status);
            return result;
        } catch (RuntimeException | Error t) {
            tm.rollback(status);
            throw t;
        } catch (Throwable t) {
            // checked exception → commit (Spring 본가 정합)
            tm.commit(status);
            throw t;
        }
    }

    private Transactional findAnnotation(Method method) {
        Transactional onMethod = method.getAnnotation(Transactional.class);
        if (onMethod != null) return onMethod;
        Transactional onClass = method.getDeclaringClass().getAnnotation(Transactional.class);
        if (onClass != null) return onClass;
        throw new IllegalStateException("@Transactional not found on " + method);
    }

    private PlatformTransactionManager resolveTransactionManager(Transactional anno) {
        String name = anno.transactionManager();
        if (!name.isEmpty()) {
            return beanFactory.getBean(name, PlatformTransactionManager.class);
        }
        // type 기반 fallback (B-2 흡수)
        try {
            return beanFactory.getBean(PlatformTransactionManager.class);
        } catch (Exception e) {
            throw new TransactionException.NoTransactionManagerException(
                    "No PlatformTransactionManager bean found");
        }
    }
}
```

- [x] **Step 4: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.TransactionInterceptorTest`
Expected: PASS — 6건

- [x] **Step 5: 회귀 확인**

Run: `./gradlew test`
Expected: 199 PASS / 0 FAIL (193 + 6)

- [x] **Step 6: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/TransactionInterceptor.java sfs-tx/src/test/java/com/choisk/sfs/tx/support/TransactionInterceptorTest.java
git commit -m "feat(sfs-tx): TransactionInterceptor advice + 단위 테스트 6건 — B-2 (TM 이름 라우팅) 흡수

- invoke(target, method, proceed):
  * 정상 반환 → commit
  * RuntimeException/Error → rollback + rethrow
  * Checked Exception → commit + rethrow (Spring 본가 정합)
- resolveTransactionManager: anno.transactionManager() name lookup → type fallback
- 테스트 6건: 정상 commit / RT rollback / checked commit / 이름 라우팅 / type fallback / REQUIRES_NEW propagation 전달

회귀: 193 → 199 PASS.
"
```

> **실행 기록 (2026-05-01):**
> - **편차 1**: 테스트 메서드 시그니처 `throws Exception` → `throws Throwable` 변경. Plan 코드에서 `invoke`가 `throws Throwable`을 선언하지만 테스트가 `throws Exception`으로 되어 있어 컴파일 에러 발생. `Throwable > Exception`이므로 테스트 시그니처를 `throws Throwable`로 수정.
> - **편차 2**: `sfs-tx/build.gradle.kts`에 `implementation(project(":sfs-beans"))` 명시 추가. Plan 주석에 "sfs-aop가 transitive로 sfs-beans를 가져옴"이라고 기재됐으나, sfs-aop는 `implementation(project(":sfs-context"))`으로 의존하므로 transitive export가 없음. `sfs-beans`를 sfs-tx 의존에 직접 추가.
> - **편차 3**: `DefaultListableBeanFactory.resolveBeanNameByType`가 BeanDefinition 기반만 검색하여 `registerSingleton`으로 등록된 빈을 type-lookup 시 찾지 못하는 버그 수정. `resolveBeansOfType` (BeanDefinition + 직접 등록 싱글톤 합산) 활용하도록 수정.

---

### Task B3: `TransactionalBeanPostProcessor` + 단위 테스트 5건 (A-1/A-3 흡수)

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessor.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessorTest.java`

**TDD 적용:** ✅ 적용 — Phase 2B `AspectEnhancingBeanPostProcessor` 패턴 회수. enhance/skip/final 가드/`@Component` 누락 검증 모두 동작 분기. TDD 필수.

- [x] **Step 1: 실패 테스트 작성 — `TransactionalBeanPostProcessorTest` 5건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessorTest.java`

```java
package com.choisk.sfs.tx.boot;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalBeanPostProcessorTest {

    private DefaultListableBeanFactory beanFactory;
    private TransactionalBeanPostProcessor bpp;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("tm", new MockTransactionManager(new ThreadLocalTsm()));
        bpp = new TransactionalBeanPostProcessor();
        bpp.setBeanFactory(beanFactory);
    }

    @Test
    void enhanceTransactionalBean() {
        SampleTransactionalService bean = new SampleTransactionalService();

        Object enhanced = bpp.postProcessAfterInitialization(bean, "sample");

        assertThat(enhanced.getClass()).isNotEqualTo(SampleTransactionalService.class);
        assertThat(enhanced).isInstanceOf(SampleTransactionalService.class); // 서브클래스
    }

    @Test
    void skipBeansWithoutTransactionalAnnotation() {
        PlainService bean = new PlainService();

        Object result = bpp.postProcessAfterInitialization(bean, "plain");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void selfIsolationOfBeanPostProcessor() {
        // BPP는 자신을 enhance하지 않음
        Object result = bpp.postProcessAfterInitialization(bpp, "bpp");

        assertThat(result).isSameAs(bpp);
    }

    @Test
    void warnOnFinalTransactionalMethod() {
        // A-1 흡수: final @Transactional 메서드는 silent skip 대신 WARN
        ServiceWithFinalTransactional bean = new ServiceWithFinalTransactional();

        // 시스템 출력 캡처는 본 단위 테스트의 부수가 아님 — WARN 메시지가 로그/예외로 박제됨을 검증
        // 본 phase는 WARN 우선 (의사결정 #12)
        Object enhanced = bpp.postProcessAfterInitialization(bean, "withFinal");

        // 통합 검증: enhance는 진행되지만 final 메서드는 advice 비적용
        assertThat(enhanced).isNotNull();
        assertThat(bpp.getLastFinalMethodWarnings()).isNotEmpty();
    }

    @Test
    void rejectTransactionalClassWithoutComponentRegistration() {
        // A-3 흡수: @Transactional만 부착, BeanFactory에 등록되지 않은 클래스 검출
        // 본 단위 테스트는 BPP가 등록 여부를 *검증*하는 게 본질이 아니라,
        // BPP 진입 시 빈 이름이 BeanFactory에 있는지 확인하는 가드 검증
        SampleTransactionalService bean = new SampleTransactionalService();
        // 빈 이름을 일부러 BeanFactory에 *없는* 이름으로
        assertThatThrownBy(() -> bpp.postProcessAfterInitialization(bean, "unregistered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be registered as a bean");
    }

    // ===== test fixtures =====

    static class SampleTransactionalService {
        @Transactional public String doWork() { return "ok"; }
    }

    static class PlainService {
        public String doWork() { return "ok"; }
    }

    static class ServiceWithFinalTransactional {
        @Transactional public final String doFinalWork() { return "ok"; }
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.boot.TransactionalBeanPostProcessorTest`
Expected: FAIL — `TransactionalBeanPostProcessor` 없음

> **편차 박제 가능성**: A-3 검증 task 5번 테스트는 *BPP 진입 시점에 BeanFactory에서 빈 이름 lookup*하는 가드인데, 실제로는 BPP가 빈 등록 *후* 호출되므로 항상 등록되어 있음. 따라서 이 가드는 *별도 ApplicationContext refresh hook*에서 동작해야 의미가 있음. **테스트 5번은 향후 통합 테스트로 이동 가능** — 실행 단계에서 판단해서 plan에 박제.

> **실행 기록 (2026-05-01):**
> - **편차**: Test #5 (rejectTransactionalClassWithoutComponentRegistration) 제거. plan 자신이 라인 1390/1530~1535에서 제거 옵션을 명시적으로 위임.
> - **근거**: BPP는 빈 생성 *후* 호출되므로 빈 이름이 BeanFactory에 항상 등록된 상태. A-3 검증은 ApplicationContext.refresh 시점이 정확한 위치 — 후속 phase로 이월.
> - **영향**: 테스트 4건, 회귀 +4 (199 → 203). DoD 13번(`단위 테스트 5건` → `단위 테스트 4건`)도 보정 대상.

- [x] **Step 3: `TransactionalBeanPostProcessor` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessor.java`

```java
package com.choisk.sfs.tx.boot;

import com.choisk.sfs.core.BeanCreationException;
import com.choisk.sfs.beans.BeanFactory;
import com.choisk.sfs.beans.BeanFactoryAware;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.SmartInstantiationAwareBeanPostProcessor;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.support.TransactionInterceptor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * byte-buddy 기반 {@code @Transactional} BPP. Phase 2B {@code AspectEnhancingBeanPostProcessor}와 동일 패턴.
 *
 * <p>postProcessAfterInitialization 동작:
 * <ol>
 *   <li>BPP 자기 격리 (Phase 2B 패턴)</li>
 *   <li>{@code @Transactional} 메서드 없으면 원본 그대로</li>
 *   <li>final 메서드 가드 (A-1) — WARN 박제, enhance는 진행</li>
 *   <li>byte-buddy 서브클래스 + interceptor 적용 + 필드 reflection 복사</li>
 * </ol>
 *
 * <p>{@link SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference}는 별도 task에서 구현 (A-2 흡수).
 */
public class TransactionalBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;
    private final List<String> lastFinalMethodWarnings = new ArrayList<>();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;

        if (!hasTransactionalMethod(bean.getClass())) return bean;

        warnOnFinalTransactionalMethods(bean.getClass());

        try {
            return enhance(bean);
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Failed to enhance @Transactional bean", e);
        }
    }

    private boolean hasTransactionalMethod(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Transactional.class)) return true;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Transactional.class)) return true;
        }
        return false;
    }

    private void warnOnFinalTransactionalMethods(Class<?> clazz) {
        lastFinalMethodWarnings.clear();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Transactional.class) && Modifier.isFinal(m.getModifiers())) {
                String warn = "WARN: @Transactional method is final and will be silently skipped: " + m;
                System.err.println(warn);
                lastFinalMethodWarnings.add(warn);
            }
        }
    }

    private Object enhance(Object bean) throws Exception {
        Class<?> originalClass = bean.getClass();
        TransactionInterceptor interceptor = new TransactionInterceptor(beanFactory);

        Class<?> enhanced = new ByteBuddy()
                .subclass(originalClass)
                .method(ElementMatchers.isAnnotatedWith(Transactional.class)
                        .and(ElementMatchers.not(ElementMatchers.isFinal())))
                .intercept(MethodDelegation.to(new TxMethodInterceptor(interceptor)))
                .make()
                .load(originalClass.getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(MethodHandles.lookup()))
                .getLoaded();

        Object enhancedInstance = enhanced.getDeclaredConstructor().newInstance();
        copyFields(bean, enhancedInstance);
        return enhancedInstance;
    }

    private void copyFields(Object src, Object dst) throws Exception {
        Class<?> clazz = src.getClass();
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            f.set(dst, f.get(src));
        }
    }

    /** 테스트 보조 — A-1 WARN 박제 검증용. */
    public List<String> getLastFinalMethodWarnings() {
        return List.copyOf(lastFinalMethodWarnings);
    }

    /** byte-buddy interceptor — TransactionInterceptor.invoke로 위임. */
    public static class TxMethodInterceptor {
        private final TransactionInterceptor delegate;
        public TxMethodInterceptor(TransactionInterceptor delegate) { this.delegate = delegate; }

        @RuntimeType
        public Object intercept(@Origin Method method, @AllArguments Object[] args, @SuperCall Callable<Object> superCall) throws Throwable {
            return delegate.invoke(null, method, superCall);
        }
    }
}
```

- [x] **Step 4: 테스트 실행 — PASS 확인 + A-3 가드 편차 박제**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.boot.TransactionalBeanPostProcessorTest`
Expected: PASS — 4건 (test #5 *제외* 가능성 있음 — Step 2 박제 참조)

> **편차 처리 결정:** A-3 (`@Component` 누락 검증)은 BPP가 *후처리*에서 빈 이름을 받아 검증하는 형태로는 의미가 약함 (BPP 호출 시점에는 이미 빈으로 등록됨). 본 phase 구현은 *부분 흡수* — `lastFinalMethodWarnings` 같은 *학습 박제* 후크는 두지만, 명시 에러는 통합 테스트로 이전.
>
> 실행 단계에서 다음 중 하나로 결정:
> 1. 테스트 5번을 *통합 테스트*로 이동 (사실상 ApplicationContext refresh 시점에서 검증해야 의미)
> 2. 테스트 5번을 *제거*하고 spec § 5.4의 A-3 흡수 위치를 "ApplicationContext refresh hook" 후속 phase 박제로 변경
>
> Plan 문서 § "실행 기록"에 결정 박제 필수.

- [x] **Step 5: 회귀 확인**

Run: `./gradlew test`
Expected: 203~204 PASS / 0 FAIL (199 + 4~5)

- [x] **Step 6: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessor.java sfs-tx/src/test/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessorTest.java
git commit -m "feat(sfs-tx): TransactionalBeanPostProcessor + 단위 테스트 — A-1 (final WARN) 흡수

- Phase 2B AspectEnhancingBeanPostProcessor 패턴 회수
- byte-buddy 서브클래스 + TxMethodInterceptor + 필드 reflection 복사
- A-1 흡수: final @Transactional 메서드 WARN 출력 (silent skip 대신)
- A-3 가드는 BPP 시점에는 의미 약함 → 통합 테스트로 흡수 위치 변경 가능

회귀: 199 → 203~204 PASS.
"
```

---

### Task B4: 첫 살아있는 시연 — Mock TM + AppConfig 등록 + 통합 테스트

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java`
- Create: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/MockTransactionIntegrationTest.java`

**TDD 적용:** ✅ 적용 — vertical slice 1의 첫 살아있는 시연. AppConfig 빈 등록이 정확히 advice를 트리거하는지 통합 검증.

- [x] **Step 1: 실패 테스트 작성 — `MockTransactionIntegrationTest` 3건**

생성: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/MockTransactionIntegrationTest.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class MockTransactionIntegrationTest {

    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void capture() {
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restore() {
        System.setOut(original);
    }

    @Test
    void singleTransactionalMethodTriggersBeginAndCommit() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            demo.doSimple();

            String log = stdout.toString();
            assertThat(log).contains("[TX] BEGIN");
            assertThat(log).contains("[TX] COMMIT");
        }
    }

    @Test
    void runtimeExceptionTriggersRollback() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            try { demo.doFailing(); } catch (RuntimeException ignored) {}

            String log = stdout.toString();
            assertThat(log).contains("[TX] BEGIN");
            assertThat(log).contains("[TX] ROLLBACK");
            assertThat(log).doesNotContain("[TX] COMMIT");
        }
    }

    @Test
    void requiresNewProducesSuspendAndResume() {
        try (var ctx = new AnnotationConfigApplicationContext(MockTxAppConfig.class)) {
            DemoService demo = ctx.getBean(DemoService.class);

            demo.doOuter();  // outer(REQUIRED) → inner(REQUIRES_NEW)

            String log = stdout.toString();
            assertThat(log).contains("[TX] SUSPEND");
            assertThat(log).contains("[TX] RESUME");
            // 최소 BEGIN 2회 (outer + inner)
            long beginCount = log.lines().filter(l -> l.contains("[TX] BEGIN")).count();
            assertThat(beginCount).isEqualTo(2L);
        }
    }

    // ===== test config =====

    @Configuration
    static class MockTxAppConfig {
        @Bean public TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
        @Bean public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }
        @Bean public TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
        @Bean public DemoService demoService(InnerService inner) { return new DemoService(inner); }
        @Bean public InnerService innerService() { return new InnerService(); }
    }

    @Component
    static class DemoService {
        private final InnerService inner;
        DemoService(InnerService inner) { this.inner = inner; }

        @Transactional public void doSimple() { /* nothing */ }

        @Transactional public void doFailing() {
            throw new RuntimeException("biz");
        }

        @Transactional public void doOuter() {
            inner.doInner();
        }
    }

    @Component
    static class InnerService {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void doInner() { /* nothing */ }
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-samples:test --tests com.choisk.sfs.samples.order.MockTransactionIntegrationTest`
Expected: FAIL — `MockTxAppConfig` 안 빈 등록만 가능. 컴파일 PASS이지만 advice 동작 안 함 → 콘솔 출력 부재로 어서션 실패.

- [x] **Step 3: AppConfig에 BPP 등록 (편차 가능성 — 본 task는 *전용 config*를 사용하므로 메인 AppConfig 수정 미필요)**

> **편차 박제:** 본 task의 통합 테스트는 *내부 `MockTxAppConfig` static class*에 BPP를 등록. 메인 `sfs-samples/.../AppConfig.java`에는 *Phase 2B의 LoggingAspect 등록*만 그대로 유지. 본 phase의 *DataSource + 진짜 TM 등록*은 Task C5에서 수행.
>
> 따라서 Step 3는 *수정 없음* — Step 1의 `MockTxAppConfig`로 충분.

- [x] **Step 4: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-samples:test --tests com.choisk.sfs.samples.order.MockTransactionIntegrationTest`
Expected: PASS — 3건

- [x] **Step 5: 회귀 확인**

Run: `./gradlew test`
Expected: 206~207 PASS / 0 FAIL (203~204 + 3)

- [x] **Step 6: 커밋**

```bash
git add sfs-samples/src/test/java/com/choisk/sfs/samples/order/MockTransactionIntegrationTest.java
git commit -m "test(sfs-samples): Mock TM end-to-end 통합 테스트 3건 — vertical slice 1 첫 살아있는 시연

- AnnotationConfigApplicationContext + TransactionalBeanPostProcessor 자동 advice 적용 검증
- 3 시나리오: 단순 commit / RuntimeException rollback / REQUIRES_NEW suspend+resume
- AppConfig는 본 task에서 수정 안 함 — 전용 MockTxAppConfig로 격리

회귀: 203~204 → 206~207 PASS. Phase 3 첫 살아있는 시연 완성.
"
```

> **실행 기록 (2026-05-01):**
> - **편차 1**: plan 라인 1670~1693의 fixture(`final` 필드 + 명시 생성자)가 BPP의 no-arg 생성자 + non-final 필드 한계와 부정합. fixture를 Phase 1/2B 정합 패턴(package-private 필드 + `@Bean` 팩토리 직접 할당)으로 수정.
> - **편차 2 (미박제 추가 편차)**: `TransactionalBeanPostProcessor.enhance()`가 `ClassLoadingStrategy.UsingLookup.of(MethodHandles.lookup())`를 사용하여 타 패키지 클래스 enhance 불가(`ByteBuddy must be defined in the same package as TransactionalBeanPostProcessor` 오류). `AspectEnhancingBeanPostProcessor` 정합으로 `MethodHandles.privateLookupIn(originalClass, MethodHandles.lookup())`로 수정. `sfs-tx` 기존 회귀 모두 PASS 유지.
> - **근거**: `TransactionalBeanPostProcessor.enhance()`는 `MethodHandles.lookup()`으로 호출자 패키지(`com.choisk.sfs.tx.boot`)의 Lookup만 가지므로, 타 패키지 클래스의 서브클래스 주입 불가. `privateLookupIn`은 대상 클래스의 ClassLoader 컨텍스트에서 Lookup을 획득하여 패키지 무관하게 동작. Phase 2C+에서 constructor injection 박제 시 `enhance()` 전면 리팩토링 예정.
> - **영향**: 테스트 3건/회귀 +3은 plan과 동일. 회귀: 203 → 206 PASS.

---

## 섹션 C: JDBC 통합 + 시연 도메인 (vertical slice 2)

### Task C1: H2 의존 추가 + DataSource 빈 + schema.sql

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `sfs-tx/build.gradle.kts`
- Modify: `sfs-samples/build.gradle.kts`
- Create: `sfs-samples/src/main/resources/schema.sql`

**TDD 적용:** ❌ 제외 — 환경 셋업 (의존성/SQL DDL). 컴파일 + 회귀 테스트만.

- [x] **Step 1: `libs.versions.toml`에 H2 추가**

수정: `gradle/libs.versions.toml`

```toml
[versions]
junit = "5.11.3"
assertj = "3.26.3"
asm = "9.9.1"
bytebuddy = "1.14.19"
h2 = "2.2.224"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
asm-commons = { module = "org.ow2.asm:asm-commons", version.ref = "asm" }
bytebuddy = { module = "net.bytebuddy:byte-buddy", version.ref = "bytebuddy" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
```

- [x] **Step 2: `sfs-tx/build.gradle.kts`에 H2 testImplementation 추가**

수정: `sfs-tx/build.gradle.kts`

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-aop"))
    implementation(libs.bytebuddy)

    testImplementation(libs.h2)
}
```

- [x] **Step 3: `sfs-samples/build.gradle.kts`에 H2 runtimeOnly 추가**

수정: `sfs-samples/build.gradle.kts` — 의존성 블록에 추가

```kotlin
runtimeOnly(libs.h2)
testImplementation(libs.h2)
```

- [x] **Step 4: `schema.sql` 신설**

생성: `sfs-samples/src/main/resources/schema.sql`

```sql
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS audit_log;

CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item VARCHAR(200) NOT NULL,
    amount INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    message VARCHAR(500) NOT NULL
);
```

- [x] **Step 5: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-tx:compileJava :sfs-samples:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 206~207 PASS / 0 FAIL (변동 없음)

- [x] **Step 6: 커밋**

```bash
git add gradle/libs.versions.toml sfs-tx/build.gradle.kts sfs-samples/build.gradle.kts sfs-samples/src/main/resources/schema.sql
git commit -m "chore(sfs-tx): H2 임베디드 DB 의존 추가 + schema.sql 신설

- libs.versions.toml에 h2 = '2.2.224' 추가
- sfs-tx: testImplementation(libs.h2) — 단위 테스트용
- sfs-samples: runtimeOnly + testImplementation — 시연/통합 테스트용
- schema.sql: orders / audit_log 두 테이블

spec § 7 한계: HikariCP 등 connection pool 미사용, schema migration tool 없음.
"
```

---

### Task C2: `DataSourceTransactionManager` + 단위 테스트 5건

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ConnectionHolder.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/DataSourceTransactionManager.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/DataSourceTransactionManagerTest.java`

**TDD 적용:** ✅ 적용 — `Connection.setAutoCommit(false)` / `commit()` / `rollback()` 실제 호출이 분기. JDBC 외부 경계.

- [x] **Step 1: 실패 테스트 작성 — `DataSourceTransactionManagerTest` 5건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/DataSourceTransactionManagerTest.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceTransactionManagerTest {

    private DataSource dataSource;
    private ThreadLocalTsm tsm;
    private DataSourceTransactionManager tm;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT)");
        }
        this.dataSource = ds;
        this.tsm = new ThreadLocalTsm();
        this.tm = new DataSourceTransactionManager(dataSource, tsm);
    }

    @AfterEach
    void tearDown() {
        tsm.clearAll();
    }

    @Test
    void beginBindsConnectionToTsm() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());

        assertThat(tsm.getResource(dataSource)).isNotNull();
        assertThat(status.isNewTransaction()).isTrue();

        tm.commit(status);
    }

    @Test
    void commitTriggersConnectionCommitAndUnbinds() throws Exception {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try (Statement s = holder.getConnection().createStatement()) {
            s.execute("INSERT INTO t VALUES (1)");
        }
        tm.commit(status);

        assertThat(tsm.getResource(dataSource)).isNull();
        // 새 connection으로 INSERT가 commit되었는지 확인
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM t");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void rollbackTriggersConnectionRollbackAndUnbinds() throws Exception {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try (Statement s = holder.getConnection().createStatement()) {
            s.execute("INSERT INTO t VALUES (1)");
        }
        tm.rollback(status);

        assertThat(tsm.getResource(dataSource)).isNull();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM t");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    @Test
    void requiresNewSuspendsCurrentConnection() {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder outerHolder = (ConnectionHolder) tsm.getResource(dataSource);

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.requiresNew());
        ConnectionHolder innerHolder = (ConnectionHolder) tsm.getResource(dataSource);

        assertThat(innerHolder).isNotSameAs(outerHolder);
        assertThat(inner.isNewTransaction()).isTrue();

        tm.commit(inner);
        // outer 복원
        assertThat(tsm.getResource(dataSource)).isSameAs(outerHolder);

        tm.commit(outer);
    }

    @Test
    void connectionAutoCommitIsDisabledOnBegin() {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);

        try {
            assertThat(holder.getConnection().getAutoCommit()).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tm.commit(status);
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.DataSourceTransactionManagerTest`
Expected: FAIL — `DataSourceTransactionManager`/`ConnectionHolder` 없음

- [x] **Step 3: `ConnectionHolder` 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ConnectionHolder.java`

```java
package com.choisk.sfs.tx.support;

import java.sql.Connection;

/**
 * TSM에 binding되는 트랜잭션 컨텍스트. {@link DataSourceTransactionManager}가 begin 시 생성/bind,
 * commit/rollback 시 unbind/close. {@link com.choisk.sfs.tx.jdbc.JdbcTemplate}이 getResource로 조회.
 */
public final class ConnectionHolder {

    private final Connection connection;

    public ConnectionHolder(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
```

- [x] **Step 4: `DataSourceTransactionManager` 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/DataSourceTransactionManager.java`

```java
package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC {@link Connection} 기반 TM. Spring 본가 {@code DataSourceTransactionManager}와 동명/다른 패키지.
 *
 * <p>begin: getConnection → setAutoCommit(false) → ConnectionHolder bind to TSM
 * <br>commit: doCommit(connection) → connection.close → unbind
 * <br>rollback: doRollback(connection) → connection.close → unbind
 * <br>suspend: unbind만 (보관본 = ConnectionHolder)
 * <br>resume: bind 보관본
 */
public class DataSourceTransactionManager extends AbstractPlatformTransactionManager {

    private final DataSource dataSource;
    private final TransactionSynchronizationManager tsm;

    public DataSourceTransactionManager(DataSource dataSource, TransactionSynchronizationManager tsm) {
        this.dataSource = dataSource;
        this.tsm = tsm;
    }

    public DataSource getDataSource() { return dataSource; }
    public TransactionSynchronizationManager getTsm() { return tsm; }

    @Override
    protected Object doGetExisting() {
        return tsm.getResource(dataSource);
    }

    @Override
    protected Object doBegin(TransactionDefinition definition) {
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            ConnectionHolder holder = new ConnectionHolder(conn);
            tsm.bindResource(dataSource, holder);
            return holder;
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("failed to begin", e);
        }
    }

    @Override
    protected void doCommit(Object transaction) {
        ConnectionHolder holder = (ConnectionHolder) transaction;
        try {
            holder.getConnection().commit();
        } catch (SQLException e) {
            throw new TransactionException.CommitFailedException("commit failed", e);
        } finally {
            tsm.unbindResource(dataSource);
            closeQuietly(holder.getConnection());
        }
    }

    @Override
    protected void doRollback(Object transaction) {
        ConnectionHolder holder = (ConnectionHolder) transaction;
        try {
            holder.getConnection().rollback();
        } catch (SQLException e) {
            throw new TransactionException.RollbackFailedException("rollback failed", e);
        } finally {
            tsm.unbindResource(dataSource);
            closeQuietly(holder.getConnection());
        }
    }

    @Override
    protected Object doSuspend(Object transaction) {
        return tsm.unbindResource(dataSource);
    }

    @Override
    protected void doResume(Object suspendedResources) {
        tsm.bindResource(dataSource, suspendedResources);
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); } catch (SQLException ignored) {}
    }
}
```

- [x] **Step 5: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.DataSourceTransactionManagerTest`
Expected: PASS — 5건

- [x] **Step 6: 회귀 확인**

Run: `./gradlew test`
Expected: 211~212 PASS / 0 FAIL (206~207 + 5)

- [x] **Step 7: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/{ConnectionHolder,DataSourceTransactionManager}.java sfs-tx/src/test/java/com/choisk/sfs/tx/support/DataSourceTransactionManagerTest.java
git commit -m "feat(sfs-tx): DataSourceTransactionManager + 단위 테스트 5건 — JDBC 통합 본질

- ConnectionHolder: TSM bind 단위
- DataSourceTransactionManager:
  * doBegin: getConnection + setAutoCommit(false) + bind
  * doCommit/Rollback: connection.commit/rollback + close + unbind
  * doSuspend/Resume: TSM bind/unbind만 (보관본 = ConnectionHolder)
- 테스트 5건: bind / commit (실제 INSERT 검증) / rollback (실제 미INSERT 검증)
  / requires_new (별도 connection) / autoCommit=false 박제

회귀: 206~207 → 211~212 PASS.
"
```

---

### Task C3: `JdbcTemplate` mini + `RowMapper` + 단위 테스트 4건

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/jdbc/RowMapper.java`
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/jdbc/JdbcTemplate.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/jdbc/JdbcTemplateTest.java`

**TDD 적용:** ✅ 적용 — `query`/`update` 본문, transaction-aware connection 분기.

- [x] **Step 1: 실패 테스트 작성 — `JdbcTemplateTest` 4건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/jdbc/JdbcTemplateTest.java`

```java
package com.choisk.sfs.tx.jdbc;

import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.support.ConnectionHolder;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTemplateTest {

    private DataSource dataSource;
    private ThreadLocalTsm tsm;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT, name VARCHAR(100))");
        }
        this.dataSource = ds;
        this.tsm = new ThreadLocalTsm();
        this.jdbc = new JdbcTemplate(dataSource, tsm);
    }

    @AfterEach
    void tearDown() {
        tsm.clearAll();
    }

    @Test
    void queryReturnsEmptyListWhenNoRows() {
        List<String> names = jdbc.query("SELECT name FROM t", (rs, i) -> rs.getString(1));

        assertThat(names).isEmpty();
    }

    @Test
    void updateInsertsRow() {
        int affected = jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");

        assertThat(affected).isEqualTo(1);
        List<String> names = jdbc.query("SELECT name FROM t WHERE id = ?", (rs, i) -> rs.getString(1), 1);
        assertThat(names).containsExactly("Alice");
    }

    @Test
    void queryWithMultipleRows() {
        jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");
        jdbc.update("INSERT INTO t VALUES (?, ?)", 2, "Bob");

        List<String> names = jdbc.query("SELECT name FROM t ORDER BY id", (rs, i) -> rs.getString(1));

        assertThat(names).containsExactly("Alice", "Bob");
    }

    @Test
    void usesBoundConnectionWhenInsideTransaction() {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource, tsm);
        tm.getTransaction(TransactionDefinition.required());

        // 트랜잭션 안에서 INSERT — TSM에 bind된 ConnectionHolder의 connection 사용
        jdbc.update("INSERT INTO t VALUES (?, ?)", 1, "Alice");

        // commit 전: TSM에 bind된 holder의 connection만 INSERT가 보임
        ConnectionHolder holder = (ConnectionHolder) tsm.getResource(dataSource);
        assertThat(holder).isNotNull();
        // commit 시 영구화
        tm.commit(tm.getTransaction(TransactionDefinition.required()));
        // (위 두 번째 getTransaction은 재진입 → join → status는 isNew=false. 명시적 commit으로 마무리는 학습 박제)

        List<String> names = jdbc.query("SELECT name FROM t", (rs, i) -> rs.getString(1));
        assertThat(names).containsExactly("Alice");
    }
}
```

- [x] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.jdbc.JdbcTemplateTest`
Expected: FAIL — `JdbcTemplate`/`RowMapper` 없음

- [x] **Step 3: `RowMapper` 인터페이스 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/jdbc/RowMapper.java`

```java
package com.choisk.sfs.tx.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code JdbcTemplate.query} 결과 row → 객체 변환 함수형 인터페이스.
 */
@FunctionalInterface
public interface RowMapper<T> {
    T mapRow(ResultSet rs, int rowNum) throws SQLException;
}
```

- [x] **Step 4: `JdbcTemplate` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/jdbc/JdbcTemplate.java`

```java
package com.choisk.sfs.tx.jdbc;

import com.choisk.sfs.tx.support.ConnectionHolder;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * mini JdbcTemplate. Spring 본가 {@code JdbcTemplate}의 query/update만 박제.
 *
 * <p>transaction-aware: TSM에 bind된 {@link ConnectionHolder}가 있으면 그 connection을 사용하고
 * close하지 않음 (TM이 close 책임). 없으면 {@link DataSource}에서 새로 가져와 finally close.
 */
public class JdbcTemplate {

    private final DataSource dataSource;
    private final TransactionSynchronizationManager tsm;

    public JdbcTemplate(DataSource dataSource, TransactionSynchronizationManager tsm) {
        this.dataSource = dataSource;
        this.tsm = tsm;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        ConnHandle h = obtainConnection();
        try (PreparedStatement ps = h.connection.prepareStatement(sql)) {
            bindArgs(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();
                int row = 0;
                while (rs.next()) {
                    result.add(mapper.mapRow(rs, row++));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + sql, e);
        } finally {
            h.releaseIfNotBound();
        }
    }

    public int update(String sql, Object... args) {
        ConnHandle h = obtainConnection();
        try (PreparedStatement ps = h.connection.prepareStatement(sql)) {
            bindArgs(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("update failed: " + sql, e);
        } finally {
            h.releaseIfNotBound();
        }
    }

    private ConnHandle obtainConnection() {
        ConnectionHolder bound = (ConnectionHolder) tsm.getResource(dataSource);
        if (bound != null) {
            return new ConnHandle(bound.getConnection(), false);
        }
        try {
            return new ConnHandle(dataSource.getConnection(), true);
        } catch (SQLException e) {
            throw new RuntimeException("getConnection failed", e);
        }
    }

    private static void bindArgs(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private record ConnHandle(Connection connection, boolean ownedByCaller) {
        void releaseIfNotBound() {
            if (ownedByCaller) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
```

- [x] **Step 5: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.jdbc.JdbcTemplateTest`
Expected: PASS — 4건

- [x] **Step 6: 회귀 확인**

Run: `./gradlew test`
Expected: 215~216 PASS / 0 FAIL (211~212 + 4)

- [x] **Step 7: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/jdbc/ sfs-tx/src/test/java/com/choisk/sfs/tx/jdbc/JdbcTemplateTest.java
git commit -m "feat(sfs-tx): JdbcTemplate mini + RowMapper + 단위 테스트 4건 — transaction-aware connection 박제

- RowMapper @FunctionalInterface (rs, rowNum)
- JdbcTemplate.query/update만 박제 (Spring 본가 1할)
- obtainConnection: TSM bind 있으면 그 connection 재사용 (close X) / 없으면 새로 getConnection (close O)
- 테스트 4건: 0건 query / update INSERT / 다중 row / 트랜잭션 내부 connection 재사용 검증

회귀: 211~212 → 215~216 PASS. transaction-aware의 본질 박제.
"
```

---

### Task C4: Order/AuditLog 도메인 + JDBC Repository 2종

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/domain/Order.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/domain/AuditLog.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/repository/OrderRepository.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/repository/AuditRepository.java`

**TDD 적용:** ❌ 제외 — record(데이터 컨테이너) + JdbcTemplate 위 얇은 래퍼. 통합 테스트(Task C6)로 간접 검증.

- [ ] **Step 1: `Order` record 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/domain/Order.java`

```java
package com.choisk.sfs.samples.order.domain;

public record Order(Long id, String item, int amount) {

    public static Order toCreate(String item, int amount) {
        return new Order(null, item, amount);
    }
}
```

- [ ] **Step 2: `AuditLog` record 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/domain/AuditLog.java`

```java
package com.choisk.sfs.samples.order.domain;

import java.time.Instant;

public record AuditLog(Long id, Instant occurredAt, String message) {

    public static AuditLog toCreate(Instant occurredAt, String message) {
        return new AuditLog(null, occurredAt, message);
    }
}
```

- [ ] **Step 3: `OrderRepository` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/repository/OrderRepository.java`

```java
package com.choisk.sfs.samples.order.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.order.domain.Order;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;

import java.util.List;

@Repository
public class OrderRepository {

    @Autowired private JdbcTemplate jdbc;

    public void save(Order order) {
        jdbc.update("INSERT INTO orders (item, amount) VALUES (?, ?)", order.item(), order.amount());
    }

    public List<Order> findAll() {
        return jdbc.query("SELECT id, item, amount FROM orders ORDER BY id",
                (rs, i) -> new Order(rs.getLong(1), rs.getString(2), rs.getInt(3)));
    }

    public long count() {
        return jdbc.query("SELECT COUNT(*) FROM orders", (rs, i) -> rs.getLong(1)).get(0);
    }
}
```

- [ ] **Step 4: `AuditRepository` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/repository/AuditRepository.java`

```java
package com.choisk.sfs.samples.order.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.order.domain.AuditLog;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class AuditRepository {

    @Autowired private JdbcTemplate jdbc;

    public void save(AuditLog log) {
        jdbc.update("INSERT INTO audit_log (occurred_at, message) VALUES (?, ?)",
                Timestamp.from(log.occurredAt()), log.message());
    }

    public List<AuditLog> findAll() {
        return jdbc.query("SELECT id, occurred_at, message FROM audit_log ORDER BY id",
                (rs, i) -> new AuditLog(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3)));
    }

    public long count() {
        return jdbc.query("SELECT COUNT(*) FROM audit_log", (rs, i) -> rs.getLong(1)).get(0);
    }
}
```

- [ ] **Step 5: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-samples:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 215~216 PASS / 0 FAIL (변동 없음, 통합 테스트는 Task C6)

- [ ] **Step 6: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/order/domain/ sfs-samples/src/main/java/com/choisk/sfs/samples/order/repository/
git commit -m "feat(sfs-samples): Order/AuditLog 도메인 + JDBC Repository 2종

- Order record (id/item/amount) + toCreate 정적 팩토리
- AuditLog record (id/occurredAt/message) + toCreate
- OrderRepository: save / findAll / count (JdbcTemplate 위 얇은 래퍼)
- AuditRepository: 동일 시그니처

통합 테스트(C6)에서 transaction propagation 시연으로 검증.
"
```

---

### Task C5: OrderService/AuditService/Controller + AppConfig 갱신 + TransactionDemoApplication

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/BusinessException.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/AuditService.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/OrderService.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/controller/OrderController.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/TransactionDemoApplication.java`
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java`

**TDD 적용:** ❌ 제외 — service/controller/main은 시연 시그니처. propagation 분기는 advice가 책임. 통합 테스트(Task C6)로 검증.

- [ ] **Step 1: `BusinessException` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/BusinessException.java`

```java
package com.choisk.sfs.samples.order.service;

public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
```

- [ ] **Step 2: `AuditService` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/AuditService.java`

```java
package com.choisk.sfs.samples.order.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.order.domain.AuditLog;
import com.choisk.sfs.samples.order.repository.AuditRepository;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditService {

    @Autowired private AuditRepository auditRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String message) {
        auditRepo.save(AuditLog.toCreate(Instant.now(), message));
    }
}
```

- [ ] **Step 3: `OrderService` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/service/OrderService.java`

```java
package com.choisk.sfs.samples.order.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.order.domain.Order;
import com.choisk.sfs.samples.order.repository.OrderRepository;
import com.choisk.sfs.tx.annotation.Transactional;

@Service
public class OrderService {

    @Autowired private OrderRepository orderRepo;
    @Autowired private AuditService auditService;

    @Transactional
    public void placeOrder(String item, int amount) {
        orderRepo.save(Order.toCreate(item, amount));
        auditService.log("order placed: " + item);
        if (amount > 100_000) {
            throw new BusinessException("amount limit exceeded: " + amount);
        }
    }
}
```

- [ ] **Step 4: `OrderController` 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/controller/OrderController.java`

```java
package com.choisk.sfs.samples.order.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.samples.order.service.BusinessException;
import com.choisk.sfs.samples.order.service.OrderService;

@Controller
public class OrderController {

    @Autowired private OrderService orderService;

    public void placeOrder(String item, int amount) {
        try {
            orderService.placeOrder(item, amount);
            System.out.println("[OrderController] order placed: " + item + " (amount=" + amount + ")");
        } catch (BusinessException e) {
            System.out.println("[OrderController] order failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: `AppConfig` 갱신 — DataSource + TM + JdbcTemplate + BPP `@Bean` 추가**

수정: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java` — 기존 빈에 추가

```java
package com.choisk.sfs.samples.todo.config;

// ... 기존 import 유지 ...
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.support.DataSourceTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Configuration
@ComponentScan(basePackages = {
        "com.choisk.sfs.samples.todo",
        "com.choisk.sfs.samples.order"
})
public class AppConfig {

    // ... 기존 @Bean들 (LoggingAspect, AspectEnhancingBeanPostProcessor 등) 유지 ...

    @Bean
    public DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sfs-demo;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        // schema.sql 직접 실행 — 학습 박제 (마이그레이션 도구 비목표, spec § 7)
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String schema = new String(getClass().getClassLoader()
                    .getResourceAsStream("schema.sql").readAllBytes());
            for (String stmt : schema.split(";")) {
                if (!stmt.trim().isEmpty()) s.execute(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException("schema.sql load failed", e);
        }
        return ds;
    }

    @Bean
    public TransactionSynchronizationManager tsm() {
        return new ThreadLocalTsm();
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource ds, TransactionSynchronizationManager tsm) {
        return new DataSourceTransactionManager(ds, tsm);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) {
        return new JdbcTemplate(ds, tsm);
    }

    @Bean
    public TransactionalBeanPostProcessor transactionalBeanPostProcessor() {
        return new TransactionalBeanPostProcessor();
    }
}
```

> *주의*: 기존 `@ComponentScan` 패키지에 `com.choisk.sfs.samples.order` 추가. 기존 `todo` 빈은 그대로 유지(spec § 3).

- [ ] **Step 6: `TransactionDemoApplication` main 작성**

생성: `sfs-samples/src/main/java/com/choisk/sfs/samples/order/TransactionDemoApplication.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.controller.OrderController;
import com.choisk.sfs.samples.todo.config.AppConfig;

public class TransactionDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            OrderController controller = ctx.getBean(OrderController.class);

            // 정상 흐름
            controller.placeOrder("Book", 5_000);
            // 예외 흐름 — outer rollback, inner audit는 살아남음
            controller.placeOrder("Yacht", 200_000);
        }
    }
}
```

- [ ] **Step 7: 컴파일 + 회귀 확인**

Run: `./gradlew :sfs-samples:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 215~216 PASS / 0 FAIL (변동 없음 — main 추가만, 테스트는 C6)

- [ ] **Step 8: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/order/ sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java
git commit -m "feat(sfs-samples): Order/Audit 시연 도메인 service/controller + AppConfig + TransactionDemoApplication

- BusinessException (RuntimeException) — rollback 트리거
- AuditService (@Transactional REQUIRES_NEW) — log
- OrderService (@Transactional REQUIRED) — placeOrder + 100_000 한도 BusinessException
- OrderController — placeOrder + 출력 박제
- AppConfig 갱신: H2 DataSource + ThreadLocalTsm + DataSourceTransactionManager + JdbcTemplate + TransactionalBeanPostProcessor 5개 @Bean
- @ComponentScan에 'order' 패키지 추가 (기존 todo 유지)
- TransactionDemoApplication.main: 정상(Book) + 예외(Yacht) 시연 진입점

통합 시연 박제는 C6.
"
```

---

### Task C6: 통합 시연 박제 — `TransactionPropagationIntegrationTest` 6건 + `TransactionDemoApplicationTest` 2건

**Files:**
- Create: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionPropagationIntegrationTest.java`
- Create: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionDemoApplicationTest.java`

**TDD 적용:** ✅ 적용 — 시연 박제. 콘솔 출력 + DB 상태 어서션 모두.

- [ ] **Step 1: 실패 테스트 작성 — `TransactionPropagationIntegrationTest` 6건**

생성: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionPropagationIntegrationTest.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.repository.AuditRepository;
import com.choisk.sfs.samples.order.repository.OrderRepository;
import com.choisk.sfs.samples.order.service.BusinessException;
import com.choisk.sfs.samples.order.service.OrderService;
import com.choisk.sfs.samples.todo.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionPropagationIntegrationTest {

    private AnnotationConfigApplicationContext ctx;
    private OrderService orderService;
    private OrderRepository orderRepo;
    private AuditRepository auditRepo;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        orderService = ctx.getBean(OrderService.class);
        orderRepo = ctx.getBean(OrderRepository.class);
        auditRepo = ctx.getBean(AuditRepository.class);

        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM audit_log");
        }
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Test
    void successfulOrderInsertsBothOrderAndAudit() {
        orderService.placeOrder("Book", 5_000);

        assertThat(orderRepo.count()).isEqualTo(1L);
        assertThat(auditRepo.count()).isEqualTo(1L);
    }

    @Test
    void failingOrderRollsBackOrderButAuditSurvives() {
        assertThatThrownBy(() -> orderService.placeOrder("Yacht", 200_000))
                .isInstanceOf(BusinessException.class);

        // outer rollback: orders 0행
        assertThat(orderRepo.count()).isEqualTo(0L);
        // inner REQUIRES_NEW commit: audit_log 1행 (정점)
        assertThat(auditRepo.count()).isEqualTo(1L);
    }

    @Test
    void multipleOrdersAccumulate() {
        orderService.placeOrder("Book", 5_000);
        orderService.placeOrder("Pen", 1_000);

        assertThat(orderRepo.count()).isEqualTo(2L);
        assertThat(auditRepo.count()).isEqualTo(2L);
    }

    @Test
    void mixedSuccessAndFailure() {
        orderService.placeOrder("Book", 5_000);
        try { orderService.placeOrder("Yacht", 200_000); } catch (BusinessException ignored) {}
        orderService.placeOrder("Pen", 1_000);

        assertThat(orderRepo.count()).isEqualTo(2L);  // Book, Pen만
        assertThat(auditRepo.count()).isEqualTo(3L);  // 3건 모두 audit
    }

    @Test
    void auditMessageContainsItemName() {
        orderService.placeOrder("Book", 5_000);

        assertThat(auditRepo.findAll().get(0).message()).contains("Book");
    }

    @Test
    void rolledBackOrderIsNotPersistedEvenAcrossNewConnection() throws Exception {
        try { orderService.placeOrder("Yacht", 200_000); } catch (BusinessException ignored) {}

        // 새 connection으로 직접 조회 — TM이 정말 rollback했는지 검증
        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }
}
```

- [ ] **Step 2: 실패 테스트 작성 — `TransactionDemoApplicationTest` 2건**

생성: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionDemoApplicationTest.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.order.controller.OrderController;
import com.choisk.sfs.samples.todo.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionDemoApplicationTest {

    private AnnotationConfigApplicationContext ctx;
    private ByteArrayOutputStream stdout;
    private PrintStream original;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        DataSource ds = ctx.getBean(DataSource.class);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM orders");
            s.execute("DELETE FROM audit_log");
        }
        stdout = new ByteArrayOutputStream();
        original = System.out;
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void tearDown() {
        System.setOut(original);
        if (ctx != null) ctx.close();
    }

    @Test
    void successScenarioOutputsOrderPlacedLine() {
        OrderController controller = ctx.getBean(OrderController.class);

        controller.placeOrder("Book", 5_000);

        String log = stdout.toString();
        assertThat(log).contains("[OrderController] order placed: Book");
        assertThat(log).doesNotContain("order failed");
    }

    @Test
    void failureScenarioOutputsOrderFailedLineAndAuditSurvives() {
        OrderController controller = ctx.getBean(OrderController.class);

        controller.placeOrder("Yacht", 200_000);

        String log = stdout.toString();
        assertThat(log).contains("[OrderController] order failed");
        assertThat(log).contains("amount limit exceeded");

        // audit는 살아남음 (REQUIRES_NEW)
        var auditRepo = ctx.getBean(com.choisk.sfs.samples.order.repository.AuditRepository.class);
        assertThat(auditRepo.count()).isEqualTo(1L);
    }
}
```

- [ ] **Step 3: 테스트 실행 — FAIL/PASS 확인**

Run: `./gradlew :sfs-samples:test --tests com.choisk.sfs.samples.order.TransactionPropagationIntegrationTest`
Expected: PASS — 6건 (인프라가 모두 갖춰진 상태)

Run: `./gradlew :sfs-samples:test --tests com.choisk.sfs.samples.order.TransactionDemoApplicationTest`
Expected: PASS — 2건

> 본 task는 Step 1/2가 *시연 박제*라서 RED 단계 없이 바로 GREEN 가능. 인프라는 Task A1~C5에서 모두 완성됨.

- [ ] **Step 4: 회귀 확인**

Run: `./gradlew test`
Expected: 223~224 PASS / 0 FAIL (215~216 + 8)

- [ ] **Step 5: 커밋**

```bash
git add sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionPropagationIntegrationTest.java sfs-samples/src/test/java/com/choisk/sfs/samples/order/TransactionDemoApplicationTest.java
git commit -m "test(sfs-samples): 통합 시연 박제 — propagation 6건 + demo 2건

TransactionPropagationIntegrationTest 6건:
- 정상: orders+audit 1건씩
- 실패(BusinessException): orders 0건 / audit 1건 (REQUIRES_NEW 정점)
- 다중 누적, 혼합 성공/실패, audit 메시지 검증
- 새 connection 직접 조회로 rollback 검증

TransactionDemoApplicationTest 2건:
- 정상 시나리오 콘솔 'order placed: Book' 출력
- 실패 시나리오 'order failed' + audit 살아남음

회귀: 215~216 → 223~224 PASS. spec § 4.2/4.3 시연 박제 완성.
"
```

---

## 섹션 D: ScopedValue 비교 박제 + Phase 2B 보류 흡수 통합

### Task D1: `ScopedValueTsm` + 단위 테스트 3건 + `TsmComparisonTest` 비교 박제 3건

**Files:**
- Create: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ScopedValueTsm.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/ScopedValueTsmTest.java`
- Create: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/TsmComparisonTest.java`

**TDD 적용:** ✅ 적용 — Java 25 `ScopedValue` API 활용. immutable 제약 + 우회 가변 영역 패턴이 본질.

> **학습 박제 의도** (spec § 4.5): ScopedValueTsm은 *완전한 대체*가 아닌 *비교 학습용*. ScopedValue.where(...).run(...) 패턴은 람다 스코프 안에서만 살아있어, transaction interceptor에서 채택하기 부적합. 따라서 본 task는 *동일 scope 내 동작*만 박제.

- [ ] **Step 1: 실패 테스트 작성 — `ScopedValueTsmTest` 3건**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/ScopedValueTsmTest.java`

```java
package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopedValueTsmTest {

    private final ScopedValueTsm tsm = new ScopedValueTsm();

    @Test
    void bindAndGetWithinScope() {
        Object key = new Object();

        tsm.runInScope(() -> {
            tsm.bindResource(key, "conn#1");
            assertThat(tsm.getResource(key)).isEqualTo("conn#1");
        });
    }

    @Test
    void bindOutsideScopeThrows() {
        assertThatThrownBy(() -> tsm.bindResource(new Object(), "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in scope");
    }

    @Test
    void scopesAreNestedAndIsolated() {
        Object key = new Object();

        tsm.runInScope(() -> {
            tsm.bindResource(key, "outer-conn");

            tsm.runInScope(() -> {
                // nested scope — 새 map. outer 값은 보이지 않음 (immutable scope)
                assertThat(tsm.getResource(key)).isNull();
                tsm.bindResource(key, "inner-conn");
                assertThat(tsm.getResource(key)).isEqualTo("inner-conn");
            });

            // outer 복원 (자동 — scope 종료 시)
            assertThat(tsm.getResource(key)).isEqualTo("outer-conn");
        });
    }
}
```

- [ ] **Step 2: 테스트 실행 — FAIL 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.ScopedValueTsmTest`
Expected: FAIL — `ScopedValueTsm` 없음

- [ ] **Step 3: `ScopedValueTsm` 구현 작성**

생성: `sfs-tx/src/main/java/com/choisk/sfs/tx/support/ScopedValueTsm.java`

```java
package com.choisk.sfs.tx.support;

import java.util.HashMap;
import java.util.Map;

/**
 * Java 25 {@link ScopedValue} (JEP 506) 기반 TSM. {@link ThreadLocalTsm}과 *동일 인터페이스*이지만
 * scope 모델이 본질적으로 다름.
 *
 * <h3>박제 의도 (spec § 4.5)</h3>
 * <ul>
 *   <li>ScopedValue 자체는 *immutable* — bind/unbind 같은 추가 등록은 직접 불가</li>
 *   <li>우회 가변 영역 패턴: {@code ScopedValue<Map<Object, Object>>} — 외곽 ScopedValue는 immutable이지만 내부 Map은 가변</li>
 *   <li>nested scope는 자동 복원 — {@code where(...).run(...)} 종료 시 outer scope로 자동 복귀</li>
 *   <li>{@code transaction interceptor}가 채택하기엔 람다 스코프 강제로 부적합 — 이게 *왜 Spring이 ThreadLocal을 쓰는가*의 박제</li>
 * </ul>
 *
 * <p>{@link #bindResource}/{@link #getResource}/{@link #unbindResource}는 *현재 scope 안에서*만 의미가 있음.
 * scope 밖에서 호출 시 {@link IllegalStateException}.
 *
 * @see ThreadLocalTsm
 */
public class ScopedValueTsm implements TransactionSynchronizationManager {

    private static final ScopedValue<Map<Object, Object>> SLOT = ScopedValue.newInstance();

    /**
     * 새 scope를 열고 람다를 실행한다. 람다 안에서 {@link #bindResource} 등이 동작.
     * 람다 종료 시 scope 자동 복원.
     */
    public void runInScope(Runnable r) {
        ScopedValue.where(SLOT, new HashMap<>()).run(r);
    }

    @Override
    public void bindResource(Object key, Object value) {
        currentMap().put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        if (!SLOT.isBound()) return null;
        return SLOT.get().get(key);
    }

    @Override
    public Object unbindResource(Object key) {
        return currentMap().remove(key);
    }

    @Override
    public void clearAll() {
        // ScopedValue는 외부 명시적 clear 불가 — scope 종료 시 자동
        if (SLOT.isBound()) {
            SLOT.get().clear();
        }
    }

    private Map<Object, Object> currentMap() {
        if (!SLOT.isBound()) {
            throw new IllegalStateException("not in scope — call runInScope first");
        }
        return SLOT.get();
    }
}
```

- [ ] **Step 4: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.ScopedValueTsmTest`
Expected: PASS — 3건

- [ ] **Step 5: 실패 테스트 작성 — `TsmComparisonTest` 3건 (비교 박제)**

생성: `sfs-tx/src/test/java/com/choisk/sfs/tx/support/TsmComparisonTest.java`

```java
package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ThreadLocal과 ScopedValue 두 TSM 구현체의 *동일 동작*과 *결정적 차이* 박제.
 *
 * spec § 4.5의 표를 테스트로 명시화 — 학습 정점 박제.
 */
class TsmComparisonTest {

    @Test
    void bothImplementationsBindAndRetrieveSameValue() {
        Object key = new Object();

        // ThreadLocal
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "v1");
        Object tlValue = tlTsm.getResource(key);
        tlTsm.clearAll();

        // ScopedValue
        ScopedValueTsm svTsm = new ScopedValueTsm();
        Object[] svValueRef = new Object[1];
        svTsm.runInScope(() -> {
            svTsm.bindResource(key, "v1");
            svValueRef[0] = svTsm.getResource(key);
        });

        // 동일 동작
        assertThat(tlValue).isEqualTo("v1");
        assertThat(svValueRef[0]).isEqualTo("v1");
    }

    @Test
    void threadLocalAllowsBindWithoutScope_butScopedValueDoesNot() {
        Object key = new Object();

        // ThreadLocal: scope 없이도 bind 가능 — 가변 ThreadLocal map
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "v1");
        assertThat(tlTsm.getResource(key)).isEqualTo("v1");
        tlTsm.clearAll();

        // ScopedValue: scope 밖에서는 IllegalStateException — immutable 제약 박제
        ScopedValueTsm svTsm = new ScopedValueTsm();
        assertThatThrownBy(() -> svTsm.bindResource(key, "v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopedValueAutoRestoresOuterScope_butThreadLocalRequiresExplicitUnbind() {
        Object key = new Object();

        // ThreadLocal: nested binding은 *덮어쓰기*. unbind 없이는 outer 복원 안 됨
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "outer");
        tlTsm.bindResource(key, "inner");  // 덮어쓰기
        assertThat(tlTsm.getResource(key)).isEqualTo("inner");  // outer 사라짐
        tlTsm.clearAll();

        // ScopedValue: nested scope는 *자동 격리* + scope 종료 시 outer 자동 복원
        ScopedValueTsm svTsm = new ScopedValueTsm();
        Object[] outerAfterInner = new Object[1];
        svTsm.runInScope(() -> {
            svTsm.bindResource(key, "outer");
            svTsm.runInScope(() -> {
                svTsm.bindResource(key, "inner");
                // inner scope에서는 outer 안 보임
                assertThat(svTsm.getResource(key)).isEqualTo("inner");
            });
            // 자동 복원
            outerAfterInner[0] = svTsm.getResource(key);
        });

        assertThat(outerAfterInner[0]).isEqualTo("outer");
    }
}
```

- [ ] **Step 6: 테스트 실행 — PASS 확인**

Run: `./gradlew :sfs-tx:test --tests com.choisk.sfs.tx.support.TsmComparisonTest`
Expected: PASS — 3건

- [ ] **Step 7: 회귀 확인**

Run: `./gradlew test`
Expected: 229~230 PASS / 0 FAIL (223~224 + 6)

- [ ] **Step 8: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/support/ScopedValueTsm.java sfs-tx/src/test/java/com/choisk/sfs/tx/support/{ScopedValueTsmTest,TsmComparisonTest}.java
git commit -m "feat(sfs-tx): ScopedValueTsm + 단위 테스트 3건 + TsmComparisonTest 비교 박제 3건

- ScopedValueTsm: ScopedValue<Map<Object,Object>> — 우회 가변 영역 패턴
- runInScope(Runnable) — 명시 scope 진입 (ThreadLocal과 결정적 차이)
- 단위 테스트 3건: scope 내 bind+get / scope 밖 IllegalStateException / nested scope 자동 격리+복원
- TsmComparisonTest 3건: 동일 동작 / scope 강제 차이 / nested 복원 차이

학습 박제 정점 (spec § 4.5): ScopedValue가 *왜 transaction interceptor에 부적합한가* 의 직접 시연.

회귀: 223~224 → 229~230 PASS. 의사결정 #11 (Phase 3+ ScopedValue 옵션) 약속 회수.
"
```

---

### Task D2: `EarlyReferenceIntegrationTest` 2건 (A-2 흡수) + `EnhancedBeanDestroyTest` 1건 (C-3 흡수)

**Files:**
- Create: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/EarlyReferenceIntegrationTest.java`
- Create: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/EnhancedBeanDestroyTest.java`

**TDD 적용:** ✅ 적용 — A-2/C-3 흡수의 통합 검증.

> **편차 가능성 박제**: A-2 (early reference) 처리는 Phase 1A `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference` 훅 활용 (spec § 5.4). 본 task의 통합 테스트가 *순환 의존*에서 transactional advice가 정확히 적용되는지 검증. **Phase 1A SIABPP 인터페이스가 이미 구현되어 있고 BPP가 그 훅을 implement하면 충분**할 것으로 예상.
>
> 만약 Phase 1A에 `getEarlyBeanReference` 훅이 *인터페이스만 박제되어 있고 호출 회로가 없다*면 별도 task로 분리 — 실행 단계에서 판단해 Plan에 박제.

- [ ] **Step 1: A-2 흡수를 위한 BPP 보강 (사전 확인)**

먼저 현재 Phase 1A SIABPP 호출 회로를 확인:

Run: `grep -rn "getEarlyBeanReference" sfs-beans/ sfs-context/`

Expected: SIABPP 인터페이스 + DefaultListableBeanFactory(또는 AbstractBeanFactory)에 호출 회로가 있음.

> **편차 박제 (실행 단계)**: 호출 회로가 없으면 Plan에 *별도 task D1.5 신설* 후 진행 — Phase 1A SIABPP 활용을 위한 회로 보강. 본 plan은 *호출 회로가 이미 있다*는 가정으로 작성.

`TransactionalBeanPostProcessor`에 SIABPP 구현 추가 (Task B3 산출물 보강):

수정: `sfs-tx/src/main/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessor.java` — `implements ... SmartInstantiationAwareBeanPostProcessor` 추가 + 메서드 override

```java
// 클래스 선언 변경
public class TransactionalBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {
    // ... 기존 필드/메서드 유지 ...

    /**
     * Phase 1A SIABPP 훅 — 순환 의존 시 *enhance된 early reference* 반환.
     * 3-level cache의 첫 의미 있는 사용 (A-2 흡수, spec § 5.4).
     */
    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        if (bean instanceof BeanPostProcessor) return bean;
        if (!hasTransactionalMethod(bean.getClass())) return bean;
        try {
            return enhance(bean);
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Failed to enhance early reference", e);
        }
    }
}
```

- [ ] **Step 2: 실패 테스트 작성 — `EarlyReferenceIntegrationTest` 2건**

생성: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/EarlyReferenceIntegrationTest.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2B A-2 흡수 검증 (spec § 5.4): 순환 의존 빈에 transactional advice가 누락되지 않음.
 *
 * Phase 1A 3-level cache의 SIABPP getEarlyBeanReference 훅이 처음 의미 있게 사용됨.
 */
class EarlyReferenceIntegrationTest {

    @Test
    void circularDependencyAppliesTransactionalAdvice() {
        try (var ctx = new AnnotationConfigApplicationContext(CircularConfig.class)) {
            CircularA a = ctx.getBean(CircularA.class);
            CircularB b = ctx.getBean(CircularB.class);

            // 두 빈 모두 enhance되었는지 — getClass()가 원본과 다름
            assertThat(a.getClass()).isNotEqualTo(CircularA.class);
            assertThat(b.getClass()).isNotEqualTo(CircularB.class);

            // 실제 호출도 advice 적용 (BeanFactory 주입을 통한 호출에서도)
            a.callViaInjected();
            b.callViaInjected();
        }
    }

    @Test
    void earlyReferenceFromBSeesEnhancedA() {
        try (var ctx = new AnnotationConfigApplicationContext(CircularConfig.class)) {
            CircularB b = ctx.getBean(CircularB.class);

            // B 안에 주입된 A (early reference)도 enhance된 인스턴스여야 함
            CircularA injectedA = b.getInjectedA();
            assertThat(injectedA.getClass()).isNotEqualTo(CircularA.class);
        }
    }

    @Configuration
    static class CircularConfig {
        @Bean public TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
        @Bean public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }
        @Bean public TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
        @Bean public CircularA a() { return new CircularA(); }
        @Bean public CircularB b() { return new CircularB(); }
    }

    @Service
    public static class CircularA {
        @Autowired CircularB b;
        @Transactional public void callViaInjected() { b.doSomething(); }
        @Transactional public void doSomething() { /* nothing */ }
    }

    @Service
    public static class CircularB {
        @Autowired CircularA a;
        public CircularA getInjectedA() { return a; }
        @Transactional public void callViaInjected() { a.doSomething(); }
        @Transactional public void doSomething() { /* nothing */ }
    }
}
```

- [ ] **Step 3: 실패 테스트 작성 — `EnhancedBeanDestroyTest` 1건**

생성: `sfs-samples/src/test/java/com/choisk/sfs/samples/order/EnhancedBeanDestroyTest.java`

```java
package com.choisk.sfs.samples.order;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.annotation.Transactional;
import com.choisk.sfs.tx.boot.TransactionalBeanPostProcessor;
import com.choisk.sfs.tx.support.MockTransactionManager;
import com.choisk.sfs.tx.support.ThreadLocalTsm;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2B C-3 흡수 검증 (spec § 5.4): enhanced(byte-buddy 서브클래스) 트랜잭셔널 빈도
 * close() 시 @PreDestroy 콜백이 정상 호출됨.
 *
 * <p>Phase 2B AOP 인프라가 enhanced 빈에 destroy 트리거를 잃지 않음을 확인 — Phase 1B-β destroy
 * 인프라와 Phase 2B/3 enhance 경로의 교차 검증.
 */
class EnhancedBeanDestroyTest {

    @Test
    void preDestroyOnEnhancedTransactionalBean() {
        EnhancedDestroyConfig config;
        try (var ctx = new AnnotationConfigApplicationContext(EnhancedDestroyConfig.class)) {
            DestroyTrackingService bean = ctx.getBean(DestroyTrackingService.class);

            // enhance 확인
            assertThat(bean.getClass()).isNotEqualTo(DestroyTrackingService.class);

            // ctx.close()가 try-with-resources 종료 시 호출됨 → @PreDestroy 트리거
            config = ctx.getBean(EnhancedDestroyConfig.class);
        }

        // 다른 ctx가 같은 config를 공유하지 않으므로, 직접 destroyed 플래그 확인은 어렵다.
        // 대신: 다음 test infra 패턴으로 검증 — 정적 카운터 사용
        assertThat(DestroyTrackingService.destroyCount).isGreaterThan(0);
    }

    @Configuration
    static class EnhancedDestroyConfig {
        @Bean public TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
        @Bean public PlatformTransactionManager tm(TransactionSynchronizationManager tsm) {
            return new MockTransactionManager(tsm);
        }
        @Bean public TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
        @Bean public DestroyTrackingService destroyTracking() { return new DestroyTrackingService(); }
    }

    @Service
    public static class DestroyTrackingService {
        public static int destroyCount = 0;

        @Transactional public void doWork() { /* nothing */ }

        @PreDestroy
        public void cleanup() {
            destroyCount++;
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — FAIL/PASS 확인**

Run: `./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.order.EarlyReferenceIntegrationTest" --tests "com.choisk.sfs.samples.order.EnhancedBeanDestroyTest"`
Expected:
- A-2 (`EarlyReferenceIntegrationTest`): SIABPP 회로가 정상이면 PASS, 아니면 FAIL → Step 1의 회로 보강 또는 별도 task 추가
- C-3 (`EnhancedBeanDestroyTest`): byte-buddy 서브클래스에서도 `@PreDestroy` 호출되면 PASS

> **실행 기록 박제 필수:** A-2 결과에 따라 Plan에 *편차 박제* 추가. C-3는 Phase 2B AOP에서 이미 검증된 패턴이라 PASS 예상.

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew test`
Expected: 232~233 PASS / 0 FAIL (229~230 + 3)

- [ ] **Step 6: 커밋**

```bash
git add sfs-tx/src/main/java/com/choisk/sfs/tx/boot/TransactionalBeanPostProcessor.java sfs-samples/src/test/java/com/choisk/sfs/samples/order/{EarlyReferenceIntegrationTest,EnhancedBeanDestroyTest}.java
git commit -m "test(sfs-samples): Phase 2B 보류 finding A-2/C-3 흡수 통합 테스트

A-2 (early reference, EarlyReferenceIntegrationTest 2건):
- TransactionalBeanPostProcessor에 SmartInstantiationAwareBeanPostProcessor 구현 추가
- getEarlyBeanReference 훅: 순환 의존 시 enhance된 early reference 반환
- Phase 1A 3-level cache 자산이 처음 의미 있게 사용됨

C-3 (enhanced destroy, EnhancedBeanDestroyTest 1건):
- @PreDestroy가 byte-buddy 서브클래스에서도 정상 호출 검증
- Phase 1B-β destroy 인프라 + Phase 2B/3 enhance 경로 교차 검증

회귀: 229~230 → 232~233 PASS. spec § 5.4의 4건 흡수 박제 (A-1/A-2/A-3/C-3 중 3건). B-2는 B2 task에서 흡수.
"
```

---

## 섹션 E: 시연 통합 + 문서 + 마감 게이트

### Task E1: README 신설 + `sfs-samples/README.md` 갱신 + DoD 갱신

**Files:**
- Create: `sfs-tx/README.md`
- Modify: `sfs-samples/README.md`
- Modify: `docs/superpowers/plans/2026-04-30-phase-3-transaction.md` (DoD 체크박스)

**TDD 적용:** ❌ 제외 — 문서.

- [ ] **Step 1: `sfs-tx/README.md` 신설**

생성: `sfs-tx/README.md`

```markdown
# sfs-tx

Spring From Scratch의 **트랜잭션 추상화 모듈** (Phase 3). Spring 본가 `spring-tx` 대응.

## 의존 관계

- 내부: `sfs-aop` (transitive로 `sfs-context`, `sfs-beans`, `sfs-core`)
- 외부: byte-buddy 1.14 (BPP enhance), H2 2.2.x (test/runtime)

## 핵심 컴포넌트

### 애노테이션 + enum
- `@Transactional` — 메서드/클래스에 트랜잭션 경계 부여
- `Propagation` — REQUIRED, REQUIRES_NEW (5종 미지원, spec § 7 한계)

### 추상 인프라
- `PlatformTransactionManager` — 모든 TM의 공통 인터페이스
- `TransactionStatus` — 현재 트랜잭션 상태 핸들
- `TransactionDefinition` (record) — 트랜잭션 시작 시 의도
- `AbstractPlatformTransactionManager` — propagation 분기 알고리즘 추상 골격

### TM 구현
- `MockTransactionManager` — 콘솔 출력만 (학습 박제)
- `DataSourceTransactionManager` — JDBC `Connection` 기반

### TSM 구현 2종 (의사결정 #11)
- `ThreadLocalTsm` — 메인 (Spring 본가 정합)
- `ScopedValueTsm` — Java 25 idiom 비교 박제

### advice + BPP
- `TransactionInterceptor` — `@Transactional` advice 본체
- `TransactionalBeanPostProcessor` — byte-buddy enhance + SIABPP `getEarlyBeanReference` 훅 활용

### JDBC
- `JdbcTemplate` mini — query/update + transaction-aware connection

## 사용법

```java
@Configuration
public class AppConfig {
    @Bean DataSource dataSource() { /* JdbcDataSource */ }
    @Bean TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
    @Bean PlatformTransactionManager tm(DataSource ds, TransactionSynchronizationManager tsm) {
        return new DataSourceTransactionManager(ds, tsm);
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) {
        return new JdbcTemplate(ds, tsm);
    }
    @Bean TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
}

@Service
class OrderService {
    @Autowired OrderRepository repo;

    @Transactional
    public void placeOrder(...) { ... }
}
```

## 한계 (본 phase 의도된 단순화)

자세한 내용은 `docs/superpowers/specs/2026-04-30-phase-3-transaction-design.md` § 7 참조.

- propagation 5종 미지원 (NESTED, SUPPORTS 등)
- isolation 동작 검증 없음 (시그니처만)
- `rollbackFor` 동작 미구현 (시그니처만)
- `TransactionTemplate` (programmatic) 미도입
- timeout, readOnly 미지원
- JTA / 분산 트랜잭션 미지원
- HikariCP 등 connection pool 미사용

## 학습 가치

본 phase의 정점은 *추상화 인터페이스의 교환 가능성*:
- 같은 `@Transactional` advice가 두 TM 구현체(Mock/DataSource)와 두 TSM 구현체(ThreadLocal/ScopedValue)에서 동일하게 작동.
- ScopedValueTsm의 *immutable 제약*이 Spring이 *왜 ThreadLocal을 쓰는가*를 직접 박제.
- Phase 2B AOP 인프라(BPP/byte-buddy/advice 패턴)가 *transaction에서 자연 회수*.
- Phase 1A 3-level cache의 SIABPP 훅이 *처음 의미 있게 사용*됨 (A-2 흡수).

## 실행

```bash
./gradlew :sfs-tx:test
./gradlew :sfs-samples:test
./gradlew build
```
```

- [ ] **Step 2: `sfs-samples/README.md` 갱신**

수정: `sfs-samples/README.md` — Phase 2B 갱신 사항 섹션 아래에 추가

```markdown
## Phase 3 갱신 사항

- **신규 도메인** — `Order`, `AuditLog` (JDBC 기반). 기존 `Todo`, `User`(in-memory)는 그대로 유지 (의사결정 #3, 두 패러다임 공존).
- **새 진입점** — `TransactionDemoApplication.main()`. 정상 시연(Book) + 예외 시연(Yacht, BusinessException).
- **AppConfig 갱신** — DataSource(H2), ThreadLocalTsm, DataSourceTransactionManager, JdbcTemplate, TransactionalBeanPostProcessor 5개 `@Bean` 추가. `@ComponentScan`에 `order` 패키지 추가.
- **시연 마일스톤**:
  * 정상: `[OrderController] order placed: Book` + orders 1행 + audit 1행
  * 예외: `[OrderController] order failed: amount limit exceeded` + orders **0행** + audit **1행** (REQUIRES_NEW 정점)

## 시연 마일스톤 (누적)

- Phase 1C: Todo 기본 시연 (in-memory)
- Phase 2A: ConfigurationEnhanceDemo (false → true)
- Phase 2B: LoggingAspect (8 → 11 라인 콘솔 어서션)
- **Phase 3**: TransactionDemoApplication (orders/audit_log DB 어서션, REQUIRES_NEW 정점)
```

- [ ] **Step 3: Plan 문서의 DoD 체크박스 갱신**

수정: `docs/superpowers/plans/2026-04-30-phase-3-transaction.md` — § "Definition of Done" 섹션의 모든 항목을 `- [x]`로 갱신.

- [ ] **Step 4: 회귀 + 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 232~233 PASS / 0 FAIL

- [ ] **Step 5: 커밋**

```bash
git add sfs-tx/README.md sfs-samples/README.md docs/superpowers/plans/2026-04-30-phase-3-transaction.md
git commit -m "docs: Phase 3 README + Plan DoD 갱신 — sfs-tx 학습 가치 박제

sfs-tx/README.md 신설:
- 의존 관계 + 핵심 컴포넌트 + 사용법 + 한계 + 학습 가치 + 실행

sfs-samples/README.md 갱신:
- Phase 3 갱신 사항: 신규 Order/AuditLog 도메인 + TransactionDemoApplication
- 시연 마일스톤 누적: Phase 1C → 2A → 2B → 3

Plan DoD 26항목 모두 [x]. 회귀: 232~233 PASS / 0 FAIL.
"
```

---

### Task E2: 마감 게이트 (다관점 리뷰 + 리팩토링 + simplify 패스)

**Files:** (실행 단계에서 식별)
- Modify: 마감 리뷰에서 즉시 고칠 finding의 산출물
- Modify: `docs/superpowers/plans/2026-04-30-phase-3-transaction.md` § "품질 게이트 기록"

**TDD 적용:** N/A — 마감 게이트는 plan 외 의식 (CLAUDE.md "완료 후 품질 게이트").

- [ ] **Step 1: 다관점 코드리뷰 (옵션 C — 병행 reviewer)**

Phase 2B 마감 게이트 패턴 그대로 (메모리 `project_phase2b_resume_point.md` 박제):
- `general-purpose` Sonnet 5관점 통합 reviewer (아키텍처/가독성/테스트/동시성/보안)
- `feature-dev:code-reviewer` 자동 스캔
- 두 reviewer 병렬 dispatch

결과 분류: HIGH (즉시 고칠) / MED (별도 phase 또는 영구 박제) / LOW (남겨둘).

- [ ] **Step 2: 즉시 고칠 finding 반영 (HIGH)**

각 finding 단위 분리 커밋. 동작 변경 없는 리팩토링은 `refactor(<module>): ...`, 동작 변경은 `fix(<module>): ...`.

각 커밋마다:
- 해당 모듈 회귀 테스트 PASS 유지 검증
- 새 테스트 추가 시 별도 `test(<module>): ...` 커밋

- [ ] **Step 3: `/simplify` 패스 (3 agent 병렬)**

`simplify` 스킬 + 3 agent 병렬 dispatch (재사용 / 품질 / 효율). Phase 2B 패턴 그대로:
- 전수 수용 금지 — 학습 가치 vs simplify 가치 충돌 finding은 보류 결정 박제
- 반영분은 `refactor(<module>): ...` 또는 `chore: ...` 커밋

- [ ] **Step 4: 품질 게이트 기록 박제**

수정: 본 plan § "품질 게이트 기록" 섹션 추가. Phase 2B의 § 13 형식 그대로:
- 1단계: 다관점 리뷰 결과 (HIGH N건 / MED M건 / LOW L건)
- 2단계: 즉시 고칠 N건 반영 (커밋 hash + 한 줄 요약)
- 3단계: simplify 패스 (반영율 % + 보류 사유)
- 회귀 카운트 (마감 게이트 종료)
- 결과 (지적 N건 / 반영 M건 / 보류 K건)

- [ ] **Step 5: 최종 빌드 + 회귀 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 232~240 PASS (마감 게이트 추가분 포함) / 0 FAIL

- [ ] **Step 6: 마감 커밋**

```bash
git add docs/superpowers/plans/2026-04-30-phase-3-transaction.md
git commit -m "docs: Plan § 품질 게이트 기록 박제 + DoD 26번 [x] — 마감 게이트 통과

마감 게이트 3단계 종료:
- 1단계 다관점 리뷰: HIGH N / MED M / LOW L
- 2단계 즉시 고칠 N건 반영
- 3단계 simplify 반영 M건 / 보류 K건

회귀: 232~233 PASS → ~233~240 PASS. main 머지 진입 준비 완료.
"
```

---

## 7. 마감 후 작업

본 plan 모든 task 완료 + 마감 게이트 통과 후:

1. **main 머지** — `feat/phase3-transaction` → main `--no-ff` 또는 GitHub PR (Phase 2B 동일 패턴).
2. **메모리 박제** — `project_phase3_resume_point.md` 신설, 다음 phase 후보(Phase 4 JPA) 박제.
3. **Phase 4 (JPA 핵심) brainstorming** — 본 phase 산출물(`PlatformTransactionManager`, `DataSourceTransactionManager`, `JdbcTemplate`, `TransactionSynchronizationManager`) 위에 영속성 컨텍스트 + EntityManager 도입.

---

## 8. 회귀 카운트 추정 vs 실제

| Task | 추정 | 실제 (실행 단계 박제) |
|---|---|---|
| A1 (모듈 신설) | +0 | — |
| A2 (시그니처) | +0 | — |
| A3 (추상 골격) | +0 | — |
| A4 (TSM + ThreadLocalTsm) | +4 | — |
| B1 (Mock TM) | +4 | — |
| B2 (TransactionInterceptor) | +6 | — |
| B3 (BPP) | +5 (test #5 편차 시 +4) | — |
| B4 (Mock 통합) | +3 | — |
| C1 (H2 환경) | +0 | — |
| C2 (DataSourceTransactionManager) | +5 | — |
| C3 (JdbcTemplate) | +4 | — |
| C4 (도메인+Repo) | +0 | — |
| C5 (Service+Controller+main+AppConfig) | +0 | — |
| C6 (통합 시연 박제) | +8 | — |
| D1 (ScopedValue + 비교) | +6 | — |
| D2 (A-2+C-3 통합) | +3 | — |
| **합계** | **+48** | — |

**누적**: 185 PASS → ~233 PASS (Phase 2B 종료 시점 + Phase 3 추정 +48).

> 마감 게이트(E2) 단계의 신규 테스트는 별도 카운트 — 마감 시 박제.

---

## 9. Definition of Done

본 spec의 모든 산출물 완성 + 마감 게이트 통과. spec § 9의 26항목.

- [ ] 1. `settings.gradle.kts`에 `sfs-tx` 추가 + `sfs-tx/build.gradle.kts` 신설 — **A1 step 1, 2**
- [ ] 2. `sfs-samples/build.gradle.kts`에 `implementation(project(":sfs-tx"))` 추가 — **A1 step 3**
- [ ] 3. `@Transactional`, `Propagation` enum 신설 — **A2 step 1, 2**
- [ ] 4. `PlatformTransactionManager` / `TransactionStatus` / `TransactionDefinition` 인터페이스/record — **A2 step 3, 4, 5**
- [ ] 5. `AbstractPlatformTransactionManager` 추상 골격 (propagation 분기 알고리즘) — **A3 step 2**
- [ ] 6. `MockTransactionManager` + 단위 테스트 4건 — **B1**
- [ ] 7. `DataSourceTransactionManager` + 단위 테스트 5건 — **C2**
- [ ] 8. `TransactionSynchronizationManager` 인터페이스 — **A4 step 3**
- [ ] 9. `ThreadLocalTsm` + 단위 테스트 4건 — **A4**
- [ ] 10. `ScopedValueTsm` + 단위 테스트 3건 — **D1 step 1~4**
- [ ] 11. `TsmComparisonTest` 비교 박제 3건 — **D1 step 5, 6**
- [ ] 12. `TransactionInterceptor` + 단위 테스트 6건 (B-2 흡수) — **B2**
- [x] 13. `TransactionalBeanPostProcessor` + 단위 테스트 4건 (A-1 흡수, A-3 후속 phase 이월) — **B3**
- [ ] 14. `JdbcTemplate` mini + 단위 테스트 4건 — **C3**
- [ ] 15. `Order` / `AuditLog` 도메인 + JDBC Repository 2종 — **C4**
- [ ] 16. `OrderService` (REQUIRED) + `AuditService` (REQUIRES_NEW) + Controller — **C5**
- [ ] 17. `AppConfig`에 `DataSource` / `PlatformTransactionManager` / `TransactionalBeanPostProcessor` `@Bean` 추가 — **C5 step 5**
- [ ] 18. `schema.sql` + 테스트 셋업 hook — **C1 step 4**
- [ ] 19. `TransactionPropagationIntegrationTest` 6건 — **C6 step 1**
- [ ] 20. `EarlyReferenceIntegrationTest` 2건 (A-2 흡수) — **D2 step 2**
- [ ] 21. `EnhancedBeanDestroyTest` 1건 (C-3 흡수) — **D2 step 3**
- [ ] 22. `TransactionDemoApplicationTest` 2건 (§ 4.2/4.3 시연 박제) — **C6 step 2**
- [ ] 23. `sfs-tx/README.md` 신설 + `sfs-samples/README.md` Phase 3 갱신 — **E1 step 1, 2**
- [ ] 24. `./gradlew :sfs-tx:test :sfs-samples:test` 모두 PASS — **E1 step 4**
- [ ] 25. `./gradlew build` 전체 PASS + 누적 ~230~235 PASS / 0 FAIL — **E1 step 4**
- [ ] 26. 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify 패스) 실행 후 기록 — **E2** (Plan 외 마감 의식)

---

## 10. Self-Review 체크리스트 (plan 작성자 자기검토)

**1. Spec coverage:** spec § 9 DoD 26항목 모두 task에 매핑됨 — § 9에 매핑 표 박제. ✅

**2. 회귀 카운트 보정:** spec § 6.3은 +48을 추정 — plan은 A4(+4) + B1(+4) + B2(+6) + B3(+5) + B4(+3) + C2(+5) + C3(+4) + C6(+8) + D1(+6) + D2(+3) = +48로 계산. § 8 표에 박제. ✅

**3. Type consistency:** 주요 시그니처 일관성:
- `PlatformTransactionManager.getTransaction(TransactionDefinition)` — A2/A3/B1/C2 모두 일관
- `TransactionStatus.isNewTransaction()/setRollbackOnly()/getSuspendedResources()` — A2/A3/B1/C2 일관
- `TransactionSynchronizationManager.bindResource/getResource/unbindResource/clearAll` — A4/B1/C2/C3/D1 일관
- `TransactionInterceptor.invoke(target, method, proceed)` — B2/B3 일관
- `JdbcTemplate.query/update` — C3/C4 일관
- `DataSourceTransactionManager(DataSource, TransactionSynchronizationManager)` — C2/C5 일관 ✅

**4. 누락 없는지 재확인:** A2~A4 (인프라), B1~B4 (Mock end-to-end vertical slice 1), C1~C6 (JDBC + 도메인 + 시연), D1~D2 (ScopedValue + 보류 흡수), E1~E2 (문서 + 마감) 모두 풀 사이클. spec § 5.4 BPP 가드 5건(A-1/A-2/A-3/B-2/C-3) 흡수 위치 모두 박제. ✅

**5. Phase 1A/2B 자산 회수 검증:**
- Phase 2B AOP 인프라 회수: `AspectEnhancingBeanPostProcessor` → `TransactionalBeanPostProcessor` (B3), `AdviceInterceptor` → `TransactionInterceptor` (B2), byte-buddy enhance + `ClassLoadingStrategy.UsingLookup` (B3) 자연 재활용.
- Phase 1A 3-level cache 자산 회수: SIABPP `getEarlyBeanReference` 훅이 D2 step 1에서 처음 의미 있게 사용. ✅

**6. 하이브리드 task 구조 일관성:** A 단계(인프라 bottom-up 4 task) → B 단계(Mock TM end-to-end 4 task, vertical slice 1) → C 단계(JDBC + 도메인 + 시연 6 task, vertical slice 2) → D 단계(ScopedValue + 보류 흡수 2 task) → E 단계(문서 + 마감 2 task). 17 task 총합 일치. ✅

**7. Bite-sized 검증:** 가장 큰 task인 C5(서비스+컨트롤러+AppConfig+main, 8 step)도 step 단위로 명확. 평균 step 5~7개 — bite-sized 원칙 만족. ✅

**8. 편차 가능성 박제:** 실행 단계에서 결정해야 할 편차:
- B3 test #5 (A-3 가드) — BPP 시점 검증의 의미 약함 — 통합 테스트로 이전 또는 제거
- D2 step 1 (SIABPP 호출 회로) — Phase 1A에 회로가 있으면 OK, 없으면 별도 task
- 두 편차 모두 실행 단계에서 *Plan 실행 기록* 블록으로 박제 필수. ✅

**9. 외부 의존 추가 명시:** H2 의존이 spec 의사결정 #11 + § 6.4 + plan C1에 일관 박제. CLAUDE.md "외부 런타임 의존 추가" 절차 준수. ✅

---



