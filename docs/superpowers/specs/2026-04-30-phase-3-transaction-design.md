# Spring From Scratch — Phase 3: 트랜잭션 추상화 설계

| 항목 | 값 |
|---|---|
| 작성일 | 2026-04-30 |
| 상태 | 초안 → 사용자 리뷰 대기 |
| 범위 | Phase 3 (트랜잭션 추상화) — 6단계 로드맵 중 3단계 |
| 선행 의존 | Phase 1 (IoC), Phase 2A/2B (AOP) — 모두 완료 |
| 후속 의존 | Phase 4 (JPA 핵심), Phase 5 (Redis), Phase 6 (Kafka) — 본 phase의 `PlatformTransactionManager` 위에 얹힘 |

---

## 0. 프로젝트 개요

### 0.1 Phase 3 한 줄 요약

> **"`PlatformTransactionManager` 인터페이스 + `@Transactional` advice + 두 TSM 구현체(ThreadLocal/ScopedValue)로 추상화 본질을 박제하고, JDBC 통합으로 실제 commit/rollback 시연까지 도달."**

### 0.2 6단계 로드맵 위치

| 단계 | 모듈 | 상태 |
|---|---|---|
| 1. IoC 컨테이너 | sfs-core / sfs-beans / sfs-context | ✅ 완료 |
| 2. AOP | sfs-aop | ✅ 완료 (Phase 2A 인프라 + 2B 본 기능) |
| **3. 트랜잭션 추상화** | **sfs-tx (신설)** | **본 spec 대상** |
| 4. JPA 핵심 | sfs-jpa (가칭) | 본 phase 산출물 위에 얹힘 |
| 5. Redis 통합 | sfs-redis (가칭) | 본 phase의 트랜잭션 동기화 콜백 활용 |
| 6. Kafka 통합 | sfs-kafka (가칭) | 동일 |

본 phase는 *원안 spec § 0.2의 6단계 로드맵을 정확히 따름*. Phase 2C(박제 후속) 우회로는 채택하지 않고, 박제 보류 finding은 § 8에서 본문 task에 자연 흡수.

### 0.3 학습 정점

원안 의사결정 #6 *"같은 코드가 두 컨테이너에서 동일하게 돈다"* 의 본 phase 응용:

> *"같은 `@Transactional` advice가 두 TSM 구현체(ThreadLocal / ScopedValue)와 두 TM 구현체(Mock / DataSource) 위에서 동일하게 작동한다."*

추상화 인터페이스의 *교환 가능성*이 학습의 정점.

---

## 1. 의사결정 로그

브레인스토밍에서 확정된 결정.

| # | 결정 | 선택지 | 채택 | 근거 |
|---|---|---|---|---|
| 1 | Phase 3 학습 정점 | A: AOP 통합만 (mock TM) / B: 추상화 + JDBC / C: 풀스펙 | **B** | 의사결정 #6과 정합. Phase 4 (JPA) 진입 비용 감소. 외부 시스템(DB) 통합 학습이 원안 비전. |
| 2 | propagation 범위 | REQUIRED만 / +REQUIRES_NEW / +NESTED | **REQUIRED + REQUIRES_NEW** | suspend/resume이 transaction synchronization의 본질. 두 propagation으로 Spring TM 본질 80% 시연. NESTED는 SAVEPOINT 학습 트랙 분리. |
| 3 | sample 도메인 확장 | 신규 추가 / 전체 마이그레이션 / 하이브리드 | **신규 Order/AuditLog 추가** | 기존 Phase 1~2 박제 자산 보존. propagation 시연 시나리오와 정합. 회귀 안정성. |
| 4 | TSM 데이터 구조 | ThreadLocal만 / ThreadLocal + ScopedValue 비교 / ScopedValue만 | **ThreadLocal 메인 + ScopedValue 비교 박제** | 원안 의사결정 #11의 *"Phase 3+ ScopedValue 옵션"* 약속 회수. 의사결정 #6 정신 응용. ScopedValue immutable 제약 박제 자체가 학습 가치. |
| 5 | Phase 2B 보류 finding 흡수 | 최소 3건 / 균형 5건 / 전체 7건 | **균형 5건 (A-1, A-2, A-3, B-2, C-3)** | Phase 3에서 *자연 발현*하는 finding만 흡수. C-1/C-2는 Phase 4 또는 영구 박제. |
| 6 | 모듈 신설 | sfs-tx / sfs-context 흡수 | **sfs-tx 신설** | Spring `spring-tx` 분리 철학 정합. `sfs-context`가 `sfs-aop` 의존하지 않으므로 `sfs-tx`는 `sfs-context` 의존 없이 `sfs-aop`/`sfs-beans`/`sfs-core`만 의존. |
| 7 | isolation 처리 | 동작 검증 / 시그니처만 | **시그니처만, 동작 검증 비목표** | DB 의존성 강함. Spring 본가도 isolation 검증 테스트는 거의 없음. |
| 8 | rollbackFor 처리 | 동작 / 시그니그만 / 미도입 | **시그니처만, 동작은 default(RuntimeException rollback)만** | 학습 가치 < 박제 비용. 후속 phase 회수 후보. |
| 9 | JdbcTemplate mini | 도입 / 미도입 | **도입 (query/update만)** | propagation 시연이 raw `PreparedStatement` 반복으로 노이즈. transaction-aware connection 박제 핵심. |
| 10 | TransactionTemplate (programmatic) | 도입 / 미도입 | **미도입** | declarative `@Transactional` 한 가지로 충분. 후속 phase 회수 후보. |
| 11 | 외부 의존 추가 | H2 임베디드 / HSQLDB / 미도입 | **H2 임베디드** | spec 개정 명시 박제. ASM/byte-buddy와 동일 기준 (의사결정 #9). 임베디드 + 학습 친화. |
| 12 | A-1 final 메서드 가드 | WARN / fail-fast | **WARN 우선** | Phase 2B의 silent skip을 *명시화*하는 1차 목표. 강화는 후속 phase. |

---

## 2. 아키텍처 & 모듈 구조

### 2.1 모듈 구성

```
spring-from-scratch/
├── sfs-core/            (변경 없음)
├── sfs-beans/           (변경 없음)
├── sfs-context/         (변경 없음)
├── sfs-aop/             (변경 없음)
├── sfs-tx/              ← 신설 (Phase 3)
└── sfs-samples/         ← Order/AuditLog 도메인 추가
```

### 2.2 sfs-tx 내부 구조

```
sfs-tx/src/main/java/com/choisk/sfs/tx/
├── annotation/
│   ├── Transactional.java          # 메서드/클래스 애노테이션
│   └── Propagation.java            # enum (REQUIRED, REQUIRES_NEW)
├── PlatformTransactionManager.java # 인터페이스
├── TransactionStatus.java          # 인터페이스
├── TransactionDefinition.java      # record
├── support/
│   ├── AbstractPlatformTransactionManager.java
│   ├── MockTransactionManager.java
│   ├── DataSourceTransactionManager.java
│   ├── TransactionSynchronizationManager.java
│   ├── ThreadLocalTsm.java
│   ├── ScopedValueTsm.java
│   └── TransactionInterceptor.java
├── jdbc/
│   └── JdbcTemplate.java
└── boot/
    └── TransactionalBeanPostProcessor.java
```

### 2.3 의존 방향 (Gradle 강제)

```
sfs-samples ──► sfs-context ──► sfs-beans ──► sfs-core
       │              ▲                ▲
       │              │                │
       └─► sfs-tx ────┴────► sfs-aop ──┘
```

- **신규 의존**: `sfs-samples → sfs-tx`, `sfs-tx → sfs-aop`, `sfs-tx → sfs-beans`, `sfs-tx → sfs-core`
- **`sfs-tx`가 `sfs-context`를 의존하지 않는 이유**: TSM/TM/JdbcTemplate 자체는 `ApplicationContext` 인프라가 필요 없음. Spring 본가의 `spring-tx → spring-aop + spring-beans + spring-core` 의존 그대로.
- **외부 의존 신규**: `com.h2database:h2` (임베디드 DB, 학습 박제용 — 의사결정 #11)

### 2.4 Spring 원본 매핑

| 우리 모듈 | Spring 원본 | 대응 클래스 |
|---|---|---|
| `sfs-tx` | `spring-tx` | `PlatformTransactionManager`, `DataSourceTransactionManager`, `TransactionSynchronizationManager`, `TransactionInterceptor`, `JdbcTemplate` |

---

## 3. 핵심 컴포넌트

### 3.1 애노테이션 + enum (3종)

```java
// sfs-tx/.../annotation/Transactional.java
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Transactional {
    String transactionManager() default "";
    Propagation propagation() default Propagation.REQUIRED;
    int isolation() default -1;                          // 시그니처만
    Class<? extends Throwable>[] rollbackFor() default {}; // 시그니처만
}

// sfs-tx/.../annotation/Propagation.java
public enum Propagation { REQUIRED, REQUIRES_NEW }
```

### 3.2 추상 인프라 (3종)

| 타입 | 종류 | 책임 |
|---|---|---|
| `PlatformTransactionManager` | interface | `getTransaction(def)` / `commit(status)` / `rollback(status)` |
| `TransactionStatus` | interface | `isNewTransaction()` / `setRollbackOnly()` / `isRollbackOnly()` / `getSuspendedResources()` |
| `TransactionDefinition` | record | `propagation`, `isolation`, ... — 트랜잭션 시작 시 의도 |

### 3.3 TM 구현 2종

| 클래스 | 역할 | 학습 가치 |
|---|---|---|
| `MockTransactionManager` | 콘솔에 `[TX] BEGIN/COMMIT/ROLLBACK/SUSPEND/RESUME` 출력만 | *추상화의 본질이 인터페이스에 있다* 박제 |
| `DataSourceTransactionManager` | JDBC `Connection.setAutoCommit(false)` → `commit()` / `rollback()` | 실제 DB 통합 시연 |

> 둘 다 `AbstractPlatformTransactionManager` 추상 골격을 상속. propagation 분기 알고리즘은 추상 골격에 박제, 구현체는 *begin/commit/rollback 실제 동작*만 override.

### 3.4 TSM 2종 (의사결정 #4)

| 클래스 | 데이터 구조 | 박제 의도 |
|---|---|---|
| `ThreadLocalTsm` | `ThreadLocal<Stack<TxState>>` | Spring 본가 정합. 가변 스택 — synchronization *추가 등록* 자연 가능 |
| `ScopedValueTsm` | `ScopedValue<TxState>` + 우회 가변 영역 (`ScopedValue<MutableHolder>`) | Java 25 idiom. *immutable 제약을 어떻게 우회하는가* 박제 |

> 두 구현체 모두 동일 `TransactionSynchronizationManager` 인터페이스. AOP advice가 *주입받은 TSM*을 그대로 사용 (DI로 구현체 교체).

### 3.5 advice + BPP (Phase 2B 패턴 회수)

#### `TransactionInterceptor` (advice 본체)

```
입력: invocation (Phase 2B JoinPoint와 유사)
1. Transactional anno = 메서드/클래스에서 추출
2. PlatformTransactionManager tm = anno.transactionManager() resolve  ← B-2 흡수
3. TransactionStatus status = tm.getTransaction(definitionFrom(anno))
4. try {
       result = invocation.proceed()
       tm.commit(status)
   } catch (Throwable t) {
       if (shouldRollback(t, anno)) tm.rollback(status)
       else tm.commit(status)                    ← checked exception은 commit
       throw t
   }
```

#### `TransactionalBeanPostProcessor` (BPP 본체)

Phase 2B `AspectEnhancingBeanPostProcessor` 패턴 그대로:

- 빈 클래스에 `@Transactional` 메서드가 있으면 byte-buddy enhance
- `final` 메서드 가드 (A-1 흡수) — WARN 로그
- Phase 1A `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()` 훅 구현으로 early reference 처리 (A-2 흡수) — Phase 1A 3-level cache 자산이 *처음 의미 있게 사용*됨
- `@Transactional` 클래스가 빈으로 등록되지 않았으면 명시 에러 (A-3 흡수)
- BPP 자기 격리 (Phase 2B 패턴 그대로)

### 3.6 JdbcTemplate mini

```java
public class JdbcTemplate {
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args);
    public int update(String sql, Object... args);
    // 그 외 비목표
}
```

Connection은 `TransactionSynchronizationManager.getResource(dataSource)`로 가져옴 — *transaction-aware*. 이게 Spring `JdbcTemplate`의 본질.

---

## 4. 주요 데이터 흐름

### 4.1 시연 시나리오

```java
// sfs-samples/.../order/OrderService.java
@Service
public class OrderService {
    @Autowired private OrderRepository orderRepo;
    @Autowired private AuditService auditService;

    @Transactional
    public void placeOrder(String item, int amount) {
        orderRepo.save(new Order(item, amount));
        auditService.log("order placed: " + item);
        if (amount > 100_000) {
            throw new BusinessException("amount limit exceeded");
        }
    }
}

// sfs-samples/.../audit/AuditService.java
@Service
public class AuditService {
    @Autowired private AuditRepository auditRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String message) {
        auditRepo.save(new AuditLog(Instant.now(), message));
    }
}
```

### 4.2 정상 흐름 (`placeOrder("A", 5000)`)

```
[TX] BEGIN     #1 (outer, REQUIRED)
  [JDBC] INSERT INTO orders ...
  [TX] SUSPEND #1
  [TX] BEGIN   #2 (inner, REQUIRES_NEW)
    [JDBC] INSERT INTO audit_log ...
  [TX] COMMIT  #2
  [TX] RESUME  #1
[TX] COMMIT    #1
결과: orders 1행, audit_log 1행
```

### 4.3 예외 흐름 (`placeOrder("B", 200_000)`)

```
[TX] BEGIN     #1 (outer, REQUIRED)
  [JDBC] INSERT INTO orders ...
  [TX] SUSPEND #1
  [TX] BEGIN   #2 (inner, REQUIRES_NEW)
    [JDBC] INSERT INTO audit_log ...
  [TX] COMMIT  #2
  [TX] RESUME  #1
  ✗ throw BusinessException
[TX] ROLLBACK  #1
결과: orders 0행, audit_log 1행          ← 정점: 비즈니스 실패해도 감사 로그는 남음
```

이 출력은 `TransactionDemoApplicationTest`로 박제 (Phase 1C/2B 콘솔 어서션 패턴 그대로).

### 4.4 propagation 분기 알고리즘

```
입력: TransactionDefinition def
존재하는 트랜잭션 = TSM에서 조회

if (없음):
    if def.propagation == REQUIRED 또는 REQUIRES_NEW:
        새 트랜잭션 시작 → TSM bind → status(isNew=true)

if (있음):
    if def.propagation == REQUIRED:
        join → status(isNew=false, suspendedResources=null)
    if def.propagation == REQUIRES_NEW:
        suspend(현재) → suspendedResources 보관
        새 트랜잭션 시작 → TSM bind → status(isNew=true, suspendedResources=보관본)

commit/rollback 시 status.suspendedResources != null이면 resume
```

### 4.5 TSM 두 구현체 데이터 흐름 차이

| 시점 | `ThreadLocalTsm` | `ScopedValueTsm` |
|---|---|---|
| BEGIN #1 | `stack.push(tx#1)` (가변) | `ScopedValue.where(SLOT, tx#1).run(() -> ...)` (immutable scope) |
| SUSPEND #1 | `stack.peek()`을 새 변수로 보관 후 `stack.pop()` | scope 종료 후 새 scope 시작 — *우회 가변 영역* (`ScopedValue<MutableHolder>`) 필요 |
| BEGIN #2 | `stack.push(tx#2)` | 새 `where().run()` (중첩 scope) |
| RESUME #1 | `stack.push(보관본)` | scope 종료 — 자동 복원 |

> *"왜 Spring은 ThreadLocal을 썼는가"* 의 박제: `ScopedValueTsm`이 SUSPEND를 위해 우회 가변 영역이 필요한 시점이 결정타. immutable 제약과 transaction synchronization의 *추가 등록* 패턴이 충돌.

---

## 5. 에러 처리

### 5.1 rollback 발동 조건

| 시나리오 | 동작 |
|---|---|
| `RuntimeException` throw | rollback (default) |
| `Error` throw | rollback (Spring 본가 정합) |
| `Checked Exception` throw | **commit** (Spring 본가 정합) — 학습 박제 |
| `@Transactional(rollbackFor = ...)` 지정 | 시그니처만, 동작은 default와 동일 |
| `TransactionStatus.setRollbackOnly()` 호출 | rollback 강제 (REQUIRED join inner가 outer 결정 영향) |

### 5.2 setRollbackOnly 메커니즘 (REQUIRED join 시)

```
시나리오: outer(REQUIRED) → inner(REQUIRED, join)
inner에서 RuntimeException 발생

[TX] BEGIN     #1 (outer)
  [TX] (join)  #1 (inner, isNew=false)
    ✗ throw RuntimeException
  status.setRollbackOnly()
  rethrow
[TX] commit() 호출 → status.isRollbackOnly()=true → ROLLBACK #1
```

`AbstractPlatformTransactionManager.commit()` 시 `isRollbackOnly()` 검사 분기가 핵심.

### 5.3 자원 누수 방지 — try/finally 패턴

`TransactionInterceptor`는 try/catch만 사용 (Spring 본가 패턴 그대로). `commit()` / `rollback()` 양쪽이 자원 정리 책임. try-with-resources 미적용 — connection이 *advice 진입 시 열림 → advice 종료 시 닫힘*이라 메서드 스코프와 충돌.

### 5.4 BPP 가드 (Phase 2B 5건 prerequisite 흡수)

| ID | 가드 위치 | 동작 |
|---|---|---|
| **A-1** (final 메서드) | `TransactionalBeanPostProcessor.enhance()` 진입 | WARN 로그 (의사결정 #12) |
| **A-2** (early reference) | Phase 1A `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()` 훅 활용 | 순환 의존 시 enhance된 early reference 반환 — Phase 1A 3-level cache 자산이 *처음 의미 있게 사용*됨 |
| **A-3** (`@Component` 누락) | BPP 스캔 진입 | 명시 에러 (`"@Transactional class X must be registered as a bean"`) |
| **B-2** (TM 이름 라우팅) | `TransactionInterceptor.resolveTransactionManager()` | `BeanFactory.getBean(name)` (없으면 fallback to type lookup) |
| **C-3** (enhanced 빈 destroy) | `EnhancedBeanDestroyTest` 통합 테스트 | enhanced 서브클래스의 `@PreDestroy` 호출 검증 |

### 5.5 예외 전파 규칙

| 시나리오 | 처리 |
|---|---|
| 비즈니스 예외 → advice → caller | rollback 후 *원본 예외* re-throw (wrap 금지) |
| `commit()` 자체가 예외 | `TransactionException` (sealed) wrap |
| `rollback()` 자체가 예외 | 로그 + *원본 예외* 우선 throw (finally 함정 회피) |

---

## 6. 테스트 전략

### 6.1 TDD 적용 / 제외 (CLAUDE.md "TDD 적용 가이드")

#### 적용 (단위 테스트 ~37 PASS)

| 컴포넌트 | 테스트 클래스 | PASS |
|---|---|---|
| `TransactionStatus` 구현 | `TransactionStatusImplTest` | 3 |
| `MockTransactionManager` | `MockTransactionManagerTest` | 4 |
| `DataSourceTransactionManager` | `DataSourceTransactionManagerTest` | 5 |
| `ThreadLocalTsm` | `ThreadLocalTsmTest` | 4 |
| `ScopedValueTsm` | `ScopedValueTsmTest` | 3 |
| `TransactionInterceptor` | `TransactionInterceptorTest` | 6 |
| `TransactionalBeanPostProcessor` | `TransactionalBeanPostProcessorTest` | 5 |
| `JdbcTemplate` mini | `JdbcTemplateTest` | 4 |
| TSM 비교 | `TsmComparisonTest` | 3 |

#### 제외

| 항목 | 근거 |
|---|---|
| `AbstractPlatformTransactionManager` | 추상 골격 (CLAUDE.md 예외) — 통합 테스트로 간접 검증 |
| `@Transactional`, `Propagation` | 시그니처만 |
| `TransactionDefinition` (record) | 데이터 컨테이너 |
| `Order` / `AuditLog` (record) | 데이터 컨테이너 |
| `OrderRepository` / `AuditRepository` (JDBC) | JdbcTemplate 위 얇은 래퍼, 통합 테스트로 간접 검증 |

### 6.2 통합 테스트 (~11 PASS)

| 테스트 클래스 | 시나리오 | PASS |
|---|---|---|
| `TransactionPropagationIntegrationTest` | REQUIRED join / REQUIRES_NEW suspend-resume / inner 실패 → outer rollback / outer 실패 → inner 살아남음 | 6 |
| `EarlyReferenceIntegrationTest` (A-2) | 순환 의존 빈이 transactional advice 적용된 채 주입 검증 | 2 |
| `EnhancedBeanDestroyTest` (C-3) | enhanced transactional 빈의 `@PreDestroy` 콜백 호출 검증 | 1 |
| `TransactionDemoApplicationTest` | § 4.2/4.3 시연 출력 정확 어서션 | 2 |

### 6.3 합계 추정

**+48 PASS** (단위 37 + 통합 11). 누적 185 → **~233 PASS**.

### 6.4 환경 셋업

| 항목 | 결정 |
|---|---|
| H2 의존 | `com.h2database:h2:2.2.x` (test + runtime, 임베디드) |
| `schema.sql` 실행 | 테스트 셋업 시 `Connection.createStatement().execute(SQL)` (`Flyway`/`Liquibase` 비목표) |
| DataSource | `org.h2.jdbcx.JdbcDataSource` (HikariCP 등 풀 비목표) |
| JUnit hook | `@BeforeEach`로 컨테이너 + DB 초기화, `@AfterEach`로 close + drop |

---

## 7. 한계 (의도된 단순화)

본 phase는 *transaction 추상화의 본질 학습*을 목적으로 하며, 다음은 의도적으로 비목표:

- **propagation 5종 미지원**: SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER / NESTED — 후속 phase 또는 Phase 4 진입 시 재검토. NESTED는 SAVEPOINT 학습 트랙 분리.
- **isolation 동작 검증 없음**: 시그니처만. DB별 격리 수준 검증은 학습 효율 낮음.
- **`rollbackFor` 동작 미구현**: 시그니처만. 사용자가 지정해도 default(RuntimeException rollback)와 동일 동작. 후속 phase 회수.
- **`TransactionTemplate` (programmatic) 미도입**: declarative `@Transactional` 한 가지로 충분. 후속 phase 회수.
- **timeout / readOnly 미지원**: `@Transactional` 시그니처에도 미포함. 학습 노이즈.
- **트랜잭션 동기화 콜백 (`TransactionSynchronization.afterCommit`/`afterCompletion`) 부분 박제**: 인터페이스 + 등록 메커니즘은 박제, 실제 사용 시연은 Phase 5/6 (Redis/Kafka 진입 시) 회수.
- **JTA / 분산 트랜잭션 미지원**: 단일 DataSource만. JTA는 학습 가치 vs 박제 비용 비율 낮음.
- **HikariCP 등 connection pool 미사용**: H2 임베디드 + raw `JdbcDataSource`. 풀링 학습은 Phase 4 (JPA) 또는 별도 phase.
- **`schema.sql` migration tool 없음**: 테스트 셋업 시 직접 SQL 실행. Flyway/Liquibase는 별도 학습 트랙.

---

## 8. Phase 2B prerequisite 회수

Phase 2B 마감 게이트에서 보류된 finding 7건 중 5건을 본 phase에 자연 흡수 (의사결정 #5). 별도 prerequisite task 없음 — *본문 task에 통합*.

| ID | 흡수 task |
|---|---|
| A-1 (final WARN) | `TransactionalBeanPostProcessor` 본문 task 안 가드 추가 |
| A-2 (early reference) | `getEarlyBeanReference` 훅 task + 통합 테스트 task |
| A-3 (`@Component` 누락 검증) | BPP 본문 task 안 명시 에러 추가 |
| B-2 (TM 이름 라우팅) | `TransactionInterceptor.resolveTransactionManager()` 본문 task |
| C-3 (enhanced destroy) | `EnhancedBeanDestroyTest` 통합 테스트 task |

### 8.1 영구 박제 (WONT-FIX)

| ID | 근거 |
|---|---|
| C-1 (BPP/Aspect 자기 격리 체크 순서) | 학습 가치 모호, 현실 시나리오 거의 없음 (Phase 2B reviewer 보류 결정 근거 동일) |
| B-1 (`@Bean(name=)` 통합 박제) | Phase 3와 무관 |
| B-3 (byte-buddy `args` 미사용 박제) | Phase 3와 무관, README 박제로 충분 |

### 8.2 별도 phase 후보

| ID | 후보 phase |
|---|---|
| C-2 (`proceed()` 중복 호출 동작 박제) | Phase 4 retry advice 또는 별도 phase에서 더 자연스러움 |
| B-4 (named module 전환) | 단독 phase 권장 (전 모듈 `module-info.java` 동시 변경 비용 큼) |

---

## 9. Definition of Done

본 spec의 모든 산출물 완성 + 마감 게이트 통과. 26항목.

- [ ] 1. `settings.gradle.kts`에 `sfs-tx` 추가 + `sfs-tx/build.gradle.kts` 신설
- [ ] 2. `sfs-samples/build.gradle.kts`에 `implementation(project(":sfs-tx"))` 추가
- [ ] 3. `@Transactional`, `Propagation` enum 신설
- [ ] 4. `PlatformTransactionManager` / `TransactionStatus` / `TransactionDefinition` 인터페이스/record
- [ ] 5. `AbstractPlatformTransactionManager` 추상 골격 (propagation 분기 알고리즘)
- [ ] 6. `MockTransactionManager` + 단위 테스트 4건
- [ ] 7. `DataSourceTransactionManager` + 단위 테스트 5건
- [ ] 8. `TransactionSynchronizationManager` 인터페이스
- [ ] 9. `ThreadLocalTsm` + 단위 테스트 4건
- [ ] 10. `ScopedValueTsm` + 단위 테스트 3건
- [ ] 11. `TsmComparisonTest` 비교 박제 3건
- [ ] 12. `TransactionInterceptor` + 단위 테스트 6건 (B-2 흡수)
- [ ] 13. `TransactionalBeanPostProcessor` + 단위 테스트 5건 (A-1 / A-3 흡수)
- [ ] 14. `JdbcTemplate` mini + 단위 테스트 4건
- [ ] 15. `Order` / `AuditLog` 도메인 + JDBC Repository 2종
- [ ] 16. `OrderService` (REQUIRED) + `AuditService` (REQUIRES_NEW) + Controller
- [ ] 17. `AppConfig`에 `DataSource` / `PlatformTransactionManager` / `TransactionalBeanPostProcessor` `@Bean` 추가
- [ ] 18. `schema.sql` + 테스트 셋업 hook
- [ ] 19. `TransactionPropagationIntegrationTest` 6건
- [ ] 20. `EarlyReferenceIntegrationTest` 2건 (A-2 흡수)
- [ ] 21. `EnhancedBeanDestroyTest` 1건 (C-3 흡수)
- [ ] 22. `TransactionDemoApplicationTest` 2건 (§ 4.2/4.3 시연 박제)
- [ ] 23. `sfs-tx/README.md` 신설 + `sfs-samples/README.md` Phase 3 갱신
- [ ] 24. `./gradlew :sfs-tx:test :sfs-samples:test` 모두 PASS
- [ ] 25. `./gradlew build` 전체 PASS + 누적 ~230~235 PASS / 0 FAIL
- [ ] 26. 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify 패스) 실행 후 기록

---

## 10. 후속 단계

1. **본 spec 사용자 리뷰** — 사용자 승인 후 plan 작성으로 이행.
2. **plan 작성** — `superpowers:writing-plans` 스킬로 task 단위 분해. 위 DoD 26항목을 task로 매핑.
3. **Phase 4 (JPA 핵심) brainstorming** — 본 phase 산출물(특히 `PlatformTransactionManager`, `DataSourceTransactionManager`, `JdbcTemplate`) 위에 영속성 컨텍스트 + EntityManager 도입.
4. **이월 박제 후보 (Phase 4 또는 별도)**:
   - propagation 추가 5종 (SUPPORTS / NOT_SUPPORTED / MANDATORY / NEVER / NESTED)
   - `rollbackFor` 동작 구현
   - `TransactionTemplate` (programmatic) 도입
   - C-2 `proceed()` 중복 호출 박제 (retry advice 학습 시점)
   - B-4 named module 전환 (단독 phase)

---

## 11. Self-Review 체크리스트 (spec 작성자 자기검토)

**1. 원안 정합성**: 6단계 로드맵의 *3단계 정확 진입*. Phase 2C 우회로 채택하지 않음. 의사결정 #11의 "Phase 3+ ScopedValue 옵션" 약속 회수. ✅

**2. 의사결정 12건 일관성**: Q1~Q5 사용자 채택 + 부수 결정 7건 모두 § 1 표에 박제. 본문 모든 섹션이 의사결정과 정합. ✅

**3. 모듈 의존 방향**: `sfs-tx → sfs-aop, sfs-beans, sfs-core` (sfs-context 의존 없음). Spring 본가 `spring-tx` 의존과 동일. ✅

**4. Phase 2B 자산 회수 검증**: BPP 패턴 (`AspectEnhancingBeanPostProcessor` → `TransactionalBeanPostProcessor`), advice 패턴 (`AdviceInterceptor` → `TransactionInterceptor`), byte-buddy enhance, `getEarlyBeanReference` 훅 모두 자연 회수. ✅

**5. Phase 1A 자산 회수 검증**: 3-level cache의 `getEarlyBeanReference` 훅이 *처음 의미 있게 사용*됨 (A-2 흡수). ✅

**6. 회귀 카운트 일관성**: § 6.1/6.2 = +48 PASS, DoD 25항목 = "누적 ~230~235". 185 + 48 = 233 일치. ✅

**7. TDD 적용/제외 판단**: CLAUDE.md "TDD 적용 가이드"의 추상 골격 / 데이터 컨테이너 / 시그니처 예외에 모두 정합. ✅

**8. 한계 박제 (§ 7) 9항목**: 후속 phase에서 회수 가능한 항목과 영구 미목표 항목 분리. ✅

**9. 학습 정점 명시**: § 0.3 의사결정 #6 응용. § 4.5 TSM 비교, § 4.3 시연 출력 정점 (`orders 0행, audit_log 1행`)이 학습 정점 박제. ✅
