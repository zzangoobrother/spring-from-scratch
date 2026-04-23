# Spring From Scratch — Phase 1: IoC 컨테이너 설계

| 항목 | 값 |
|---|---|
| 작성일 | 2026-04-19 |
| 상태 | 초안 → 사용자 리뷰 대기 |
| 범위 | Phase 1 (IoC 컨테이너) — 전체 6단계 로드맵 중 1단계 |
| 선행 의존 | 없음 |
| 후속 의존 | Phase 2 (AOP), Phase 3 (Transaction), Phase 4 (JPA), Phase 5 (Redis), Phase 6 (Kafka) 모두 이 스펙을 기반으로 확장 |

---

## 0. 프로젝트 개요

### 0.1 목표

Spring Framework의 핵심 구조를 **순수 Java 25로 직접 구현**하여, Spring의 내부 설계 원리를 깊이 이해한다. 학습이 주 용도이며, 이 컨테이너 위에 JPA 핵심, Redis/Kafka 통합을 단계적으로 확장해나간다.

### 0.2 전체 로드맵 (6단계)

| 단계 | 모듈 | 핵심 산출물 | 선행 의존 |
|---|---|---|---|
| **1** | IoC 컨테이너 | BeanDefinition, BeanFactory, ApplicationContext, 주요 애노테이션, 3-level cache | — |
| 2 | AOP | JDK Dynamic Proxy 기반, @Aspect/@Before/@Around | 1 |
| 3 | 트랜잭션 추상화 | PlatformTransactionManager, @Transactional | 1, 2 |
| 4 | JPA 핵심 | EntityManager, 영속성 컨텍스트, 변경 감지 | 1, 2, 3 |
| 5 | Redis 통합 | RedisTemplate, Serializer 추상화 | 1, (2) |
| 6 | Kafka 통합 | KafkaTemplate, Listener Container, @KafkaListener | 1, (2) |

각 단계는 **독립된 spec → plan → 구현 사이클**을 가진다. 이 문서는 1단계만 다룬다.

### 0.3 Phase 1 스코프 (한 줄 요약)

> "BeanPostProcessor 확장점까지 갖춘, 3-level cache 기반 순환참조 해결이 가능한 애노테이션 기반 DI 컨테이너."

---

## 1. 의사결정 로그 (Decisions Log)

브레인스토밍 과정에서 확정된 결정사항을 근거와 함께 기록한다.

| # | 결정 | 선택지 | 채택 | 근거 |
|---|---|---|---|---|
| 1 | Phase 1 스코프 | A: 미니멀 / B: 스프링 충실 / C: 풀스펙 | **B + FactoryBean** | BeanPostProcessor가 있어야 Phase 2~6가 "확장점에 꽂히는" 구조로 만들어짐. FactoryBean은 JPA EntityManagerFactory와 Redis ConnectionFactory 구현에 필수 |
| 2 | Java 버전 | 17 / 21 / 25 | **Java 25 (LTS)** | Scoped Values(JEP 506), Structured Concurrency(JEP 505) 정식 → 후속 Phase에서 ThreadLocal 대체 학습 소재가 풍부해짐. Spring(6.x도 Java 17 기준)보다 우리 학습 프레임워크가 더 모던 |
| 3 | 애노테이션 네이밍 | A: Spring 동명/다른 패키지 / B: 접두사 / C: JSR-330 | **A** | Spring 소스와 라인 단위 비교 학습 가능. 패키지만 다르면 충돌 없음. Spring도 `javax.inject.Inject`와 공존시키는 선례 |
| 4 | 빌드 도구/구조 | Gradle 멀티 / Gradle 단일 / Maven | **Gradle Kotlin DSL 멀티모듈** | 의존성 방향을 빌드시스템이 강제 → Spring의 `spring-beans` vs `spring-context` 분리 철학을 컴파일러로 체득 |
| 5 | 루트 패키지 | com / io / dev | **`com.choisk.sfs`** | 사용자 지정 |
| 6 | 테스트 전략 | 단위만 / 샘플만 / 이중 검증 | **이중 (JUnit 5 + 샘플 앱)** | 샘플 앱이 Spring과 교차 검증 가능 → "같은 코드가 두 컨테이너에서 동일하게 돈다"가 학습의 정점 |
| 7 | 내부 구현 스타일 | Legacy Faithful / Modern Native / Hybrid | **Hybrid** | 공개 API는 Spring과 동일(호환성), 내부는 Java 25 idioms(record, sealed, pattern matching) 활용. 주석에 Spring 원본 매핑 |
| 8 | `BeanDefinition` 형태 | record (immutable) / class (mutable) | **mutable class** | BeanFactoryPostProcessor가 인스턴스화 전에 definition을 수정할 수 있어야 함. Spring 철학 그대로 |
| 9 | `@PostConstruct`/`@PreDestroy` | Jakarta / 직접 정의 | **직접 정의** | 외부 런타임 의존: ASM(클래스패스 스캔), byte-buddy(`@Configuration` 클래스 enhance). 그 외 의존 추가는 spec 개정을 요구한다. "Spring이 왜 자기 애노테이션 쓰다가 Jakarta로 넘어갔는지"의 맥락까지 학습 |
| 10 | 확장점 전체 (BPP/IABPP/SIABPP/BFPP/Aware/InitializingBean·DisposableBean 6종) | Phase 1 포함 / Phase 2로 지연 | **Phase 1 포함** | 모든 후속 Phase가 여기 꽂히므로 지금 안 만들면 Phase 2 진입 시 대규모 리팩토링 필요. 특히 `SmartInstantiationAwareBeanPostProcessor`는 3-level cache의 `getEarlyBeanReference` 훅으로 Phase 2 AOP 프록시 조기 생성에 필수 |
| 11 | "생성 중" 추적 | ThreadLocal / ScopedValue | **ThreadLocal (Phase 1), ScopedValue 옵션(Phase 3+)** | Spring과 동일 출발점 확보 후 비교 학습 |
| 12 | 테스트 도구 | JUnit+AssertJ / +Mockito | **JUnit 5 + AssertJ (Mockito 제외)** | 컨테이너 테스트는 통합 성격이라 Mock보다 실제 빈 권장. AssertJ는 예외 메시지 검증 품질 향상 |
| 13 | `@Configuration` CGLIB 프록시 | Phase 1 포함 / Phase 2 연기 | **Phase 2 연기** | JDK Dynamic Proxy로는 불가능. Phase 2 AOP에서 ByteBuddy/CGLIB 도입 후 구현. "`@Bean` 메서드 간 호출은 일반 메서드 호출"이라는 한계를 문서화 |

---

## 2. 아키텍처 & 모듈 구조

### 2.1 모듈 구성

```
spring-from-scratch/
├── settings.gradle.kts
├── build.gradle.kts                   # Java 25 toolchain, JUnit 5, AssertJ 공통 설정
│
├── sfs-core/                          # [의존: 없음]
│   └── 책임: 리플렉션/스캔 유틸, AnnotationMetadata, 예외 기반 클래스
│
├── sfs-beans/                         # [의존: sfs-core]
│   └── 책임: BeanDefinition, BeanFactory 계층, FactoryBean,
│           BeanPostProcessor 인터페이스, 싱글톤 3-level cache
│
├── sfs-context/                       # [의존: sfs-core, sfs-beans]
│   └── 책임: ApplicationContext, AnnotationConfigApplicationContext,
│           ClassPathBeanDefinitionScanner, ConfigurationClassPostProcessor,
│           AutowiredAnnotationBeanPostProcessor,
│           @Component/@Configuration/@Bean 등 애노테이션
│
└── sfs-samples/                       # [의존: sfs-context 전이적으로 전부]
    ├── sample-hello-di/
    ├── sample-configuration/
    ├── sample-factorybean/
    ├── sample-postprocessor/
    ├── sample-circular-deps/
    └── sample-scopes/
```

### 2.2 의존성 방향 (Gradle이 강제)

```
sfs-samples ──► sfs-context ──► sfs-beans ──► sfs-core
```

역방향 import는 컴파일 에러. Spring이 `BeanFactory`를 `spring-beans`에, `ApplicationContext`를 `spring-context`에 둔 이유를 체득.

### 2.3 Spring 원본과의 매핑

| 우리 모듈 | Spring 원본 | 대응 클래스 예시 |
|---|---|---|
| `sfs-core` | `spring-core` | `ClassUtils`, `ReflectionUtils`, `AnnotationMetadata` |
| `sfs-beans` | `spring-beans` | `BeanFactory`, `DefaultListableBeanFactory`, `BeanDefinition`, `FactoryBean` |
| `sfs-context` | `spring-context` | `ApplicationContext`, `@Component`, `AnnotationConfigApplicationContext` |

---

## 3. 핵심 컴포넌트

### 3.1 `sfs-core`

| 타입 | 종류 | 책임 | Java 25 활용 |
|---|---|---|---|
| `Assert` | final class | `notNull`, `hasText`, `isAssignable` | — |
| `ClassUtils` | final class | `getDefaultClassLoader`, `forName`, `isAssignable` | — |
| `ReflectionUtils` | final class | `findMethod`, `findField`, `makeAccessible`, `invoke` | — |
| `ClassPathScanner` | interface + impl | `.class` 파일 경로 나열 (ASM으로 메타데이터만 읽음, 클래스 로딩 없음) | sealed result types |
| `AnnotationMetadata` | record | 클래스의 애노테이션 정보 | record |
| `BeansException` | sealed RuntimeException | 모든 컨테이너 예외의 루트 | sealed |

### 3.2 `sfs-beans` — 인터페이스 계층

```
BeanFactory                          getBean, containsBean, isSingleton, getType
  ├── HierarchicalBeanFactory        getParentBeanFactory
  ├── ListableBeanFactory            getBeanNamesForType, getBeansOfType
  └── AutowireCapableBeanFactory     autowire, resolveDependency, applyBeanPostProcessors...

ConfigurableBeanFactory              registerSingleton, addBeanPostProcessor, registerScope
  └── ConfigurableListableBeanFactory   (전체 결합)
```

### 3.3 `sfs-beans` — 메타데이터

| 타입 | 형태 | 설명 |
|---|---|---|
| `BeanDefinition` | **mutable class** | `beanClass`, `scope`, `lazyInit`, `primary`, `qualifier`, `constructorArgs`, `propertyValues`, `initMethod`, `destroyMethod`, `factoryBeanName`, `factoryMethodName` |
| `Scope` | sealed interface | `Singleton`, `Prototype` (Request/Session은 여지만) |
| `AutowireMode` | enum | `NO`, `BY_NAME`, `BY_TYPE`, `CONSTRUCTOR` |
| `BeanReference` | record | 다른 빈 참조 placeholder |
| `PropertyValue` | record | `name`, `value` 또는 `BeanReference` |

### 3.4 `sfs-beans` — FactoryBean

```java
public interface FactoryBean<T> {
    T getObject() throws Exception;
    Class<?> getObjectType();
    default boolean isSingleton() { return true; }
}
```

`getBean("&myFactory")` ← `&` 접두사로 FactoryBean 자신 조회. `getBean()` 내부에서 Java 25 pattern matching switch로 분기.

### 3.5 `sfs-beans` — 확장 지점 (Extension Points)

| 타입 | 역할 | 호출 시점 |
|---|---|---|
| `BeanPostProcessor` | 빈 인스턴스 감싸기 (프록시 등) | 초기화 전/후 |
| `InstantiationAwareBeanPostProcessor` | 인스턴스화 자체 가로채기 + 프로퍼티 주입 후킹 | 생성자 전/후, 프로퍼티 적용 단계 |
| `SmartInstantiationAwareBeanPostProcessor` | 조기 참조(early reference) 제공 시점 훅 | 3차 캐시 팩토리 실행 시 |
| `BeanFactoryPostProcessor` | BeanDefinition 자체 수정 | 모든 싱글톤 인스턴스화 전 |
| `BeanNameAware`, `BeanFactoryAware` | 자신의 이름/컨테이너 참조 받기 | 초기화 직전 |
| `InitializingBean`, `DisposableBean` | 초기화/소멸 콜백 (인터페이스 방식) | 초기화 시 / 종료 시 |

**후속 Phase에서 여기에 꽂힐 것:**
- AOP → `BeanPostProcessor`, `SmartInstantiationAwareBeanPostProcessor`
- `@Transactional` → `BeanPostProcessor` + `BeanFactoryPostProcessor`
- `@Autowired` 처리 → `InstantiationAwareBeanPostProcessor`
- `@Configuration` 처리 → `BeanFactoryPostProcessor`

### 3.6 `sfs-beans` — 구현 클래스

| 클래스 | 역할 |
|---|---|
| `DefaultSingletonBeanRegistry` | 3-level cache 관리 (`singletonObjects`, `earlySingletonObjects`, `singletonFactories`) |
| `AbstractBeanFactory` | `getBean()` 템플릿 메서드, 스코프 라우팅, FactoryBean `&` 분기 |
| `AbstractAutowireCapableBeanFactory` | 인스턴스화 → 프로퍼티 주입 → 초기화의 핵심 플로우 |
| `DefaultListableBeanFactory` | 최상위 구현체 — `BeanDefinition` 맵 보유, 타입별 조회 |

### 3.7 `sfs-context` — 애노테이션

```java
package com.choisk.sfs.context.annotation;

@Component                          // @Service, @Repository, @Controller는 meta-annotation
@Configuration
@Bean
@Autowired
@Qualifier("name")
@Primary
@Lazy
@Scope("singleton" | "prototype")
@PostConstruct
@PreDestroy
```

### 3.8 `sfs-context` — 핵심 클래스

| 클래스 | 역할 |
|---|---|
| `ApplicationContext` (interface) | `BeanFactory` 확장 (Phase 1은 BeanFactory 수준 기능만) |
| `ConfigurableApplicationContext` | `refresh()`, `close()` 등 라이프사이클 제어 |
| `AbstractApplicationContext` | `refresh()` 템플릿 메서드 — 8단계 플로우 |
| `GenericApplicationContext` | BeanDefinition을 직접 등록하는 범용 구현 |
| `AnnotationConfigApplicationContext` | 사용자 주 진입점 |
| `ClassPathBeanDefinitionScanner` | 패키지 스캔 → `@Component` 탐지 (ASM 메타데이터 기반) |
| `ConfigurationClassPostProcessor` | `@Configuration` 클래스의 `@Bean` 메서드 → BeanDefinition (BeanFactoryPostProcessor) |
| `AutowiredAnnotationBeanPostProcessor` | `@Autowired` 필드/생성자 주입 실행 (InstantiationAwareBeanPostProcessor) |
| `CommonAnnotationBeanPostProcessor` | `@PostConstruct` / `@PreDestroy` 실행 |

---

## 4. 빈 라이프사이클 & 데이터 흐름

### 4.1 `refresh()` — 8단계 (Phase 1 버전)

Spring 원본 12단계 중 이벤트·i18n·리스너를 제외한 8단계로 출발.

1. **prepareRefresh** — active 플래그, 타임스탬프
2. **obtainFreshBeanFactory** — `DefaultListableBeanFactory` 생성 + 패키지 스캔으로 BeanDefinition 등록
3. **prepareBeanFactory** — 기본 BeanPostProcessor들 등록 (Aware 처리용)
4. **postProcessBeanFactory** — 서브클래스 훅 (Phase 1은 no-op)
5. **invokeBeanFactoryPostProcessors** ⭐ — ConfigurationClassPostProcessor 실행 → `@Bean` 메서드를 BeanDefinition으로 등록
6. **registerBeanPostProcessors** ⭐ — 모든 BeanPostProcessor 타입 빈 인스턴스화 & 등록
7. **finishBeanFactoryInitialization** ⭐⭐ — lazy=false 싱글톤 전체 인스턴스화 (여기서 `createBean()` 내부 플로우가 빈별로 실행)
8. **finishRefresh** — close 시 destroy 호출을 위한 shutdown hook 등록

### 4.2 `createBean()` 내부 플로우 (빈 하나의 생성 과정)

```
getBean(name) 진입
  │
  ▼
[A] 싱글톤 캐시 조회 (3-level) → hit이면 바로 반환
  │ miss
  ▼
[B] createBean(name, definition)
  B-1. resolveBeforeInstantiation (InstantiationAwareBPP.before)
  B-2. 생성자 결정 & 인스턴스화 (ReflectionUtils)
  B-3. 3차 캐시 등록: singletonFactories.put(name, () -> getEarlyReference(this))
  B-4. populateBean → InstantiationAwareBPP.postProcessProperties (@Autowired 주입)
  B-5. initializeBean
    (a) Aware 콜백 (BeanNameAware → BeanFactoryAware)
    (b) BeanPostProcessor.beforeInit (@PostConstruct 실행)
    (c) InitializingBean.afterPropertiesSet / init-method
    (d) BeanPostProcessor.afterInit (AOP 프록시는 여기서, Phase 2)
  B-6. 1차 캐시 승격, 2차/3차 제거
  B-7. destroy 메서드 등록 (@PreDestroy / DisposableBean / destroy-method)
```

---

## 5. 3-Level Cache & 순환 참조 해결

### 5.1 3단계 캐시 구조

`DefaultSingletonBeanRegistry` 내부:

| 캐시 | 내용 | 언제 들어가나 | 언제 나가나 |
|---|---|---|---|
| 1차 `singletonObjects` | 완성된 싱글톤 | 초기화 완료 (B-6) | 컨테이너 종료 시 |
| 2차 `earlySingletonObjects` | 조기 노출된 참조 (팩토리가 실행되어 만든) | 3차에서 승격될 때 | 1차로 승격될 때 |
| 3차 `singletonFactories` | `() -> getEarlyReference(this)` 팩토리 | 생성자 직후 (B-3) | 2차로 승격되거나 완성될 때 |

조회 순서: **1차 → 2차 → 3차**. 3차 hit 시 팩토리 실행 후 2차로 승격하고 3차에서 제거 (조기 참조 단일성 보장).

### 5.2 순환 참조 해결 시나리오

`UserService ↔ OrderService` (필드/세터 주입):

1. `getBean("userService")` → `u0 = new UserService()`
2. 3차에 `user` 팩토리 등록
3. `u0.orderService` 주입 → `getBean("orderService")` 재귀
4. `o0 = new OrderService()` → 3차에 `order` 팩토리 등록
5. `o0.userService` 주입 → `getBean("userService")`
6. 1차/2차 miss, 3차 hit → 팩토리 실행 → `earlyUser` 생성 → 2차 승격
7. `o0.userService = earlyUser`
8. OrderService 초기화 (`proxyO`가 될 수도 있음), 1차 승격
9. UserService 주입 완료 (`u0.orderService = proxyO`)
10. UserService 초기화 — **이미 2차에 노출된 earlyUser가 있으면 그것을 최종으로 사용** (두 인스턴스 공존 방지)
11. 1차 승격

### 5.3 왜 3단계인가 (AOP와의 연결)

2단계 (완성 / 원본-직접)만 있으면, 후반 초기화에서 AOP가 빈을 프록시로 감쌀 때 **이미 주입된 원본과 최종 프록시가 달라짐**. 3단계는 **"조기 참조를 꺼내는 순간 AOP 훅(`SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference`)을 호출해 그때 프록시를 씌움"**으로써 이 문제를 해결한다. Phase 2가 여기 꽂힌다.

### 5.4 해결 불가능한 케이스

- **생성자 순환**: 생성자 진입 시점에 아직 `this` 인스턴스가 없음 → 3차 캐시에 등록할 객체 부재 → `BeanCurrentlyInCreationException`.
- 우회: 세터 주입으로 변경 / `@Lazy` 프록시 주입(Phase 2).

### 5.5 구현 스케치 (Approach 3)

```java
sealed interface CacheLookup {
    record Complete(Object bean) implements CacheLookup {}
    record EarlyReference(Object bean) implements CacheLookup {}
    record DeferredFactory(ObjectFactory<?> factory) implements CacheLookup {}
    record Miss() implements CacheLookup {}
}

var result = switch (lookupSingleton(name)) {
    case Complete(var b)         -> b;
    case EarlyReference(var b)   -> b;
    case DeferredFactory(var f)  -> {
        var early = f.getObject();
        promoteToSecondLevel(name, early);
        yield early;
    }
    case Miss()                  -> null;  // 호출자가 createBean 진입
};
```

"현재 생성 중" 추적: `ThreadLocal<Set<String>>` (Spring 동일 출발점). Phase 3+에서 `ScopedValue` 기반 비교 학습.

---

## 6. 예외 계층 & 에러 처리

### 6.1 예외 계층

```
BeansException (sealed, RuntimeException)
├── NoSuchBeanDefinitionException
├── NoUniqueBeanDefinitionException
├── BeanDefinitionStoreException
├── BeanCreationException (non-sealed)   ← Phase 2+에서 서브타입 추가 가능
│   ├── BeanCurrentlyInCreationException
│   ├── UnsatisfiedDependencyException
│   └── BeanInstantiationException
├── BeanNotOfRequiredTypeException
├── FactoryBeanNotInitializedException
└── BeanIsNotAFactoryException
```

### 6.2 에러 메시지 3원칙

모든 예외 메시지는 다음을 포함한다:

1. **무엇이 실패했는가** (구체 대상: 빈 이름, 클래스, 필드)
2. **왜 실패했는가** (원인)
3. **어떻게 고치는가** (행동 유도 힌트 — "Consider annotating with @Component", "Change to setter injection", "Add @Primary")

예시는 본 문서의 섹션 5에 수록된 세 가지 모델 메시지를 참조.

### 6.3 `BeanCreationContext` record — 구조화된 에러 전파

```java
public record BeanCreationContext(
    String beanName,
    Class<?> beanClass,
    CreationStage stage,              // sealed: Instantiating, Populating, Initializing
    List<String> creationChain        // 순환 에러 메시지용
) { }
```

예외 생성 시 context를 주입하여, 로깅/분석에서 구조화된 정보 접근 가능. Spring 원본보다 개선된 부분.

### 6.4 `refresh()` 실패 시 복구 (All-or-Nothing)

1. 이미 생성된 싱글톤 모두 `destroy()` 호출 (실패는 로그만, 다른 destroy는 계속)
2. BeanFactory 폐기
3. `active=false`, `closed=true` 플래그
4. 원본 예외 재전파

Phase 3 트랜잭션 커넥션 누수, Phase 6 Kafka consumer 고아 스레드 방지를 위해 Phase 1에서 필수 구현.

---

## 7. 테스트 전략 & 완료 정의

### 7.1 계층별 테스트

**`sfs-core`** — 유틸/인프라 단위 테스트 (ClassPathScanner, AnnotationMetadataReader, ReflectionUtils, Assert).

**`sfs-beans`** — 컨테이너 코어:
- DefaultSingletonBeanRegistry: 3-level cache 동작, 팩토리 1회 실행 보장
- DefaultListableBeanFactory: getBean 전 variant, `@Primary`/`@Qualifier`, 모든 예외 케이스
- 순환 참조 테스트 (최우선): 세터/필드 성공, 생성자 실패, 3-cycle 포함
- FactoryBean: 제품 반환, `&` 접두사, 싱글톤 캐싱
- 확장점: BFPP 수정이 인스턴스 반영, BPP 호출 순서, Aware 콜백 순서

**`sfs-context`** — 통합 테스트:
- ClassPathBeanDefinitionScanner, `@Configuration`+`@Bean`, `@Autowired` 전 주입 방식, `@PostConstruct`/`@PreDestroy`, refresh 전 플로우, 실패 시 복구

### 7.2 샘플 앱 (`sfs-samples/`)

| 샘플 | 내용 |
|---|---|
| `sample-hello-di` | 가장 단순한 `@Component` + `@Autowired` |
| `sample-configuration` | `@Configuration` + `@Bean` 팩토리 메서드 |
| `sample-factorybean` | FactoryBean 직접 구현, `&` 접두사 |
| `sample-postprocessor` | 커스텀 BeanPostProcessor 추적 로그 |
| `sample-circular-deps` | setter/field/constructor 3케이스 모두 |
| `sample-scopes` | singleton/prototype/lazy 시연 |

### 7.3 교차 검증 (Spring과 출력 비교)

선정 4개(`sample-hello-di`, `sample-configuration`, `sample-factorybean`, `sample-scopes`)는 Spring Boot 기반 짝을 두고 `CompareWithSpringTest`로 출력 동일성 검증.

`sfs-samples/*/src/test/`에서만 Spring 의존 허용. 메인 코드는 Spring 의존 0.

### 7.4 테스트 도구

| 도구 | 용도 |
|---|---|
| JUnit 5 | 전체 러너 |
| AssertJ | Fluent assertion (예외 메시지 `.hasMessageContaining` 검증) |
| Spring Boot (test 스코프만) | 교차 검증 참조 구현 |

Mockito는 사용하지 않는다 (통합 테스트 성격).

### 7.5 완료 정의 (Definition of Done)

**기능:**
- [ ] `AnnotationConfigApplicationContext(basePackages...)` / `(configClasses...)` 양쪽 동작
- [ ] `@Component`/`@Service`/`@Repository`/`@Controller`/`@Configuration`/`@Bean`/`@Autowired`/`@Qualifier`/`@Primary`/`@Lazy`/`@Scope`/`@PostConstruct`/`@PreDestroy` 모두 지원
- [ ] `FactoryBean<T>` + `&` 접두사
- [ ] 3종 BeanPostProcessor 확장점 동작
- [ ] 세터/필드 순환 참조 해결, 생성자 순환 적절한 예외
- [ ] `close()` 시 destroy 역순 호출

**품질:**
- [ ] 핵심 로직 단위 테스트 커버리지 90%+ (JaCoCo)
- [ ] 모든 샘플 `./gradlew run` 실행 가능
- [ ] 교차 검증 4종 통과 (출력 동일성)
- [ ] 모든 예외가 행동 유도 힌트 포함
- [ ] 각 모듈 README.md에 Spring 원본 매핑표

**학습 산출물:**
- [ ] 본 설계 문서 (docs/superpowers/specs/)
- [ ] `docs/notes/` 구현 중 통찰 노트 (선택, Phase 2~6 참조용 강력 추천)

---

## 8. 명시적 Out of Scope

Phase 1에서 **의도적으로 제외**한 것들:

- 이벤트 시스템 (`ApplicationEventPublisher`, `@EventListener`) → Phase 5 고려
- `Environment`, `@Value`, `@PropertySource`, 프로파일 → 필요 시 추가
- `MessageSource` (i18n) → 영구 제외
- XML 설정 → 영구 제외
- `FactoryBean`의 복잡한 제네릭 해결 → 기본 케이스만
- `@Configuration` 클래스의 CGLIB 프록시 → **Phase 2 연기 (한계 문서화)**
- 자동 프록시 `@EnableAspectJAutoProxy` → Phase 2
- `@Conditional`, `@Profile` → Phase 3+ 재평가

---

## 9. 부록: Spring 원본 매핑 요약

| 우리 타입 | Spring 원본 | 비고 |
|---|---|---|
| `com.choisk.sfs.beans.BeanFactory` | `org.springframework.beans.factory.BeanFactory` | 동일 시그니처 |
| `DefaultListableBeanFactory` | 동명 | 핵심 메서드 동일, 내부 record 사용 |
| `BeanDefinition` | `AbstractBeanDefinition` 계열 | mutable class, 일부 필드 축약 |
| `DefaultSingletonBeanRegistry` | 동명 | 3-level cache 로직 동일, pattern matching으로 재구성 |
| `AnnotationConfigApplicationContext` | 동명 | refresh 8단계 (원본 12단계에서 축소) |
| `@Component` | `org.springframework.stereotype.Component` | 동명, 다른 패키지 |

---

## 10. 후속 작업 (Phase 1 스펙 승인 후)

1. **Task 7:** `writing-plans` 스킬로 구현 플랜 작성 (모듈별 작업 순서, 체크포인트, 테스트 우선 순서)
2. 구현 시작 (별도 사이클)
3. Phase 1 완료 후 Phase 2 (AOP) 스펙 작성으로 진행

---

*이 문서는 Spring From Scratch 프로젝트의 Phase 1 설계 합의본이다. 승인 후 구현 플랜의 근거가 되며, 구현 도중 설계 변경이 필요하면 본 문서도 함께 업데이트한다.*
