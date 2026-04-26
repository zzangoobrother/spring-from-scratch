# Phase 2A — byte-buddy 도입 + `@Configuration` enhance + `@ComponentScan` + IoC 정리 설계 스펙

- **작성일:** 2026-04-26
- **페이즈:** Phase 2A (Phase 1 마감 직후, AOP 진입 전)
- **관련 문서:**
  - 상위 설계: `docs/superpowers/specs/2026-04-19-ioc-container-design.md`
  - 선행 plan: `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` (완료)
  - 선행 plan: `docs/superpowers/plans/2026-04-25-phase-1c-samples-demo.md` (완료, 138 PASS)
- **선행 조건:** Phase 1C 완료 (`feat/phase1c-samples` 브랜치, 138 PASS, 마감 게이트 통과). main 머지는 본 phase와 *직교* — 본 spec은 Phase 1C 머지 여부와 무관하게 진행 가능 (*권장은 머지 후*).
- **분리 phase:** Phase 2B (AOP 본체) — 본 spec 범위 외, 별도 spec/plan에서 처리.

---

## 1. 배경과 목표

### 1.1 Phase 1 마감 시점 상태

Phase 1 (1A/1B-α/1B-β/1C)을 통해 다음이 완성됨:

- 컨테이너 라이프사이클: `refresh()` 8단계 + `close()` LIFO destroy
- BeanFactory 계열: `AbstractBeanFactory`/`AbstractAutowireCapableBeanFactory`/`DefaultListableBeanFactory`
- 3-level cache (순환 참조 해결)
- 처리기 3종: `ConfigurationClassPostProcessor`(BFPP) / `AutowiredAnnotationBeanPostProcessor`(BPP) / `CommonAnnotationBeanPostProcessor`(BPP)
- 애노테이션: `@Configuration` / `@Bean` / `@Component` (+ stereotype 3종) / `@Autowired` / `@PostConstruct` / `@PreDestroy` / `@Primary` / `@Qualifier` / `@Scope` / `@Lazy`
- 데모 application (`sfs-samples`): `TodoDemoApplication.main()` + `EnhanceAbsenceDemo.main()` + 통합 테스트 박제

회귀 138 PASS / 0 FAIL. 그러나 **두 가지 거짓말**이 코드에 남아있다:

1. **`@Configuration.proxyBeanMethods=true`가 동작하지 않음** — 메타데이터만 존재. 사용자가 `false`로 바꿔도 동작 동일.
2. **`@ComponentScan` 애노테이션 부재** — 패키지 스캔은 가능하나 *생성자 인자*로만 (`new AnnotationConfigApplicationContext("pkg")`). `@Configuration` 클래스에 `@ComponentScan("pkg")` 다는 표준 Spring 패턴 미지원.

### 1.2 본 phase의 목적

`sfs-context`에 byte-buddy 기반 런타임 클래스 변형(enhance)을 도입하여 Phase 1의 두 거짓말을 해소한다. 동시에 Phase 1B-β/1C에서 박제된 정리 항목 3종을 *AOP 진입 전 cleanup pass*로 처리한다:

1. **`@Configuration` enhance** — byte-buddy로 `@Configuration` 클래스의 `@Bean` 메서드 호출을 컨테이너 라우팅으로 변형. `proxyBeanMethods=true`가 *진짜로 동작*함.
2. **`@ComponentScan` 애노테이션 + 처리** — 표준 Spring 패턴 도입. `ConfigurationClassPostProcessor`가 `@Configuration` 클래스의 `@ComponentScan(basePackages=...)` 발견 시 `ClassPathBeanDefinitionScanner` 호출.
3. **다운캐스팅 정리 2건** — Phase 1B-β 박제 🔵. `resolveDependency`를 `ConfigurableListableBeanFactory`로 승격 + BPP 처리기 생성자 파라미터 타입 변경. AOP 서브클래스 등장 시 `ClassCastException` 위험 사전 차단.
4. **IdGenerator/Clock 두 경로 통일** — Phase 1C 박제. `UserService`가 `Clock` 직접 주입 대신 `IdGenerator.nowInstant()` 사용. `IdGenerator`의 dead API 부활.
5. **데모 application 갱신** — `EnhanceAbsenceDemo` rename + 어서션 갱신 (false → true 마일스톤), `TodoDemoApplication`의 7 클래스 명시 등록을 `@ComponentScan`으로 축약.

### 1.3 비목표 (Out of Scope)

- **AOP 본체** (`@Aspect` / `@Around` / `@Pointcut` 등) — Phase 2B로 분리.
- **`sfs-aop` 모듈 신설** — Phase 2B에서 처리.
- **AspectJ 표현식 파서** — Phase 2B 또는 그 이후.
- **Cache/Transaction/Async 등 다른 enhance 후보** — Phase 2B 이후 별도.
- **byte-buddy의 모든 기능 학습** — `MethodDelegation` + `MethodInterceptor` 두 패턴만 사용.
- **고급 enhance 시나리오** (인터페이스 프록시, JDK Dynamic Proxy 비교 등) — 본 spec은 *클래스 기반 서브클래스 프록시*만 다룸.

---

## 2. Brainstorming 결정 박제 (2026-04-26)

| # | 질문 | 결정 | 핵심 함의 |
|---|---|---|---|
| Q1 | Phase 2 범위 분할 | **B** — 2-phase | 2A: byte-buddy 인프라 + 정리 / 2B: AOP 본체 |
| Q2 | byte-buddy 위치 | **B** — `sfs-context`에 의존 | sfs-aop 모듈은 Phase 2B에서 신설 |
| Q3 | IdGenerator/Clock 통일 | **a** — IdGenerator로 통일 | UserService가 `@Autowired IdGenerator`만 의존 |
| Q3-bis | EnhanceAbsenceDemo 운명 | **가** — rename + 출력 갱신 | 마일스톤 살림 (false → true) |
| Q4 | AOP 시연 방식 | **A** — logging advice (Phase 2B) | Phase 2A는 advice 추가 0건 |
| Q5 | task 진행 패턴 | **A'** — byte-buddy 핵심만 풀 사이클 | enhance 직접 닿는 3 task만 quality review |

---

## 3. 현재 인프라 상태 (spec 작성 시점 grep 결과)

### 3.1 이미 있는 것들 (재활용)

| 항목 | 위치 | 상태 |
|---|---|---|
| byte-buddy 카탈로그 | `gradle/libs.versions.toml` | `bytebuddy = "1.14.19"` 등록 |
| sfs-context의 byte-buddy 의존 | `sfs-context/build.gradle.kts:7` | `implementation(libs.bytebuddy)` 추가됨 |
| `@Configuration.proxyBeanMethods` 플래그 | `Configuration.java:14` | 메타데이터만 (동작 미구현) |
| `ClassPathBeanDefinitionScanner` | sfs-context/support | 패키지 스캔 + BD 등록 동작 |
| `AnnotationConfigApplicationContext(String...)` | sfs-context/support | `scan(packages) + refresh()` 동작 |
| `ComponentScanIntegrationTest` | sfs-context 통합 | 2건 PASS |
| `Phase1IntegrationTest` | sfs-context 통합 | enhance 부재 박제 (`directCallFormCreatesDistinctInstanceWithoutEnhance`) |

### 3.2 신규 작업 / 수정 작업

**신규 클래스:**
- `sfs-context/annotation/ComponentScan.java` — `@ComponentScan` 애노테이션
- `sfs-context/support/ConfigurationClassEnhancer.java` — byte-buddy 서브클래스 생성기
- `sfs-context/support/BeanMethodInterceptor.java` — `@Bean` 메서드 호출 인터셉터

**수정 클래스:**
- `sfs-context/support/ConfigurationClassPostProcessor.java` — `@ComponentScan` 처리 + enhance 적용
- `sfs-beans/ConfigurableListableBeanFactory.java` — `resolveDependency` 시그니처 승격 (다운캐스팅 정리)
- `sfs-context/support/AutowiredAnnotationBeanPostProcessor.java` — 생성자 파라미터 타입 변경
- `sfs-context/support/CommonAnnotationBeanPostProcessor.java` — 생성자 파라미터 타입 변경
- `sfs-context/support/AnnotationConfigUtils.java` — 위 두 BPP 생성자 호출부 다운캐스팅 제거
- `sfs-samples/...service/UserService.java` — `@Autowired Clock` 제거, `@Autowired IdGenerator`로 통일
- `sfs-samples/...todo/EnhanceAbsenceDemo.java` → `ConfigurationEnhanceDemo.java` rename + 출력 갱신
- `sfs-samples/...todo/EnhanceAbsenceDemoTest.java` → `ConfigurationEnhanceDemoTest.java` rename + 어서션 갱신
- `sfs-samples/...todo/TodoDemoApplication.java` — 7 클래스 명시 등록 → `@ComponentScan` 축약
- `sfs-context/...integration/Phase1IntegrationTest.java` — `directCallFormCreatesDistinctInstanceWithoutEnhance` 어서션 갱신 (false → true)
- `sfs-samples/.../config/AppConfig.java` — `@ComponentScan` 추가
- `sfs-samples/README.md` — Phase 2A 학습 가치 반영

---

## 4. byte-buddy 학습 메모

### 4.1 CGLIB와 byte-buddy

Spring 본가는 `@Configuration` enhance에 *CGLIB*를 사용한다. CGLIB는 ASM 위에 만든 *클래스 기반 동적 프록시* 라이브러리 — 인터페이스를 요구하지 않고 *임의 클래스의 서브클래스*를 런타임 생성한다. byte-buddy는 CGLIB의 후계격으로 *동일 능력 + 더 모던한 API*를 제공.

**왜 인터페이스 프록시(JDK Dynamic Proxy)가 아닌가:**
`@Configuration` 클래스는 일반적으로 인터페이스를 구현하지 않는다. JDK Dynamic Proxy는 인터페이스 메서드만 가로챌 수 있어 사용 불가. 따라서 *서브클래스 프록시*가 유일한 선택지.

**클래스 기반 프록시의 한계:**
- `final` 클래스는 서브클래스 불가 → enhance 불가
- `final` 메서드는 오버라이드 불가 → 인터셉트 불가
- `private` 메서드는 오버라이드 불가 → 인터셉트 불가
- 생성자는 *부모 생성자가 호출됨* → 부모 클래스의 부수 효과(field 초기화 등) 동일하게 발생

본 phase는 학습용이라 *위 제약을 수용*하고, `@Configuration` 클래스가 일반 클래스 + public 무인자 생성자 + non-final `@Bean` 메서드를 갖는다고 가정한다.

### 4.2 MethodInterceptor 모델

byte-buddy의 핵심 패턴:

```java
Class<?> enhancedClass = new ByteBuddy()
    .subclass(originalClass)
    .method(ElementMatchers.isAnnotatedWith(Bean.class))   // 어떤 메서드를 가로챌지
    .intercept(MethodDelegation.to(interceptor))           // 어떻게 처리할지
    .make()
    .load(originalClass.getClassLoader())
    .getLoaded();
```

`interceptor`는 일반 객체로, byte-buddy가 메서드 시그니처와 어노테이션을 보고 호출. 학습용 interceptor:

```java
public class BeanMethodInterceptor {
    @RuntimeType
    public Object intercept(
            @SuperCall Callable<Object> superCall,         // 원본 메서드 호출
            @Origin Method method,                          // 호출된 메서드 메타
            @AllArguments Object[] args                     // 호출 인자
    ) throws Exception {
        String beanName = resolveBeanName(method);
        if (beanFactory.containsSingleton(beanName)) {
            return beanFactory.getBean(beanName);          // 캐시된 빈 반환
        }
        return superCall.call();                            // 원본 메서드 본문 실행
    }
}
```

이 단순 모델로 *직접 호출 → 컨테이너 라우팅* 변형이 가능.

### 4.3 sfs에서의 사용 범위

본 phase에서 byte-buddy는 *오로지 `@Configuration` enhance에만* 사용. AOP의 advice 체인은 Phase 2B에서 *동일 byte-buddy*를 재활용 (학습 단위 응집). 본 phase의 enhance 코드는 **단일 인터셉터 + 단일 매칭 룰**로 압축 — Phase 2B의 advice 체인 도입 시 분기 확장이 자연스럽게 일어나도록 단순 구조 유지.

---

## 5. 컴포넌트 카탈로그

### 5.1 신규: `@ComponentScan` 애노테이션

```java
// sfs-context/src/main/java/com/choisk/sfs/context/annotation/ComponentScan.java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentScan {
    String[] value() default {};
    String[] basePackages() default {};
}
```

> **시연 요점:** `value`/`basePackages`는 *동의어* (Spring 본가 동일). 둘 다 비어있으면 *애노테이션 달린 클래스의 패키지*를 기본 스캔 — 본 phase에선 *명시 지정만* 지원하고 기본 추론은 Phase 2B 또는 이후로 미룸.

### 5.2 신규: `ConfigurationClassEnhancer`

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassEnhancer.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

public class ConfigurationClassEnhancer {

    private final ConfigurableListableBeanFactory beanFactory;

    public ConfigurationClassEnhancer(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Class<?> enhance(Class<?> configClass) {
        return new ByteBuddy()
                .subclass(configClass)
                .method(ElementMatchers.isAnnotatedWith(Bean.class))
                .intercept(MethodDelegation.to(new BeanMethodInterceptor(beanFactory)))
                .make()
                .load(configClass.getClassLoader())
                .getLoaded();
    }
}
```

### 5.3 신규: `BeanMethodInterceptor`

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/BeanMethodInterceptor.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public class BeanMethodInterceptor {

    private final ConfigurableListableBeanFactory beanFactory;

    public BeanMethodInterceptor(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @RuntimeType
    public Object intercept(
            @SuperCall Callable<Object> superCall,
            @Origin Method method,
            @AllArguments Object[] args
    ) throws Exception {
        String beanName = resolveBeanName(method);
        if (beanFactory.containsSingleton(beanName)) {
            return beanFactory.getBean(beanName);
        }
        return superCall.call();
    }

    private String resolveBeanName(Method method) {
        Bean ann = method.getAnnotation(Bean.class);
        if (ann != null && ann.name().length > 0 && !ann.name()[0].isEmpty()) {
            return ann.name()[0];
        }
        return method.getName();
    }
}
```

> **시연 요점:** `containsSingleton`으로 *완성된 싱글톤만* 체크 — 초기 생성 흐름에선 컨테이너가 직접 호출하므로 cache miss라서 `superCall`이 실행됨 (정상). 이후 `@Bean` 메서드 본문에서 다른 `@Bean` 메서드를 *직접 호출*할 때는 cache hit이 발생해 컨테이너 라우팅됨.

### 5.4 수정: `ConfigurationClassPostProcessor`

기존 (Phase 1B-β):
- `@Configuration` 클래스의 `@Bean` 메서드 → `factoryMethod` BD 등록만 수행

수정 후 (Phase 2A):
1. `@ComponentScan` 처리 — `@Configuration` 클래스에서 `@ComponentScan` 발견 시 `ClassPathBeanDefinitionScanner` 호출
2. **enhance 적용** — `proxyBeanMethods=true`인 `@Configuration` 클래스의 BeanDefinition을 *enhance된 서브클래스*로 교체

순서가 중요:
- ① `@ComponentScan` 먼저 처리 (BD 추가) → ② `@Bean` 메서드 → factoryMethod BD 등록 → ③ enhance 적용

```java
@Override
public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
    // 1. @ComponentScan 처리
    processComponentScans(bf);

    // 2. @Bean 메서드 → factoryMethod BD 등록 (기존 로직)
    String[] definitionNames = bf.getBeanDefinitionNames();
    for (String configName : definitionNames) {
        BeanDefinition bd = bf.getBeanDefinition(configName);
        if (bd.getBeanClass() == null || !bd.getBeanClass().isAnnotationPresent(Configuration.class)) continue;
        registerBeanMethods(bf, configName, bd);
    }

    // 3. enhance 적용 — proxyBeanMethods=true인 @Configuration 클래스
    enhanceConfigurationClasses(bf);
}
```

> **enhance 적용의 본질:** BD의 `beanClass`를 enhance된 서브클래스로 *교체*. 컨테이너가 `createBean()` 시점에 `Class.newInstance()`를 호출하면 enhance 클래스가 인스턴스화 → 모든 `@Bean` 메서드가 인터셉터 경유.

### 5.5 수정: `ConfigurableListableBeanFactory` + BPP 처리기 (다운캐스팅 정리)

**현재 문제:**
- `AutowiredAnnotationBeanPostProcessor(DefaultListableBeanFactory)` — 구현 클래스 직접 의존
- `CommonAnnotationBeanPostProcessor(DefaultListableBeanFactory)` — 동일
- `AnnotationConfigUtils` 내부 다운캐스팅 (`(DefaultListableBeanFactory) ctx.getBeanFactory()`)

**해결:**
- `ConfigurableListableBeanFactory`에 `resolveDependency(DependencyDescriptor)` 메서드 승격 (현재 `DefaultListableBeanFactory`에만 존재)
- BPP 처리기 생성자 파라미터를 `ConfigurableListableBeanFactory`로 변경
- `AnnotationConfigUtils`의 다운캐스팅 제거

학습 가치: AOP가 들어와 `BeanFactory`의 데코레이터 또는 서브클래스가 등장할 때 `ClassCastException`이 사전 차단됨.

### 5.6 수정: `sfs-samples`

#### 5.6.1 IdGenerator/Clock 통일

```java
// UserService.java — 변경 후
@Service
public class UserService {
    @Autowired UserRepository userRepo;
    @Autowired IdGenerator idGen;        // ← Clock 직접 주입 제거

    @PostConstruct
    void seedDefaultUser() {
        userRepo.save("기본 사용자", idGen.nowInstant());   // ← clock.instant() → idGen.nowInstant()
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) { return userRepo.save(name, idGen.nowInstant()); }
    public Optional<User> find(Long id) { return userRepo.findById(id); }
    public int total() { return userRepo.count(); }
}
```

> `AppConfig`의 `@Bean Clock systemClock()`은 그대로 유지 — `IdGenerator`의 매개변수 자동 주입을 위한 *유일한 시연 빈*. 학습 의도 보존.

#### 5.6.2 EnhanceAbsenceDemo → ConfigurationEnhanceDemo rename

파일/클래스/테스트 모두 rename. 출력문구 갱신:

```
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 적용): account.user == ctx.user → true
```

테스트 어서션도 두 줄 모두 `→ true`로 갱신. 첫 번째 줄은 라우팅 결과, 두 번째 줄은 *enhance 결과*. 같은 결과이지만 *경로가 다름*을 학습 메모로 박제.

> **마일스톤 의의:** Phase 1C에서 박제한 `→ false`가 Phase 2A 진입과 동시에 `→ true`로 바뀜. 추가 코드 없이 *Phase 2A 작업 자체가 증거*. 커밋 메시지에 "Phase 1C 박제 마일스톤이 살아 움직였음" 명시.

#### 5.6.3 TodoDemoApplication ComponentScan 적용

기존 (Phase 1C):
```java
new AnnotationConfigApplicationContext(
        AppConfig.class,
        UserRepository.class, TodoRepository.class,
        UserService.class, TodoService.class,
        UserController.class, TodoController.class)
```

변경 후 (Phase 2A):
```java
new AnnotationConfigApplicationContext(AppConfig.class)
```

`AppConfig`에 `@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")` 추가:

```java
@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
public class AppConfig {
    @Bean public Clock systemClock() { ... }
    @Bean public IdGenerator idGenerator(Clock clock) { ... }
}
```

> **시연 가치:** 학습자가 *Spring Boot의 `@SpringBootApplication`*에 익숙해지기 위한 다리 — `@ComponentScan`이 `@Configuration`과 함께 동작하는 표준 패턴. `TodoDemoApplication`은 한 줄 진입점으로 축약됨.

---

## 6. 데이터 흐름

### 6.1 `@Configuration` enhance 적용 시퀀스

```
new AnnotationConfigApplicationContext(AppConfig.class)
│
├─ register(AppConfig) → BeanDefinition 추가 (beanClass = AppConfig.class)
│
└─ refresh()
    └─ invokeBeanFactoryPostProcessors() (5단계)
        └─ ConfigurationClassPostProcessor.postProcessBeanFactory()
            ├─ ① @ComponentScan 처리 — AppConfig의 @ComponentScan 발견
            │   └─ ClassPathBeanDefinitionScanner.scan("com.choisk.sfs.samples.todo")
            │       → UserRepository, TodoRepository, UserService, ... 7 BD 추가
            ├─ ② @Bean 메서드 처리 — AppConfig의 systemClock(), idGenerator() 두 BD 추가
            └─ ③ enhance 적용 — AppConfig BD의 beanClass를 enhance된 서브클래스로 교체
                └─ ConfigurationClassEnhancer.enhance(AppConfig.class) → AppConfig$ByteBuddy$xxx

└─ finishBeanFactoryInitialization() (7단계)
    └─ preInstantiateSingletons() → 모든 BD 인스턴스화
        ├─ AppConfig → enhance 클래스 인스턴스화 (AppConfig$ByteBuddy$xxx)
        ├─ Clock → AppConfig.systemClock() 호출 (factoryMethod)
        │            ↑ enhance 인터셉터 경유: containsSingleton("systemClock") false → superCall → 신규 Clock 반환
        ├─ IdGenerator → AppConfig.idGenerator(clock) 호출
        │            ↑ 인터셉터 경유: containsSingleton("idGenerator") false → superCall (clock 인자는 컨테이너가 채움)
        └─ ... (나머지 빈)
```

**핵심:** enhance 인터셉터가 *컨테이너 자체의 호출은 통과시키고* (cache miss), `@Bean` 메서드 본문에서 *다른 @Bean 메서드를 직접 호출*할 때만 라우팅한다. 이 비대칭이 enhance의 본질.

### 6.2 `@ComponentScan` 처리 시퀀스

```
@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
class AppConfig { ... }

ConfigurationClassPostProcessor.processComponentScans():
  for 모든 @Configuration 클래스 BD:
    if @ComponentScan 보유:
      String[] basePackages = @ComponentScan.basePackages() ∪ @ComponentScan.value()
      ClassPathBeanDefinitionScanner.scan(basePackages)
        → @Component 메타 보유 클래스 → BD 등록
```

`AnnotationConfigApplicationContext(String...)` 생성자 경로(직접 패키지 인자)와 `@ComponentScan` 경로는 *동일한 `ClassPathBeanDefinitionScanner.scan()`*을 호출 — 코드 재사용.

### 6.3 EnhanceAbsenceDemo → ConfigurationEnhanceDemo 마일스톤 박제

Phase 1C의 박제 대상 코드 (변경 없음):

```java
@Configuration
public static class DirectCallConfig {
    @Bean public User user() { return new User(42); }
    @Bean public Account account() { return new Account(user()); }   // 직접 호출
}
```

| 시점 | account.user == ctx.user | 이유 |
|---|---|---|
| Phase 1C 끝 | **false** | enhance 미구현 → 직접 호출이 진짜 메서드 호출 → 새 인스턴스 |
| Phase 2A 끝 | **true** | enhance → 직접 호출이 인터셉터 경유 → 캐시된 User 반환 |

테스트 어서션이 *깨질 예정* — 이게 Phase 2A 작업의 *증거*. 마일스톤 박제 갱신 커밋 1건이 Phase 2A의 의의를 한 줄로 요약함.

---

## 7. 모듈 의존 정책

| 모듈 | 의존 (변경 없음) |
|---|---|
| `sfs-core` | (외부만) |
| `sfs-beans` | `sfs-core` |
| `sfs-context` | `sfs-beans` + **byte-buddy (이미 존재)** |
| `sfs-samples` | `sfs-context` |

**변경 없음.** byte-buddy는 1B-β 시점부터 sfs-context에 *implementation*으로 등록되어 있었으나 미사용. Phase 2A가 그 의존을 *처음 사용*하는 phase.

---

## 8. 테스트 전략

### 8.1 `ConfigurationClassEnhancer` 단위 테스트 (신규)

```java
@Test
void enhanceProducesSubclassOfOriginal() {
    Class<?> enhanced = enhancer.enhance(AppConfig.class);
    assertThat(AppConfig.class.isAssignableFrom(enhanced)).isTrue();
    assertThat(enhanced).isNotEqualTo(AppConfig.class);
}

@Test
void interceptorRoutesToContainerWhenBeanCached() {
    // BeanFactory mock 또는 실제 DefaultListableBeanFactory 사용
    // beanFactory.containsSingleton("user") → true 시 getBean 결과 반환 검증
}
```

### 8.2 `@Bean` inter-bean reference 통합 테스트 (수정)

`Phase1IntegrationTest.directCallFormCreatesDistinctInstanceWithoutEnhance`:
- 테스트 이름과 어서션 모두 갱신
- 신규 이름 후보: `directCallFormRoutesToContainerWithEnhance`
- 어서션: `account.user` 와 `ctx.getBean(User.class)`가 **같음** 검증

### 8.3 `@ComponentScan` 통합 테스트 (신규)

```java
@Test
void componentScanFromConfigurationDiscoversBeans() {
    try (var ctx = new AnnotationConfigApplicationContext(ScanFromConfig.class)) {
        assertThat(ctx.containsBean("simpleService")).isTrue();
    }
}

@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.context.samples.basic")
static class ScanFromConfig {}
```

### 8.4 sfs-samples 마일스톤 박제 갱신

- `EnhanceAbsenceDemoTest` rename → `ConfigurationEnhanceDemoTest`
- 두 어서션 모두 `→ true`로 갱신
- `TodoDemoApplicationTest` — 기존 8 라인 출력 시퀀스 *변동 없음* (출력 자체는 동일하므로 PASS 유지)
- `UserServiceTest` — `@Autowired Clock` 제거에 따른 ctx 등록 클래스 갱신 (단, AppConfig가 IdGenerator 빈 제공하므로 PASS 유지)

### 8.5 회귀 카운트 예상

| 모듈 | Phase 1C 종료 | Phase 2A 종료 (예상) | 변동 |
|---|---|---|---|
| sfs-core | 28 | 28 | 0 |
| sfs-beans | 58 | 58 | 0 |
| sfs-context | 44 | 47~48 | +3~4 (Enhancer 단위 +2, ComponentScan 통합 +1, 갱신 0) |
| sfs-samples | 8 | 8 | 0 (rename + 어서션 갱신, 카운트 변동 없음) |
| **합계** | **138** | **141~142** | **+3~4** |

> 만약 `BeanMethodInterceptor`/`ConfigurationClassPostProcessor` 확장에 단위 테스트가 더 추가되면 +5~6도 가능.

---

## 9. Definition of Done

**신규 컴포넌트:**

- [ ] 1. `@ComponentScan` 애노테이션 신설 (`sfs-context/annotation/ComponentScan.java`)
- [ ] 2. `ConfigurationClassEnhancer` 신설 + 단위 테스트
- [ ] 3. `BeanMethodInterceptor` 신설

**수정 — sfs-context:**

- [ ] 4. `ConfigurationClassPostProcessor` 확장 — `@ComponentScan` 처리 + enhance 적용
- [ ] 5. `Phase1IntegrationTest.directCallFormCreatesDistinctInstanceWithoutEnhance` 어서션 갱신 + 테스트명 갱신
- [ ] 6. `@ComponentScan` 통합 테스트 신설

**수정 — sfs-beans + sfs-context (다운캐스팅 정리):**

- [ ] 7. `ConfigurableListableBeanFactory.resolveDependency` 시그니처 승격
- [ ] 8. `AutowiredAnnotationBeanPostProcessor` + `CommonAnnotationBeanPostProcessor` 생성자 파라미터 타입 변경
- [ ] 9. `AnnotationConfigUtils` 다운캐스팅 제거

**수정 — sfs-samples:**

- [ ] 10. `UserService` IdGenerator/Clock 통일 (`@Autowired Clock` 제거)
- [ ] 11. `EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo` rename + 출력 갱신
- [ ] 12. `EnhanceAbsenceDemoTest` → `ConfigurationEnhanceDemoTest` rename + 어서션 갱신
- [ ] 13. `TodoDemoApplication` 7 클래스 명시 등록 → `@ComponentScan` 축약
- [ ] 14. `AppConfig`에 `@ComponentScan` 추가
- [ ] 15. `sfs-samples/README.md` Phase 2A 학습 가치 반영

**품질:**

- [ ] 16. `./gradlew :sfs-context:test :sfs-samples:test` 모두 PASS
- [ ] 17. `./gradlew build` 전체 PASS + 누적 ~141~142 PASS / 0 FAIL
- [ ] 18. 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify 패스) 실행 후 기록

---

## 10. 후속 작업 (본 spec 범위 외)

- **Phase 2A → main 머지** — `feat/phase2a-byte-buddy` 또는 1C 머지 후 동일 브랜치 연속
- **Phase 2B (AOP) brainstorming** — `sfs-aop` 모듈 신설 + `@Aspect`/`@Pointcut`/`@Around` + logging advice 시연 (sfs-samples의 `TodoController` 메서드 호출 전후 로그)
- **deep version 점진 추가** (선택)
  - `@ComponentScan.basePackages()` 비어있을 때 *애노테이션 클래스 패키지 자동 추론*
  - `@ComponentScan.includeFilters/excludeFilters` 지원
  - `@Configuration` enhance에서 `final` 클래스/메서드 검출 시 명확한 에러 메시지

---

## 11. 학습 가치 매핑

| Phase 2A 항목 | 학습 가치 | 이후 재활용 |
|---|---|---|
| byte-buddy MethodInterceptor 패턴 | 런타임 클래스 변형의 첫 경험 | Phase 2B AOP advice |
| `@Configuration` enhance | inter-bean reference의 본질 | Spring Boot `@SpringBootApplication` 이해 |
| `@ComponentScan` | 표준 Spring 패턴 도입 | Phase 2B 이후 sfs-mvc 등 |
| 다운캐스팅 정리 | 인터페이스 의존의 가치 | AOP/Cache/Tx 등 BPP 후손 도입 시 안전 |
| IdGenerator 통일 | 학습 데모와 코드 사용 일치 | Phase 2B logging advice의 자연스러운 시연 대상 |
| EnhanceAbsenceDemo rename | 살아있는 마일스톤 박제 | Phase 2B 이후 추가 enhance 마일스톤의 패턴 |

> **본 phase의 메타 학습 가치:** "Phase가 진행되면 *기존 코드의 진실*이 바뀐다 — 그래서 박제(테스트)가 이를 자동으로 검출하고, *이름조차 거짓말이 되면 rename*해야 한다." 이게 Phase 1C에서 EnhanceAbsenceDemo를 만든 진짜 이유의 회수.
