# Phase 1B-β — sfs-context 처리기 3종 + 자동 주입 구현 플랜 (Phase 1 종료)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@Autowired` / `@PostConstruct` / `@PreDestroy` / `@Bean`이 동작하는 처리기 3종을 추가하여 Phase 1을 마감한다. 1B-α에서 골격만 깔아둔 `refresh()` 5/6단계가 실제 처리기 작업으로 채워지고, `AnnotationConfigApplicationContext`로 만든 컨테이너에서 자동 주입 + 라이프사이클 콜백이 모두 동작한다.

**Architecture:** `ConfigurationClassPostProcessor` (BFPP, byte-buddy enhance + `@Bean` 추출), `AutowiredAnnotationBeanPostProcessor` (IABPP, 필드/세터/생성자 3-경로 주입), `CommonAnnotationBeanPostProcessor` (BPP, `@PostConstruct`/`@PreDestroy`) 3종 신설. `sfs-beans`에 `BeanDefinition.factoryBeanName/factoryMethodName` 필드 + `DependencyDescriptor` + `DefaultListableBeanFactory.resolveDependency` 보강. `ConfigurationClassEnhancer`가 byte-buddy로 `@Configuration` 클래스를 enhance해 inter-bean reference 시 컨테이너 라우팅을 보장.

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL), JUnit 5, AssertJ. byte-buddy 1.14.x (1B-α에서 카탈로그 등록만 됨, 본 plan에서 첫 사용). `sfs-beans`에 ASM 기반 `AnnotationMetadataReader` (1A에 이미 있음) 활용 확장.

**Selected Spec:** `docs/superpowers/specs/2026-04-23-phase-1b-context-design.md` (Section 5 처리기 3종 / Section 6 테스트 전략 / Section 7.2 1B-β DoD 13항목)

**선행 조건:** Plan 1B-α 완료 (main 브랜치 `0f1f47f` 머지 시점 + sfs-core 25 + sfs-beans 48 + sfs-context 31 = 104 테스트 PASS) + 품질 게이트 통과 (1B-α plan 하단 게이트 기록 블록 참조)

**End state:** 아래 시나리오가 동작하는 컨테이너. Phase 1 종료 시점.

```java
// Plan 1B-β 종료 시점 통과해야 하는 시나리오
@Configuration
class AppConfig {
    @Bean public Repo repo() { return new Repo(); }
    @Bean public Service service() { return new Service(repo()); } // inter-bean: getBean("repo")로 라우팅
}

@Component class Worker {
    @Autowired Service service;
    @PostConstruct void init() { /* 호출됨 */ }
    @PreDestroy void cleanup() { /* close() 시 호출됨 */ }
}

var ctx = new AnnotationConfigApplicationContext(AppConfig.class, Worker.class);
Worker w = ctx.getBean(Worker.class);
assertThat(w.service).isNotNull();
assertThat(w.service.repo).isSameAs(ctx.getBean(Repo.class));  // 동일 싱글톤
ctx.close();  // @PreDestroy 호출 + shutdown hook 정리
```

**Plan 1B-α와 다른 운영 차이:**
- byte-buddy 첫 *사용* (1B-α에선 카탈로그 등록만 했음). enhance 클래스 검증 시 `getClass().getSimpleName()` 직접 비교 금지 (`$$EnhancerByByteBuddy$$` 접미사). 테스트는 `getSuperclass()` 또는 `getDeclaredField`/`getDeclaredMethod`로 검증.
- `sfs-beans`에 의존성 해석 도입 (Plan 1A는 `getBean(String)` 단일 진입만, Plan 1B-β는 `resolveDependency(DependencyDescriptor)` 신설).
- 1B-α 품질 게이트 `/simplify` 패스에서 1B-β로 이월된 4건(부록 B1~B4)을 본 plan task에 통합 — 각각 적절한 섹션에 배치.
- 처리기 3종은 `AnnotationConfigApplicationContext` 생성자에서 자동 등록되므로, 1B-α의 빈 hook(`registerBeanPostProcessors`/`invokeBeanFactoryPostProcessors`)이 본 plan에서 *실제로* 동작 시작.

---

## 섹션 구조 (Task 한 줄 요약)

| 섹션 | 범위 | Task | TDD |
|---|---|---|---|
| **A** | `sfs-beans` 선결 보강 — `BeanDefinition` factoryMethod 모드 + `createBean` 분기 | A1~A2 | 모두 적용 |
| **B** | `sfs-beans` 의존 해석 보강 — `DependencyDescriptor` + `resolveDependency` + `@Lazy` 회귀 + simplify B4 atomic | B1~B4 | 모두 적용 |
| **C** | `sfs-context` 애노테이션 3종 정의 — `@Autowired`/`@PostConstruct`/`@PreDestroy` | C1 | 제외 (메타정보) |
| **D** | `AnnotationConfigUtils` + 처리기 자동 등록 (simplify B3 통합) | D1~D2 | 일부 적용 |
| **E** | `ConfigurationClassPostProcessor` + `ConfigurationClassEnhancer` (byte-buddy) + ASM 사전 필터 (simplify B1+B2) | E1~E4 | 모두 적용 |
| **F** | `AutowiredAnnotationBeanPostProcessor` — 필드/세터/생성자 + `required=false` + 컬렉션 + 다수 후보 폴백 | F1~F6 | 모두 적용 |
| **G** | `CommonAnnotationBeanPostProcessor` — `@PostConstruct` 호출 + `@PreDestroy` 등록 | G1~G2 | 모두 적용 |
| **H** | 통합 테스트 — design doc 6.3 1B-β 필수 6선 | H1~H6 | 적용 (통합) |
| **I** | 마감 + 품질 게이트 준비 — 회귀 + README + DoD 갱신 | I1~I3 | 제외 (마감) |

총 **30 Task**. 누적 테스트 카운트 예상: 1B-α 완료 시점 104 → 1B-β 완료 시점 **~129~134** (1B-β +25~30).

**simplify 이월 4건의 task 매핑 (1B-α 품질 게이트 기록 → 본 plan):**
- B1 (ASM 사전 필터) → 섹션 E의 Task E4
- B2 (`ClassUtils.forName`) → 섹션 E의 Task E4 (B1과 같은 작업)
- B3 (`getBeanFactory()` 경로 통일) → 섹션 D의 Task D1 (`AnnotationConfigUtils` 도입)
- B4 (`registerSingleton` atomic) → 섹션 B의 Task B4

---

## 섹션 A: `sfs-beans` 선결 보강 — factoryMethod 모드 (Task A1~A2)

### Task A1: `BeanDefinition`에 `factoryBeanName`/`factoryMethodName` 필드 추가

> **TDD 적용 여부:** 적용 — 분기 진입 조건이 본질 (factoryMethodName != null 분기).

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanDefinition.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/BeanDefinitionFactoryMethodTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task A2: `AbstractAutowireCapableBeanFactory.createBean`에 factoryMethod 분기 추가

> **TDD 적용 여부:** 적용 — 인스턴스화 경로의 본질적 분기. constructor 경로 대신 `factoryBean.factoryMethod(args)` 호출로 라우팅.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/AbstractAutowireCapableBeanFactory.java` (또는 `DefaultListableBeanFactory.createBean` 위치)
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/CreateBeanFactoryMethodTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 B: `sfs-beans` 의존 해석 보강 (Task B1~B4)

### Task B1: `DependencyDescriptor` 신설

> **TDD 적용 여부:** 적용 — `required` 플래그 + 제네릭 추출 분기가 본질.

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/DependencyDescriptorTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task B2: `DefaultListableBeanFactory.resolveDependency` 신설

> **TDD 적용 여부:** 적용 — List/Map/단일/다수 후보/required=false 분기. 의존 해석 코어.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/ResolveDependencyTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task B3: `@Lazy` skip 회귀 검증 (1B-α 보강 재확인)

> **TDD 적용 여부:** 적용 — 1B-α에서 추가된 분기가 1B-β 변경 후에도 PASS 유지 확인.

**Files:**
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/LazyInitSkipRegressionTest.java` (또는 1B-α의 `LazyInitializationTest` 그대로 회귀 PASS 확인)

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task B4: `registerSingleton`의 DisposableBean 감지를 `singletonLock` 안으로 atomic화 *(simplify 이월 B4)*

> **TDD 적용 여부:** 적용 — 동시성 안전성. 현재는 `singletonObjects.put`과 `disposableBeans.put`이 분리된 critical section.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java:34-44`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/RegisterSingletonAtomicTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 C: `sfs-context` 애노테이션 3종 정의 (Task C1)

### Task C1: `@Autowired` / `@PostConstruct` / `@PreDestroy` 정의

> **TDD 적용 여부:** 제외 — 메타정보만. 컴파일 + 회귀 테스트만 검증.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Autowired.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/PostConstruct.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/PreDestroy.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 D: `AnnotationConfigUtils` + 처리기 자동 등록 (Task D1~D2)

### Task D1: `AnnotationConfigUtils` 신설 — 처리기 3종 자동 등록 헬퍼 *(simplify 이월 B3 통합)*

> **TDD 적용 여부:** 적용 — 등록 헬퍼의 멱등성 (이미 등록된 처리기 중복 등록 방지) 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotationConfigUtilsTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움. simplify 이월 B3: `getBeanFactory()` 직접 전달 vs `registerBeanDefinition` 위임 경로를 본 헬퍼로 통일)*

---

### Task D2: `AnnotationConfigApplicationContext` 생성자에 처리기 3종 자동 등록 추가

> **TDD 적용 여부:** 제외 — 위임 호출만. 통합 테스트 (섹션 H)로 검증.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigApplicationContext.java`

> *(Step 1-5 본문은 후속 위임에서 채움. 1B-α에서 의도적으로 비워둔 자리에 `AnnotationConfigUtils.registerAnnotationConfigProcessors(this)` 한 줄 추가)*

---

## 섹션 E: `ConfigurationClassPostProcessor` + `ConfigurationClassEnhancer` (Task E1~E4)

### Task E1: `ConfigurationClassEnhancer` 신설 — byte-buddy enhance + intercept

> **TDD 적용 여부:** 적용 — enhance 결과가 컨테이너 라우팅을 보장한다는 본질.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassEnhancer.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassEnhancerTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task E2: `ConfigurationClassPostProcessor` 신설 — `@Bean` 메서드 → BeanDefinition 변환

> **TDD 적용 여부:** 적용 — `@Bean` 추출, factoryBean/Method BD 생성, scope/lazy 적용 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessorTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task E3: `proxyBeanMethods=false` 분기 — enhance 생략

> **TDD 적용 여부:** 적용 — `@Configuration(proxyBeanMethods=false)`일 때 enhance 스킵 분기.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessorTest.java` (테스트 추가)

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task E4: `ClassPathBeanDefinitionScanner`에 ASM 사전 필터 도입 *(simplify 이월 B1 + B2)*

> **TDD 적용 여부:** 적용 — `Class.forName` 전에 `AnnotationMetadataReader`로 메타-필터링하는 새 분기. 메타 2-depth 처리 한계 명문화.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/ClassPathBeanDefinitionScanner.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ClassPathBeanDefinitionScannerAsmFilterTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움. simplify 이월 B1: ASM 사전 필터 / B2: `ClassUtils.forName(name, getDefaultClassLoader())`로 클래스로더 정책 명확화)*

---

## 섹션 F: `AutowiredAnnotationBeanPostProcessor` (Task F1~F6)

### Task F1: 필드 주입 (reflection field.set)

> **TDD 적용 여부:** 적용 — `@Autowired` 필드 발견 + `resolveDependency` 호출 + reflection 주입 경로.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredFieldInjectionTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task F2: 세터 주입 (setter 호출)

> **TDD 적용 여부:** 적용 — `@Autowired` 세터 발견 + 인자 해석 + 호출 분기.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredSetterInjectionTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task F3: 생성자 주입 + 단일 ctor 자동 검출 (Spring 4.3+ 정책)

> **TDD 적용 여부:** 적용 — `determineCandidateConstructors` 분기 (명시 `@Autowired` 우선, 단일 non-default ctor 자동, 그 외 null).

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/AbstractAutowireCapableBeanFactory.java` (또는 createBean 위치)
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredConstructorInjectionTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task F4: `required=false` 분기 — 매칭 빈 없을 때 null 주입

> **TDD 적용 여부:** 적용 — `DependencyDescriptor.required` 플래그 + null 허용 분기.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredRequiredFalseTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task F5: `List<T>` / `Map<String, T>` 컬렉션 주입

> **TDD 적용 여부:** 적용 — `resolveDependency` 안의 List/Map 분기. 모든 매칭 빈 수집.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java` (List/Map 분기 추가)
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredCollectionInjectionTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task F6: 다수 후보 시 `@Primary` → `@Qualifier` → 필드명 폴백

> **TDD 적용 여부:** 적용 — `determineAutowireCandidate` 폴백 3단 본질.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredPrimaryQualifierFallbackTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 G: `CommonAnnotationBeanPostProcessor` (Task G1~G2)

### Task G1: `@PostConstruct` 호출 (BPP:before 시점)

> **TDD 적용 여부:** 적용 — 라이프사이클 5단계 호출 시점이 본질. 1A의 `awareName → BPP:before → afterProps → customInit → BPP:after` 순서 보존.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/PostConstructInvokeTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task G2: `@PreDestroy` 등록 + 등록 역순 호출

> **TDD 적용 여부:** 적용 — `registerDisposableBean` 등록 시점 + LIFO 순서가 본질. 1B-α의 `DefaultSingletonBeanRegistry.destroySingletons` LIFO와 결합.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/PreDestroyOrderTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 H: 통합 테스트 — 1B-β 필수 6선 (Task H1~H6)

### Task H1: `ConfigurationInterBeanReferenceTest.interBeanReferenceReturnsSameInstance`

> **TDD 적용 여부:** 적용 (통합) — byte-buddy enhance 검증의 핵심. `@Configuration` 안의 `methodA()`가 `methodB()`를 호출해도 `getBean("methodB")`로 라우팅.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/ConfigurationInterBeanReferenceTest.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/config/AppConfig.java` (샘플 Config + Bean 메서드)

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task H2: `AutowiredCollectionInjectionTest.listInjectionContainsAllMatchingBeans`

> **TDD 적용 여부:** 적용 (통합) — `List<Handler>` 주입에 모든 `@Component class XxxHandler`가 들어가는지.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/AutowiredCollectionInjectionTest.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/autowired/HandlerSamples.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task H3: `PrimaryAndQualifierTest.primaryWinsOverOthers` + `qualifierOverridesPrimary`

> **TDD 적용 여부:** 적용 (통합) — 다수 후보 폴백 3단의 우선순위.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/PrimaryAndQualifierTest.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/autowired/MultiCandidateSamples.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task H4: `LazyInitializationTest` 회귀 — 1B-β 시점에서도 PASS 유지

> **TDD 적용 여부:** 제외 — 1B-α 테스트(`sfs-context/src/test/java/com/choisk/sfs/context/integration/LazyInitializationTest.java`)가 1B-β 변경 후에도 그대로 PASS 함만 확인.

**Files:**
- Verify: `sfs-context/src/test/java/com/choisk/sfs/context/integration/LazyInitializationTest.java` (수정 없음, 회귀만)

> *(Step 1-5 본문은 후속 위임에서 채움. 새 테스트 추가 없음 — 단순 회귀 확인)*

---

### Task H5: `PostConstructPreDestroyOrderTest.postConstructFiresAfterAwareBeforeAfterProps`

> **TDD 적용 여부:** 적용 (통합) — 라이프사이클 콜백 순서. 1A `BeanLifecycleOrderTest`와 결합되어 5단계 검증.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/PostConstructPreDestroyOrderTest.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/lifecycle/LifecycleSamples.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

### Task H6: `AutowiredRequiredFalseTest.requiredFalseAllowsNullDependency`

> **TDD 적용 여부:** 적용 (통합) — `@Autowired(required=false)`로 매칭 빈 없을 때 null 주입.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/AutowiredRequiredFalseTest.java`

> *(Step 1-5 본문은 후속 위임에서 채움)*

---

## 섹션 I: 마감 + 품질 게이트 준비 (Task I1~I3)

### Task I1: Plan 1B-β 회귀 테스트 + 빌드 검증

> **TDD 적용 여부:** 제외 — 회귀 + 빌드 명령어만.

**Files:** *(테스트 카운트 / 빌드 결과를 본 plan 문서 하단 실행 기록 블록에 기록)*

> *(Step 1-3 본문은 후속 위임에서 채움. `./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test` + `./gradlew build` 모두 PASS / 누적 ~129~134건 PASS 확인)*

---

### Task I2: `sfs-context/README.md` 업데이트 — 1B-β 시점 동작 시나리오

> **TDD 적용 여부:** 제외 — 문서.

**Files:**
- Modify: `sfs-context/README.md`

> *(Step 1-3 본문은 후속 위임에서 채움)*

---

### Task I3: Plan 1B-β DoD 체크리스트 갱신 + 최종 커밋

> **TDD 적용 여부:** 제외 — 문서.

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` (DoD 14항목 모두 `[x]` + 실행 기록 블록 추가)

> *(Step 1-3 본문은 후속 위임에서 채움)*

---

## 🎯 Plan 1B-β Definition of Done — 최종 체크리스트

**기능적 DoD (12항목, design doc Section 7.2와 1:1 매칭):**

- [ ] 1. `@Configuration` + `@Bean` 클래스의 빈이 등록되며, `@Bean methodName()`이 빈 이름이 된다 (Task E2, H1)
- [ ] 2. `@Bean("custom")` 명시 시 그 이름이 우선한다 (Task E2)
- [ ] 3. `@Configuration` 클래스의 inter-bean reference는 컨테이너 싱글톤을 반환한다 (byte-buddy 검증) (Task E1, H1)
- [ ] 4. `@Configuration(proxyBeanMethods = false)`는 enhance를 생략한다 (Task E3)
- [ ] 5. `@Autowired` 필드 주입이 동작한다 (Task F1)
- [ ] 6. `@Autowired` 세터 주입이 동작한다 (Task F2)
- [ ] 7. 단일 non-default 생성자는 `@Autowired` 없이도 자동 검출된다 (Spring 4.3+) (Task F3)
- [ ] 8. `@Autowired(required=false)`는 매칭 빈이 없을 때 null을 주입한다 (Task F4, H6)
- [ ] 9. `List<T>`/`Map<String, T>` 컬렉션 주입이 동작한다 (Task F5, H2)
- [ ] 10. 다수 후보 시 `@Primary` → `@Qualifier` → 필드명 매칭 폴백 순서가 적용된다 (Task F6, H3)
- [ ] 11. `@PostConstruct` 메서드는 `BPP:before` 시점에 호출된다 (Task G1, H5)
- [ ] 12. `@PreDestroy` 메서드는 `close()` 시 등록 역순으로 호출된다 (Task G2, H5)

**품질 DoD:**

- [ ] 13. spec line 418~419의 Phase 1 종료 DoD 전 항목 만족 확인 (Task I1, I3)
- [ ] 14. simplify 이월 4건(B1 ASM 사전 필터, B2 `ClassUtils.forName`, B3 `getBeanFactory()` 경로 통일, B4 `registerSingleton` atomic) 모두 본 plan 내에서 반영 (Task B4, D1, E4)

---

## ▶ Plan 1B-β 완료 후 다음 단계

1. **CLAUDE.md "완료 후 품질 게이트" 3단계** — 다관점 코드리뷰 → 리팩토링 → `/simplify` 패스. 결과를 plan 문서 하단에 `> **품질 게이트 기록 (YYYY-MM-DD):**` 블록으로 기록 (1B-α 패턴).
2. **main 머지 게이트** — `feat/phase1b-processors` → main `--no-ff` 또는 GitHub PR (1A/1B-α 패턴 동일).
3. **Phase 1 종료 선언** — spec line 418~419의 Phase 1 DoD 전 항목 만족.
4. **Phase 2 시작** — `sfs-aop` (또는 다른 phase). 별도 brainstorming 필요.

---

## 부록 — simplify 이월 4건 추적 (1B-α 품질 게이트 → 본 plan)

본 plan 작성 시점(2026-04-25)에 1B-α 품질 게이트 `/simplify` 패스에서 이월된 4건. 각각이 본 plan의 어느 task에 통합되는지 명시:

| 이월 # | 영역 | 1B-α 품질 게이트의 이월 사유 | 본 plan 매핑 |
|---|---|---|---|
| **B1** | ASM 사전 필터 (`Class.forName` 전 `AnnotationMetadataReader`로 메타-필터) | 메타-애노테이션 2-depth 처리 한계로 동작 미세 변경 가능. 1B-β `ConfigurationClassPostProcessor` 도입 시 함께 정리 | **Task E4** |
| **B2** | `loadClass` → `ClassUtils.forName(name, getDefaultClassLoader())` | 클래스로더 정책이 호출 클래스 → 스레드 컨텍스트로 전환되는 미세 동작 변경 | **Task E4** (B1과 같은 작업으로 묶음) |
| **B3** | `getBeanFactory()` 직접 전달 vs `registerBeanDefinition` 위임 경로 통일 | 등록 경로 변경은 API 의미 변경. `AnnotationConfigUtils` 도입 시점에 정리 | **Task D1** |
| **B4** | `registerSingleton`의 DisposableBean 감지를 `singletonLock` 안으로 atomic화 | 정확성 관점 — 현재 호출 흐름에서 실제 race 없으나 구조적 허점. `sfs-beans` 동시성 강화 task로 분리 | **Task B4** |

> **결론:** 4건 모두 본 plan 내 적절한 task에 통합 완료. 1B-β 마감 시점에 **simplify 이월 잔여 = 0건**이 되어야 DoD #14 충족.

---

> **작성 상태 메모 (2026-04-25):** 본 plan은 골격(헤더 + 섹션 헤더 + task 헤드라인 + DoD)만 1차 작성된 상태. 각 task의 Step 1-5 본문(실패 테스트 코드 / 명령어 / 구현 코드 / 검증 / 커밋)은 분할 위임으로 점진적 추가 예정. 분할 위임 4번:
> - 위임 1: 섹션 A + B 본문 (6 task)
> - 위임 2: 섹션 C + D + E 본문 (7 task)
> - 위임 3: 섹션 F + G 본문 (8 task)
> - 위임 4: 섹션 H + I 본문 (9 task)
>
> 각 위임 완료 후 본 메모는 진행 상황 갱신. 모든 본문 채워지면 본 메모 삭제.
