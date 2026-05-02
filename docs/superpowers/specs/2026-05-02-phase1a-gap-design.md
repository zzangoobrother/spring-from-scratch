# Spring From Scratch — Phase 1A 결함 보강 (중간 정리 phase) 설계

| 항목 | 값 |
|---|---|
| 작성일 | 2026-05-02 |
| 상태 | 초안 → 사용자 리뷰 대기 |
| 범위 | Phase 1A 결함 보강 + Phase 4 진입 토대 정리 (중간 정리 phase, 정식 phase 번호 없음) |
| 선행 의존 | Phase 1A (sfs-beans), Phase 2B (sfs-aop), Phase 3 (sfs-tx) — 모두 완료 + main 머지 |
| 후속 의존 | Phase 4 (JPA 핵심) — 본 phase 완료 후 진입 |
| 관련 메모리 | `project_phase3_phase1a_gap.md` (Phase 3 작업 중 노출된 결함 박제) |

---

## 0. 프로젝트 개요

### 0.1 한 줄 요약

> **"각 enhance BPP에 `earlyProxyReferences` 캐시를 도입해 *순환 의존 + enhance* 시나리오에서 단일 인스턴스를 *원천 보장*하고, 인프라 계층의 우회 코드(`copyFieldsToEarlyReference`)를 제거. G1·G2 회귀 갭은 함께 메워 Phase 4 (JPA 핵심) 진입 토대 정리."**

### 0.2 학습 정점 (D1 BLOCKED로 재정의됨 — 2026-05-02)

> *"순환 의존 + BPP enhance 시나리오에서 단일 인스턴스 보장의 책임 위치는 *프록시 패턴*에 따라 다르다.
>  Spring 본가는 *CGLIB wrap-around proxy*(원본에 필드 주입 → 그 원본을 프록시로 위임 wrap)로 `early == created` 보장 → BPP 자신이 책임.
>  우리 *byte-buddy 서브클래싱 + 필드 복사* 패턴은 `early ≠ created` 항상 성립 → **책임 분담**: BPP가 *재enhance 방지*(CPU 절약 + 의도 명확화), 인프라(DSBR)가 *필드 동기화* 담당."*

**박제 대비 (D1 시도 + BLOCKED로 발견)**:

| 본 phase 가설 (D1 시도 전) | 실제 결과 (D1 BLOCKED, 2026-05-02) |
|---|---|
| 인프라(`DefaultSingletonBeanRegistry`)가 *증상 보정* (필드 reflection 복사) → **계층 오염** → BPP가 *원천* 보장하면 인프라 우회 코드 제거 가능 | byte-buddy 서브클래싱 패턴에서 `early ≠ created` 항상 성립 (early=프록시 인스턴스, created=원본 인스턴스) → `@Autowired` 필드 주입은 created에만 적용 → BPP 캐시는 *재enhance 방지*만, 인프라의 *필드 동기화는 필수* → **책임 분담 패턴** |

**D1 시도가 박제한 학습** (Phase 4 진입 시 핵심 자산):
- 본 phase의 spec § 3.3.3 가설(*"DSBR 단순화 가능"*)이 *byte-buddy 서브클래싱 + 필드 복사* 패턴에서 성립 안 함을 *통합 회귀 fail*(`EarlyReferenceIntegrationTest` NPE)로 발견
- Spring 본가 *CGLIB wrap-around proxy* 패턴과 우리 *서브클래스 + 필드 복사* 패턴의 *근본 차이* 박제
- *"학습 정점이 가설로 시작 → 시도 → BLOCKED → 박제 재정의"* 자체가 *spec 작성 + 시도가 학습 콘텐츠*임을 시연 (Phase 4의 영속성 컨텍스트 + 프록시 작업에서 동일 함정 회피 자산)

### 0.3 배경 — Phase 3에서 노출된 Phase 1A 결함

Phase 3 (sfs-tx) 구현 중 Phase 1A 핵심 인프라(`sfs-beans`)의 *테스트로 검증되지 않은 결함 2건 + 디자인 우려 1건*이 자연 노출되어 Phase 3 작업 범위 안에서 *기능*만 보강됨. 본 phase는 그 잔여 부채를 청산.

| ID | 결함/우려 | Phase 3 처리 | 본 phase 처리 |
|---|---|---|---|
| **G1** | `DefaultListableBeanFactory.resolveBeanNameByType`가 `registerSingleton` 직접 등록 빈을 type lookup 시 못 찾음 | 기능 보강 (커밋 `6a8493b`) — `resolveBeansOfType`이 BeanDefinition + 직접 등록 합산 | 회귀 테스트 신설 (3건) |
| **G2** | `AbstractAutowireCapableBeanFactory.doCreateBean.factoryMethod` 분기가 *3차 캐시 등록 + populateBean 누락* | 기능 보강 (커밋 `a74a1f6`) — 풀 사이클 보강 + `copyFieldsToEarlyReference` 추가 | 회귀 테스트 신설 (3건) |
| **G3** | `DefaultSingletonBeanRegistry.copyFieldsToEarlyReference`가 *symptomatic* — BPP가 두 번 enhance하는 근본 원인 미해결 | (D2 추가 — *디자인 우려* 박제) | **부분 해결**: BPP `earlyProxyReferences` 캐시 도입 (재enhance 방지) — `copyFieldsToEarlyReference` 제거는 *D1 BLOCKED*로 보류 (§ 0.2 학습 정점 재정의 박제) |

---

## 1. 의사결정 로그

브레인스토밍에서 확정된 결정.

| # | 결정 | 선택지 | 채택 | 근거 |
|---|---|---|---|---|
| 1 | 범위 | A: G1+G2만 / B: G1+G2+G3 함께 / C: G1+G2 먼저 별도, G3 나중 | **B (함께)** | G3는 동작 변경. G1·G2 테스트가 G3의 회귀망 역할. Phase 4 진입 전 부채 청산 |
| 2 | G3 접근법 | ①: BPP earlyProxyReferences 캐시 (Spring 본가) / ②: enhanced 인스턴스 캐시 (key=원본 identity) / ③: DSBR에 BPP 콜백 도입 | **① (Spring 본가)** | 책임 위치 정합. `copyFieldsToEarlyReference` 원천 제거. BPP 자신의 enhance 추적 책임 |
| 3 | 모듈 경계 | a: sfs-beans + sfs-aop + sfs-tx만 / b: a + sfs-context 통합 / c: sfs-beans + 양 BPP 단위 1건씩만 | **a (좁은 경계)** | sfs-context 통합은 Phase 4가 자연 박제. 양 BPP는 *동일 패턴 적용 필수* — 한쪽만 고치면 미래 BPP 함정 재현. c는 G3 동작 변경 검증 약함 |
| 4 | 회귀 카운트 목표 | i: +8 / ii: +10 / iii: +14 | **ii (+10 PASS, 232 → 242)** | Phase 3 *"+48 추정 / 실제 +47 (98%)"* 박제 밀도 패턴 회수. 분기 변형 핵심만 추가 |

---

## 2. 아키텍처 & 모듈 구조

### 2.1 변경 모듈 3종

```
sfs-beans  ──► G1·G2 회귀 갭 보강 + DefaultSingletonBeanRegistry 단순화
sfs-aop    ──► AspectEnhancingBeanPostProcessor에 earlyProxyReferences 캐시 도입
sfs-tx     ──► TransactionalBeanPostProcessor에 동일 패턴 도입
```

본 phase는 *기존 의존 그래프를 깰 필요 없이* 각 모듈 내부 책임만 재분배.

### 2.2 의존 방향 (변경 없음)

```
sfs-samples ──► sfs-context ──► sfs-beans ──► sfs-core
       │              ▲                ▲
       │              │                │
       └─► sfs-tx ────┴────► sfs-aop ──┘
```

### 2.3 Spring 원본 매핑

| 우리 모듈/클래스 | Spring 원본 | 매핑 핵심 |
|---|---|---|
| `AspectEnhancingBeanPostProcessor.earlyProxyReferences` | `AbstractAutoProxyCreator.earlyProxyReferences` | `Map<String, Object>` (key=beanName, value=원본 bean) |
| `TransactionalBeanPostProcessor.earlyProxyReferences` | `AnnotationAwareAspectJAutoProxyCreator` 동일 상속 | 동일 패턴 |
| `DefaultSingletonBeanRegistry.getOrCreateSingleton` 단순화 | `DefaultSingletonBeanRegistry.getSingleton(name, factory)` | 본가는 *애초에 우회 코드 없음* — 정합 회복 |

### 2.4 Phase 식별자

| 항목 | 값 |
|---|---|
| spec 파일 | `docs/superpowers/specs/2026-05-02-phase1a-gap-design.md` (본 문서) |
| plan 파일 | `docs/superpowers/plans/2026-05-02-phase1a-gap.md` (예정) |
| 브랜치 (제안) | `feat/phase1a-gap` |
| Phase 위치 | Phase 3과 Phase 4 사이의 *중간 정리 phase* (정식 phase 번호 없음, 일종의 *부채 청산*) |

---

## 3. 핵심 변경

### 3.1 G1 — `resolveBeanNameByType` 회귀 갭 보강

| 항목 | 내용 |
|---|---|
| 기능 변경 | **없음** (이미 정합 — `resolveBeansOfType`이 BeanDefinition + 직접 등록 싱글톤 합산) |
| 신설 테스트 | `DefaultListableBeanFactoryTypeLookupTest` (3건) |
| 위치 | `sfs-beans/src/test/java/com/choisk/sfs/beans/support/` |

**테스트 시나리오 (3건)**:

1. `registerSingleton`만 사용한 직접 등록 빈에 대해 `getBean(SomeInterface.class)` 단일 매칭 반환
2. `BeanDefinition` + `registerSingleton` 혼합 상태에서 같은 타입 단일 매칭 반환 (`@Primary` 미사용)
3. 매칭 0건일 때 `NoSuchBeanDefinitionException` throw + 메시지에 타입명 포함

> **TDD 적용**: 적용 — 분기/예외 동작 검증. RED 단계는 *형식적*(이미 PASS)이지만 메시지/예외 타입 어서션을 통한 *행동 명세*로서의 가치 보존.

### 3.2 G2 — `doCreateBean.factoryMethod` 분기 회귀 갭 보강

| 항목 | 내용 |
|---|---|
| 기능 변경 | **없음** (이미 정합 — 3차 캐시 등록 + populateBean + initializeBean 풀 사이클) |
| 신설 테스트 | `FactoryMethodEarlyReferenceTest` (3건) |
| 위치 | `sfs-beans/src/test/java/com/choisk/sfs/beans/support/` |

**테스트 시나리오 (3건)**:

1. `factoryMethodName` 설정된 `BeanDefinition`으로 만든 빈이 *3차 캐시에 ObjectFactory 등록*되는지 검증 (`registry.lookup(name)` → `DeferredFactory` 반환)
2. `factoryMethod`로 만든 빈에 `propertyValues`로 의존성 지정 시 `populateBean`이 호출되는지 검증 (필드 주입 결과 확인)
3. **순환 의존**: `@Bean A → B`, `@Bean B → A` 두 BD 상호 의존 시 *같은 인스턴스* 반환 (early reference 활용)

> **TDD 적용**: 적용 — *3차 캐시 등록 분기*가 본질. 구성 자체가 까다로워 (factoryBean + factoryMethod + 순환) 박제 가치 큼.

### 3.3 G3 — BPP `earlyProxyReferences` 캐시 도입 (동작 변경 핵심)

#### 3.3.1 `AspectEnhancingBeanPostProcessor` 변경

```
필드 추가:
  Map<String, Object> earlyProxyReferences = new ConcurrentHashMap<>();

getEarlyBeanReference(bean, beanName):  ← SIABPP 인터페이스 구현 (현재 미구현 추정)
  earlyProxyReferences.put(beanName, bean);
  return enhanceIfNeeded(bean);   // 매칭 시 enhance, 아니면 원본

postProcessAfterInitialization(bean, beanName):  ← 분기 추가
  if (earlyProxyReferences.remove(beanName) != null) {
      // 이미 early에서 enhance 처리 — 원본 그대로 반환 (early가 1차로 승격됨)
      return bean;
  }
  return enhanceIfNeeded(bean);   // 기존 경로
```

> **사실 박제 (2026-05-02 grep 검증)**: 현재 `AspectEnhancingBeanPostProcessor`는 `BeanPostProcessor + BeanFactoryAware`만 구현, `SmartInstantiationAwareBeanPostProcessor`는 *미구현*. Phase 2B에서는 `TransactionalBeanPostProcessor`만 SIABPP였음. G3 변경의 첫 단계로 `AspectEnhancingBeanPostProcessor`를 SIABPP로 *승격*해야 본 phase 캐시 패턴 적용 가능.

#### 3.3.2 `TransactionalBeanPostProcessor` 변경

```
필드 추가:
  Map<String, Object> earlyProxyReferences = new ConcurrentHashMap<>();

getEarlyBeanReference(bean, beanName):  ← 분기 추가 (현재는 무조건 enhance)
  earlyProxyReferences.put(beanName, bean);
  return enhance(bean);   // 기존 enhance 호출

postProcessAfterInitialization(bean, beanName):  ← 분기 추가
  if (earlyProxyReferences.remove(beanName) != null) {
      return bean;   // 원본 그대로
  }
  return enhance(bean);   // 기존 경로
```

#### 3.3.3 `DefaultSingletonBeanRegistry` 단순화 — **D1 BLOCKED 박제 (2026-05-02)**

> **본 섹션의 가설은 D1 시도로 *반증*됨.** byte-buddy 서브클래싱 패턴에서 `early ≠ created`가 *항상 성립*해 `copyFieldsToEarlyReference`가 *필수*. § 0.2 학습 정점 재정의 참조.

**원래 가설 (D1 시도 전)**:

```
getOrCreateSingleton(name, factory):
  // ───────── 변경 후 (가설) ─────────
  Object early = earlySingletonObjects.get(name);
  Object toStore = (early != null) ? early : created;
  // ↑ early ≠ created 분기 자체가 사라짐 (BPP가 원천 보장 — 가설)

  ... (1차 캐시 저장, 2·3차 정리) ...
```

**D1 시도 결과 (2026-05-02)**:

| 단계 | 결과 |
|---|---|
| `copyFieldsToEarlyReference` 제거 + 분기 단순화 시도 | `EarlyReferenceIntegrationTest` 2건 FAIL (NullPointerException) |
| 근본 원인 | byte-buddy 서브클래스 프록시(`early`)와 원본 인스턴스(`created`)는 *항상 다른 인스턴스* — `@Autowired` 필드 주입은 `created`에만 적용, `early`의 대응 필드는 null 유지 |
| 해결 가능성 | byte-buddy를 *wrap-around delegation* 패턴으로 전환 시 가능 (별도 phase) — 본 phase 범위 초과 |
| 결정 | `copyFieldsToEarlyReference` *유지*, `getOrCreateSingleton` 분기 *유지*. BPP 캐시(B1, C1)의 *재enhance 방지*만으로 부분 진보 박제 |

**유지되는 현행 코드** (변경 없음 — D1 BLOCKED 후 그대로):

```java
Object early = earlySingletonObjects.get(name);
Object toStore;
if (early != null) {
    if (early != created) {
        copyFieldsToEarlyReference(early, created);   // ← 유지 (필드 동기화 필수)
    }
    toStore = early;
} else {
    toStore = created;
}
```

**부분 진보** (B1, C1 캐시 도입 효과):
- enhance 호출 *2회 → 1회*로 감소 (CPU 절약)
- *의도 명확화*: BPP가 자신의 enhance 추적 책임 (early reference에 대해서는 *재enhance하지 않음*) 코드로 박제
- DSBR의 `copyFieldsToEarlyReference`는 *symptomatic 봉합*이 아닌 *byte-buddy 패턴의 필수 인프라 책임*으로 재정의됨

#### 3.3.4 신설 테스트 (4건)

| 클래스 | 모듈 | 시나리오 | PASS |
|---|---|---|---|
| `AspectEnhancingBeanPostProcessorCacheTest` | sfs-aop | (a) `getEarlyBeanReference` 호출 후 `postProcessAfterInitialization` → enhance 0회 추가 / (b) `getEarlyBeanReference` 미호출 + `postProcessAfterInitialization` → enhance 진행 | 2 |
| `TransactionalBeanPostProcessorCacheTest` | sfs-tx | 동일 패턴 (a)/(b) | 2 |

> **TDD 적용**: 적용 — *동작 변경* 본질. enhance 호출 횟수가 어서션 대상 (스파이/카운터 패턴).

---

## 4. 데이터 흐름

### 4.1 변경 전 (현재 — symptomatic)

```
순환 의존: A → B → A,  A는 @Transactional

1. getBean("A") → instantiateBean(A) → A_원본 생성
2. registerSingletonFactory("A", () -> SIABPP.getEarlyBeanReference(A_원본))
3. populateBean(A) → @Autowired B → getBean("B")
4.   getBean("B") → instantiateBean(B) → ... → @Autowired A → getBean("A")
5.     getBean("A") 재진입 → 3차 캐시 hit → factory.getObject()
       → SIABPP.getEarlyBeanReference(A_원본) → enhance(A_원본)
       → A_enhanced#1 생성 → 2차 캐시 → B에 주입
6.   getBean("B") 종료 → B 완성
7. populateBean(A) 종료 → initializeBean(A_원본) → BPP.postProcessAfterInitialization
   → enhance(A_원본) → A_enhanced#2 생성  ← ⚠️ 두 번째 enhance
8. getOrCreateSingleton(A): early(A_enhanced#1) ≠ created(A_enhanced#2)
   → copyFieldsToEarlyReference(early, created)  ← ⚠️ 인프라 우회 코드
   → 1차 캐시에 A_enhanced#1 저장

결과: B에 주입된 인스턴스 = 1차 캐시 인스턴스 (필드 동기화로 봉합)
문제: A_enhanced#2는 GC 대상이지만 *생성 비용 + 필드 복사 비용* 발생, 인프라 계층 오염
```

### 4.2 변경 후 (BPP earlyProxyReferences 캐시 + DSBR 필드 동기화 *유지*)

> **D1 BLOCKED 후 정정 (2026-05-02)**: 원래 가설은 *DSBR 우회 코드 제거*였으나, byte-buddy 서브클래싱 패턴에서 `early ≠ created`가 항상 성립해 *필드 동기화가 필수*. BPP 캐시는 *재enhance 방지*만 담당.

```
순환 의존: A → B → A,  A는 @Transactional

1. getBean("A") → instantiateBean(A) → A_원본 생성
2. registerSingletonFactory("A", () -> SIABPP.getEarlyBeanReference(A_원본))
3. populateBean(A) → @Autowired B → getBean("B")
4.   getBean("B") → ... → @Autowired A → getBean("A")
5.     getBean("A") 재진입 → 3차 캐시 hit → factory.getObject()
       → SIABPP.getEarlyBeanReference(A_원본)
       → BPP: earlyProxyReferences.put("A", A_원본)  ← 🔑 추적 등록
       → enhance(A_원본) → A_enhanced 생성 → 2차 캐시 → B에 주입
6.   getBean("B") 종료 → B 완성
7. populateBean(A) 종료 → initializeBean(A_원본) → BPP.postProcessAfterInitialization
   → BPP: earlyProxyReferences.remove("A") != null → ✅ enhance 스킵 (재enhance 방지)
   → A_원본 반환 (created = A_원본, *필드 주입 적용된 상태*)
8. getOrCreateSingleton(A): early(A_enhanced) ≠ created(A_원본)
   → copyFieldsToEarlyReference(A_enhanced, A_원본)  ← 🔑 *필수* — early 인스턴스에 created 필드 동기화
   → 1차 캐시에 A_enhanced 저장

결과: B에 주입된 인스턴스 = 1차 캐시 인스턴스 = A_enhanced (단일, 필드 주입됨)
효과:
  - enhance 호출 1회로 감소 (B1+C1 캐시) — CPU 절약 + 의도 명확화 ✅
  - 인프라 우회 코드 제거 — *불가능* (byte-buddy 서브클래싱 패턴의 필수 책임) ❌
  - 책임 분담: BPP=재enhance 방지, DSBR=필드 동기화 (§ 0.2 학습 정점 재정의 참조)
```

---

## 5. 에러 처리

| 시나리오 | 정책 |
|---|---|
| `earlyProxyReferences` 동시 접근 | `ConcurrentHashMap` (Phase 1A `singletonObjects`와 동일 정책) |
| `getEarlyBeanReference` 미호출 + `postProcessAfterInitialization`만 진입 | 캐시 miss → `enhance` 진행 (기존 동작 보존 — 비순환 의존 케이스) |
| `getEarlyBeanReference` 호출 후 `postProcessAfterInitialization` *미*진입 | 캐시 누수 가능 — 다만 *createBean 흐름이 정상이면 항상 짝지어 호출됨* (Phase 1A `doCreateBean` 풀 사이클 정합). 비정상 종료 시 누수는 *프로세스 종료까지 살아있는 BPP 객체*에 한정 → 학습 phase 범위 내 무시 |
| `enhance` 자체 실패 (byte-buddy) | 기존 정책 유지 — `BeanCreationException` wrap (변경 없음) |
| `copyFieldsToEarlyReference` 제거 후 *외부 직접 호출자* 존재 가능성 | private 메서드라 외부 호출 불가 → grep 검증으로 충분 |

> **핵심**: 본 phase의 변경은 *예외 정책 변경 없음*. 모든 변경은 *기존 정책 위에서 enhance 호출 횟수만 줄임* — 회귀 위험 최소화.

---

## 6. 테스트 전략

### 6.1 TDD 적용/제외 매트릭스 (CLAUDE.md "TDD 적용 가이드" 정합)

| 컴포넌트 | TDD 적용/제외 | 이유 |
|---|---|---|
| G1 (3건) — `resolveBeanNameByType` 회귀 갭 | **적용** | 분기/예외 동작 (NoSuchBean 등). RED는 형식적이지만 메시지/예외 타입 어서션이 *행동 명세* 가치 |
| G2 (3건) — `factoryMethod` 분기 회귀 갭 | **적용** | *3차 캐시 등록 분기* 본질 검증. 순환 의존 시나리오는 셋업 자체가 박제 가치 큼 |
| G3-aop (2건) — `AspectEnhancingBeanPostProcessor` 캐시 | **적용** | *동작 변경* 본질. enhance 호출 횟수가 어서션 (스파이/카운터 패턴) |
| G3-tx (2건) — `TransactionalBeanPostProcessor` 캐시 | **적용** | 동일 |
| `DefaultSingletonBeanRegistry.copyFieldsToEarlyReference` 제거 | **제외 (회귀 검증)** | 코드 *삭제*만 — 기존 `EarlyReferenceIntegrationTest` (sfs-tx)가 자동 회귀망. 별도 테스트 불필요 |
| `getOrCreateSingleton` 분기 단순화 | **제외 (회귀 검증)** | 동작 동일, 분기 단순화. 기존 `DefaultSingletonBeanRegistryTest` + 통합 테스트가 회귀망 |

### 6.2 합계 추정

| 영역 | 신설 PASS |
|---|---|
| sfs-beans (G1) | +3 |
| sfs-beans (G2) | +3 |
| sfs-aop (G3) | +2 |
| sfs-tx (G3) | +2 |
| **합계** | **+10** |

**누적**: 232 PASS → **242 PASS** (의사결정 #4 목표 정합)

### 6.3 회귀망 — 본 phase 동작 변경의 안전망

| 기존 테스트 | 검증 대상 |
|---|---|
| `EarlyReferenceIntegrationTest` (sfs-tx) | 순환 의존 + `@Transactional` end-to-end — G3 변경의 *integration 회귀망* |
| `MockTransactionIntegrationTest` (sfs-tx) | Mock TM end-to-end — BPP 캐시 변경 회귀 검증 |
| `LoggingAspectIntegrationTest` (sfs-aop, Phase 2B) | `@Aspect` enhance 동작 — `AspectEnhancingBeanPostProcessor` SIABPP 승격 회귀 |
| `TransactionDemoApplicationTest` (sfs-samples) | Phase 3 시연 — H2 통합 회귀 |

> 본 phase는 *기존 회귀망 위에 새 안전망 +10건*을 얹는 구조. *기존 회귀가 깨지지 않으면 G3 변경은 의도한 단순화*임이 보증됨.

---

## 7. 한계 (의도된 단순화)

본 phase는 *Phase 1A 결함 보강 + Phase 4 진입 토대 정리*가 목적. 다음은 의도적 비목표:

- **`sfs-context` `@Configuration`/`@Bean` 순환 의존 통합 테스트**: 모듈 경계 *a* 결정 — Phase 4 (JPA + AppContext 사용 패턴) 진입 시 자연 박제
- **`@Aspect` + `@Transactional` *동시* enhance 시나리오**: 두 BPP 모두 `earlyProxyReferences` 보유하지만 *상호작용*은 별도 phase 후보. 본 phase는 *각 BPP 단독* enhance 박제만
- **prototype 빈의 `earlyProxyReferences` 동작**: prototype은 매번 호출되므로 캐시 의미 없음 — singleton scope만 캐시 (조건 분기 추가). 본 phase 박제 비목표
- **`AspectEnhancingBeanPostProcessor` SIABPP 승격의 의미 박제**: 단순 인터페이스 추가만 (§ 3.3.1 사실 박제). *왜 SIABPP가 enhance BPP의 표준 시그니처인가* 깊이 박제는 별도 phase
- **`copyFieldsToEarlyReference` 제거의 *전제 조건* 박제 — 본 phase 가정 오류 발견 (D1 BLOCKED, 2026-05-02)**: 본 phase는 BPP 캐시로 단일 인스턴스 *원천 보장*되면 제거 가능하다는 *전제* 위에서 시작했으나, *byte-buddy 서브클래싱 + 필드 복사* 패턴에서 `early ≠ created`가 항상 성립해 *전제가 깨짐*. § 3.3.3 D1 BLOCKED 박제 + § 0.2 학습 정점 재정의 참조.

- **byte-buddy 서브클래싱 vs CGLIB wrap-around proxy 패턴 차이 박제**: Spring 본가 `AbstractAutoProxyCreator`는 *원본에 필드 주입 → 그 원본을 프록시로 위임 wrap*하는 방식이라 `early == created` 가능. 우리는 *서브클래스 + 필드 복사* 방식이라 `early ≠ created` 항상 성립 → 인프라(DSBR)가 *필드 동기화 책임* 보유. 이 차이의 *깊은 박제*(예: byte-buddy를 wrap-around delegation으로 전환)는 *별도 phase* — 본 phase는 *발견 + 가설 재정의*에 그침.

---

## 8. Definition of Done

본 spec의 모든 산출물 완성 + 마감 게이트 통과. 12항목.

- [ ] 1. `AspectEnhancingBeanPostProcessor`를 `SmartInstantiationAwareBeanPostProcessor`로 승격 (§ 3.3.1 사실 박제 — 현재 미구현 확정)
- [ ] 2. `AspectEnhancingBeanPostProcessor`에 `earlyProxyReferences` 필드 + `getEarlyBeanReference` + `postProcessAfterInitialization` 캐시 분기 추가
- [ ] 3. `AspectEnhancingBeanPostProcessorCacheTest` 2건 추가 (cache hit 시 enhance 스킵 / cache miss 시 enhance 진행)
- [ ] 4. `TransactionalBeanPostProcessor`에 동일 패턴 추가 (`earlyProxyReferences` 필드 + `getEarlyBeanReference`/`postProcessAfterInitialization` 캐시 분기)
- [ ] 5. `TransactionalBeanPostProcessorCacheTest` 2건 추가
- [ ] ~~6. `DefaultSingletonBeanRegistry.copyFieldsToEarlyReference` 제거 + `import java.lang.reflect.Modifier`/`Field` 정리~~ → **D1 BLOCKED 박제 (2026-05-02)** — § 0.2 학습 정점 재정의 + § 3.3.3 D1 시도 결과 박제 + § 7 한계 추가 commit으로 대체
- [ ] ~~7. `DefaultSingletonBeanRegistry.getOrCreateSingleton` 분기 단순화 (early ≠ created 분기 제거)~~ → **D1 BLOCKED 박제** — `copyFieldsToEarlyReference`가 byte-buddy 서브클래싱 패턴에서 *필수 인프라 책임*임이 통합 회귀 fail로 확정. 분기 *유지*
- [ ] 8. `DefaultListableBeanFactoryTypeLookupTest` 3건 추가 (G1 회귀 갭)
- [ ] 9. `FactoryMethodEarlyReferenceTest` 3건 추가 (G2 회귀 갭)
- [ ] 10. `./gradlew :sfs-beans:test :sfs-aop:test :sfs-tx:test :sfs-samples:test` 전부 PASS — 회귀 232 → **242 PASS / 0 FAIL** (B1+C1의 +4 + A1+A2의 +6 = +10, D1 0건)
- [ ] 11. `./gradlew build` 전체 PASS
- [ ] 12. 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify 패스) 실행 후 기록 — Phase 3 패턴 회수

---

## 9. 후속 단계

1. **본 spec 사용자 리뷰** — 사용자 승인 후 plan 작성으로 이행 (`superpowers:writing-plans`).
2. **plan 작성** — 12 DoD 항목 task 매핑. 별도 세션 권장 (메모리 박제 *디자인/구현 세션 분리* 정합).
3. **plan 실행** — TDD 적용 항목은 `superpowers:test-driven-development` 사이클, 제외 항목은 컴파일 + 회귀 테스트 검증.
4. **마감 게이트** → main 머지 → Phase 4 (JPA 핵심) brainstorming 진입.
5. **이월 박제 후보**:
   - `@Aspect` + `@Transactional` 동시 enhance 시나리오 박제
   - `sfs-context` `@Configuration`/`@Bean` 순환 의존 통합 테스트 (Phase 4 자연 회수)
   - prototype 빈 BPP 캐시 정책 명시 (조건 분기 + 박제 코멘트)

---

## 10. Self-Review 체크리스트 (spec 작성자 자기검토)

**1. 의사결정 4건 본문 정합성**: 의사결정 #1~4가 § 0~9 본문 모든 섹션과 정합. § 1 표 + § 2.1/§ 6.2/§ 8 DoD 모두 일치. ✅

**2. 회귀 카운트 일관성**: § 6.2 (+10) = DoD 10번 ("242 PASS") 일치. 232 + 10 = 242. ✅

**3. 학습 정점 명시**: § 0.2 *"책임 위치 = BPP"* + § 4.1/4.2 데이터 흐름 변경 전후 대비로 정점 박제. *"왜 인프라가 아니라 BPP인가"* 가 § 0.2 박제 대비 표로 표현. ✅

**4. 모듈 의존 방향**: § 2.2 변경 없음 — sfs-beans/sfs-aop/sfs-tx 내부 책임만 재분배. 의존 그래프 자체 무변경. ✅

**5. TDD 적용/제외 정합성**: § 6.1 매트릭스가 CLAUDE.md *"TDD 적용 가이드"*의 동작 변경(G3) / 회귀 검증(`copyFieldsToEarlyReference` 제거 등) 분기 정합. RED 단계 형식적 박제 여부도 명시. ✅

**6. Phase 3/2B 자산 회수 검증**:
- Phase 3 *마감 게이트 패턴* 회수 (DoD 12번 — 다관점 리뷰 + 리팩토링 + simplify)
- Phase 2B *BPP enhance 패턴* 위에 캐시만 추가 (`enhanceIfNeeded`/`enhance` 메서드 자체 무변경)
- Phase 1A *3-level cache + SIABPP* 자산 위에 G3 변경 (인프라 단순화) ✅

**7. 한계 박제 (§ 7) 5항목**: 후속 phase 회수 가능 항목과 영구 박제 항목 분리. *왜 본 phase 범위 밖인가* 각 항목에 명시. ✅

**8. 자기 모순 없음**: G3 변경이 *기능 동일*인 G1/G2와 *기능 변경*인 G3-aop/G3-tx의 TDD 적용 결을 § 6.1에서 명확 분리. § 8 DoD에서도 *"코드 변경 없음 회귀 검증"*과 *"동작 변경 + 새 테스트"*가 task 단위로 분리. ✅

**9. 외부 의존 추가 없음**: 본 phase는 외부 라이브러리/런타임 의존 추가 없음 — 기존 byte-buddy + ConcurrentHashMap만 활용. CLAUDE.md *"외부 런타임 의존 추가"* 절차 무관. ✅

**10. 박제 우려 vs 동작 변경 분리**: G1/G2는 *기능 정합 + 회귀망 부재*, G3는 *디자인 우려 + 동작 변경*. 본 spec은 두 결을 § 0.3 표에서 명확 분리하고 § 3.1/3.2 (G1/G2) vs § 3.3 (G3)에서 깊이 차이로 박제. ✅
