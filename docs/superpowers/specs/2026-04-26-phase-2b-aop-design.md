# Phase 2B — `sfs-aop` 모듈 신설 + `@Aspect`/`@Around`/`@Before`/`@After` + LoggingAspect 시연 설계 스펙

- **작성일:** 2026-04-26
- **페이즈:** Phase 2B (AOP 본체)
- **관련 문서:**
  - 상위 설계: `docs/superpowers/specs/2026-04-19-ioc-container-design.md`
  - 선행 spec: `docs/superpowers/specs/2026-04-26-phase2a-byte-buddy-cleanup-design.md`
  - 선행 plan: `docs/superpowers/plans/2026-04-26-phase-2a-byte-buddy-cleanup.md` (완료, 145 PASS, main 머지 완)
- **선행 조건:** Phase 2A 완료 (main 머지 완, 145 PASS / 0 FAIL, 마감 게이트 통과). byte-buddy 인프라가 `sfs-context`에 깔려 있고, BFPP/BPP 다운캐스팅이 모두 정리된 상태.
- **분리 phase:**
  - Phase 2C — Phase 2A 이월 4건 (`@Bean(name="customName")` 통합 박제, `paramType.getSimpleName()` 의존성 이름 전략, byte-buddy `args` 미사용 박제, named module 전환 박제) + 본 phase에서 박제될 후속 처리 (final 메서드 silent skip 검출, 인스턴스 교체 시점 self-reference 처리 등). 본 spec 범위 외, 별도 spec/plan에서 처리.
  - Phase 3 (가칭) — `@AfterReturning`/`@AfterThrowing` advice 추가, AspectJ 표현식 파서, advice 우선순위 (`@Order`), Aspect의 `@EnableAspect`/`@Import` 자동 등록 트리거.

---

## 1. 배경과 목표

### 1.1 Phase 2A 마감 시점 상태

Phase 2A를 통해 다음이 완성됨:

- `sfs-context`에 byte-buddy `1.14.19` `implementation` 의존
- `ConfigurationClassEnhancer` + `BeanMethodInterceptor` — `@Configuration` enhance (proxyBeanMethods=true 진짜로 동작)
- `@ComponentScan` 애노테이션 + `ConfigurationClassPostProcessor` 처리 (BFPP 시점에 BD 추가)
- `ConfigurableListableBeanFactory.resolveDependency` 인터페이스 승격 + BPP 처리기 생성자 파라미터 타입 정리 (`AbstractAutowireCapableBeanFactory`의 잔여 다운캐스팅 포함)
- `IdGenerator/Clock` 통일 (`UserService`가 `@Autowired IdGenerator`만 의존)
- `LoggingAspect` 시연을 위한 자연스러운 *주입 의존 후보*가 마련됨

회귀 145 PASS / 0 FAIL. main 머지 완료.

### 1.2 본 phase의 목적

`sfs-aop` 모듈을 신설하고 byte-buddy 인프라를 *advice chain*에 재활용해, 컨테이너에 **2차 추상**으로서의 AOP를 도입한다.

1. **`sfs-aop` 모듈 신설** — `sfs-context`와 `sfs-samples` 사이에 끼워 넣음. byte-buddy `1.14.19` *직접 명시 의존*.
2. **애노테이션 5종** — `@Aspect`, `@Loggable`, `@Around`, `@Before`, `@After`. 매칭 표현은 *클래스 직접 지정*(`@Around(Loggable.class)`), 표현식 파서 없음.
3. **`AspectEnhancingBeanPostProcessor`** — 빈 생성 후(`postProcessAfterInitialization`) 매칭 검사 → byte-buddy 서브클래스 + 인터셉터 적용. `@Aspect` 빈은 advice 등록만, 일반 빈은 매칭 시 enhance.
4. **`AdviceInterceptor`** — 3종 advice 합성: `@Around` 바깥, `@Before` 진입, super 호출, `@After` finally.
5. **`LoggingAspect` 시연** — `sfs-samples`에 `@Aspect @Component` 클래스 추가, `@Autowired IdGenerator` 의존, `TodoController.create()`에 `@Loggable` 부착. 출력 라인이 task별로 누적됨 (B2 +1줄, C1 +1줄, C2 +1줄).

### 1.3 비목표 (Out of Scope)

- **`@AfterReturning` / `@AfterThrowing` advice** — `@Around` 안의 try/catch 분해로 표현 가능 (Q3 결정 근거). Phase 3에서 도입.
- **AspectJ 표현식 파서** (`execution(...)`, `within(...)` 등) — Q2 결정에 따라 *애노테이션 매칭만*. Phase 3 또는 그 이후.
- **advice 우선순위** (`@Order`) — 본 phase는 한 메서드당 advice 종류별 1개씩만 (LoggingAspect 단일 시연), 우선순위는 의미 없음.
- **`@EnableAspect` / `@Import` 자동 등록 트리거** — 본 phase는 사용자가 `AppConfig`에 `@Bean AspectEnhancingBeanPostProcessor`로 *명시 등록* (3.1 결정). 자동화는 Phase 3.
- **인스턴스 교체 시점 early reference 문제** — 본 phase는 `getBean(Type.class)`로 *직접 가져오는 시연*만. *주입을 통한 호출*에서 advice 비적용 케이스는 Phase 2C로 박제.
- **`final` 클래스/메서드/필드 처리 강화** — final 클래스/필드는 명확한 에러 메시지로 fail-fast, final 메서드의 silent advice 비적용은 Phase 2C 박제.
- **인터페이스 기반 JDK Dynamic Proxy** — 클래스 기반 서브클래스 프록시만 (Phase 2A와 동일).

---

## 2. Brainstorming 결정 박제 (2026-04-26)

| # | 질문 | 결정 | 핵심 함의 |
|---|---|---|---|
| Q1 | Phase 2B 범위 | **B** — AOP만 분리 | 이월 4건은 Phase 2C로. AOP 학습 단위 응집 보장 |
| Q2 | Pointcut 표현식 범위 | **A** — 애노테이션 매칭만 | `@Loggable` 마커 부착, `Method.isAnnotationPresent()`로 매칭. 표현식 파서 없음 |
| Q3 | Advice 종류 | **B** — 3종 (`@Before`/`@After`/`@Around`) | 권한 사다리는 살리되 표현 중복(`@AfterReturning`/`@AfterThrowing`) 회피. Phase 3에서 5종으로 확장 가능 |
| Q4 | byte-buddy 통합 방식 | **C** — 별도 enhancer + BPP 시점 분리 | `ConfigurationClassEnhancer`(BFPP)와 `AspectEnhancingBeanPostProcessor`(BPP)가 *시점이 다른 두 enhance*. 라이프사이클 분리의 진짜 보상 회수 |
| Q5 | Aspect 클래스의 정체 | **A** — 빈으로 등록 (Spring 본가 패턴) | `@Aspect @Component` 양립. advice가 `@Autowired` 의존 가능. 컨테이너 라이프사이클 그대로 적용 |
| Q5-bis | task 진행 패턴 | 하이브리드 — 인프라 bottom-up + advice vertical slice | A 단계(인프라) → B 단계(@Around end-to-end) → C 단계(@Before/@After 분기 확장) → D 단계(시연 통합 + 문서) |
| Q5-tris | BPP 등록 방식 | 사용자 명시 `@Bean` (3.1) | `@EnableAspect`/`@Import` 자동화는 Phase 3. 본 phase는 *AOP 활성화 = BPP를 빈으로 등록*이 직설적 학습 |

---

## 3. 현재 인프라 상태 (spec 작성 시점 기준)

### 3.1 이미 있는 것들 (재활용)

| 항목 | 위치 | 상태 |
|---|---|---|
| byte-buddy 카탈로그 | `gradle/libs.versions.toml` | `bytebuddy = "1.14.19"` 등록 |
| `ClassLoadingStrategy.UsingLookup` | Phase 2A에서 도입 | Java 25 unnamed module 호환 처리 — `sfs-aop`가 그대로 채용 |
| `ConfigurationClassEnhancer` 패턴 | `sfs-context/support` | byte-buddy `subclass(...).method(...).intercept(...).make().load(...)` 호출 패턴 — `sfs-aop`가 동형 패턴으로 재활용 |
| `BeanPostProcessor` 인터페이스 | `sfs-beans` | `postProcessBeforeInitialization` / `postProcessAfterInitialization` 시그니처 |
| `registerBeanPostProcessors()` | `sfs-context` refresh 단계 | BPP 빈을 *우선 생성하고 일반 빈 후처리 단계와 분리* — Phase 2B의 자기 참조 격리 자연 만족 |
| `BeanFactory` 인터페이스 + `BeanFactoryAware` | `sfs-beans` | `getBean(name)` — advice 호출 시 aspect 빈 lookup 진입점. `BeanFactoryAware`로 BPP가 base 타입 받음 (다운캐스팅 불필요 — Phase 2A 정리 정신과 일관) |
| `@ComponentScan` (Phase 2A) | `sfs-context/annotation` | `LoggingAspect`(`@Component`)를 자동 발견 |
| `AnnotationConfigApplicationContext` | `sfs-context/support` | refresh 8단계 + close LIFO destroy. 본 phase에서 변경 없음 |
| `IdGenerator` (Phase 2A 통일) | `sfs-samples/support` | `LoggingAspect`가 `@Autowired`로 의존 — Phase 2A spec § 11이 약속한 자연 회수 |

### 3.2 신규 작업 / 수정 작업

**신규 모듈:**
- `sfs-aop/build.gradle.kts` — `implementation(project(":sfs-context"))` + `implementation(libs.bytebuddy)`
- `settings.gradle.kts` — `include("sfs-aop")` 추가

**신규 클래스 (sfs-aop):**
- `annotation/Aspect.java` — TYPE 마커
- `annotation/Loggable.java` — METHOD/TYPE 마커 (시연용)
- `annotation/Around.java` — METHOD, `value: Class<? extends Annotation>`
- `annotation/Before.java` — 동일 시그니처
- `annotation/After.java` — 동일 시그니처
- `support/AdviceType.java` — enum (BEFORE/AFTER/AROUND)
- `support/AdviceInfo.java` — record (type, targetAnnotation, adviceMethod, aspectBeanName)
- `support/AspectRegistry.java` — advice 목록 관리, 매칭 lookup
- `support/JoinPoint.java` — 인터페이스 (target, method, args)
- `support/ProceedingJoinPoint.java` — 인터페이스, extends JoinPoint, `proceed()`
- `support/MethodInvocationJoinPoint.java` — 두 인터페이스의 단일 구현
- `support/AdviceInterceptor.java` — byte-buddy 인터셉터, 3종 advice 합성
- `support/AspectEnhancingBeanPostProcessor.java` — BPP 본체

**신규/수정 클래스 (sfs-samples):**
- `samples/todo/aspect/LoggingAspect.java` — `@Aspect @Component`, `@Around`/`@Before`/`@After` 메서드 3개, `@Autowired IdGenerator`
- `samples/todo/controller/TodoController.java` — `create()` 메서드에 `@Loggable` 부착 (다른 메서드로 확장 가능)
- `samples/todo/config/AppConfig.java` — `@Bean AspectEnhancingBeanPostProcessor aspectBpp()` 추가
- `samples/todo/TodoDemoApplication.java` — 변경 없음 (출력 라인이 advice로 인해 자동 증가)
- `samples/todo/TodoDemoApplicationTest.java` — 출력 박제 갱신 (8라인 → 11라인, B2/C1/C2 task별 갱신)

**변경 없음 (본 phase의 정상 동작 신호):**
- `sfs-core` 전체
- `sfs-beans` 전체
- `sfs-context` 전체 — Phase 2A 마감 게이트가 *충분했다*는 진짜 시험. 만약 변경이 필요하면 *발견 자체가 박제 가치*이며 Phase 2C로 이월

---

## 4. AOP 학습 메모

### 4.1 Spring AOP의 영리한 단순화

AspectJ는 *별개 컴파일러를 가진 진짜 AOP 언어*다. weaving 시점이 컴파일 또는 클래스 로딩이며 *어떤 객체*에든 적용 가능. Spring AOP는 이를 *런타임 + 컨테이너 빈 한정*으로 단순화 — IoC가 이미 있으니 *advice도 그냥 빈*으로 다룬다는 영리한 결정.

본 phase가 그 단순화를 그대로 따른다:
- advice는 빈의 메서드 (`@Aspect @Component` 양립, advice 호출은 `aspectBean.method.invoke(beanFactory.getBean(name), args)`)
- weaving 시점은 BPP — 빈 생성 직후, 다른 빈에 주입되기 *전*
- weaving 대상은 컨테이너의 다른 빈 (외부 객체에는 적용 불가)

### 4.2 advice 합성 = 함수 합성

3종 advice가 한 메서드에 동시 적용될 때 호출 순서:

```
@Around 진입
  ├─ pjp.proceed() 호출 시점에:
  │     try {
  │       @Before invoke
  │       superCall.call()  // 진짜 메서드
  │       (return result)
  │     } finally {
  │       @After invoke    // 예외 시에도 호출
  │     }
  └─ @Around가 결과 변형 또는 그대로 반환
```

`AdviceInterceptor` 안에서 *@Before+superCall+@After를 try/finally로 감싼 Callable*을 만들고, 그걸 `ProceedingJoinPoint.proceed()`에 연결. *advice chain의 본질이 함수 합성임*이 lambda 중첩으로 박제됨.

### 4.3 BFPP vs BPP — 시점 분리의 진짜 가치

| 시점 | 변경 대상 | 본 phase 사용처 |
|---|---|---|
| **BFPP** (`postProcessBeanFactory`) | BeanDefinition (메타데이터) | Phase 2A `ConfigurationClassPostProcessor` — BD의 `beanClass` 교체로 enhance |
| **BPP** (`postProcessAfterInitialization`) | 빈 인스턴스 | 본 phase `AspectEnhancingBeanPostProcessor` — 인스턴스 교체로 advice proxy |

두 시점이 다른 이유:
- `@Configuration` enhance는 BD 단계에서 끝나야 함 — 이후 createBean()이 enhance된 클래스를 그대로 newInstance
- `@Aspect` proxy는 빈 인스턴스가 *완성*된 후 (DI 끝, `@PostConstruct` 끝) 적용되어야 함 — advice가 감싸는 대상이 *진짜 완성된 타깃*이어야 의미 있음

byte-buddy API(`subclass(...).intercept(...)`)는 *시점에 무관* — 같은 인프라가 두 시점에서 그대로 작동. 학습자는 *시점 차이*만 이해하면 됨.

### 4.4 인스턴스 교체 전략 (필드 복사)

BPP 시점에 빈 인스턴스가 이미 DI 완료 상태. byte-buddy 서브클래스로 교체하려면 *원본의 필드 값을 새 인스턴스로 복사*해야 함. 본 phase는 reflection으로 모든 필드 복사 (`field.set(newInstance, field.get(original))`).

**한계:**
- `final` 필드는 reflection으로도 변경 불가 (Java 17+ 강한 봉쇄) → fail-fast로 명확 에러 throw
- 학습 컨텍스트는 `@Autowired private` 필드 주입이 표준이라 final 필드가 거의 없어 자연 만족

**대안 (Spring AOP 본가)**: target 위임 wrapper — 서브클래스가 `target` 필드 보유, 인터셉터가 `target.method()` 호출. 본 phase는 *byte-buddy `@SuperCall`의 자연 사용*과 학습 단순화를 위해 필드 복사 채택. 위임 wrapper는 Phase 3 또는 그 이후 가능.

---

## 5. 컴포넌트 카탈로그

### 5.1 신규 모듈: `sfs-aop`

**`sfs-aop/build.gradle.kts`:**
```kotlin
plugins { id("buildlogic.java-library-conventions") }

dependencies {
    implementation(project(":sfs-context"))
    implementation(libs.bytebuddy)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

**`settings.gradle.kts`:**
```kotlin
include("sfs-core", "sfs-beans", "sfs-context", "sfs-aop", "sfs-samples")
```

**`sfs-samples/build.gradle.kts`** — `implementation(project(":sfs-context"))` 옆에 `implementation(project(":sfs-aop"))` 추가.

### 5.2 애노테이션 5종

```java
// sfs-aop/src/main/java/com/choisk/sfs/aop/annotation/Aspect.java
@Retention(RUNTIME) @Target(TYPE)
public @interface Aspect {}

// Loggable.java
@Retention(RUNTIME) @Target({METHOD, TYPE})
public @interface Loggable {}

// Around.java / Before.java / After.java (동일 시그니처)
@Retention(RUNTIME) @Target(METHOD)
public @interface Around {
    Class<? extends Annotation> value();
}
```

> **시연 요점:** `@Aspect`만 부착하면 컨테이너에 등록 안 됨. `@Component`와 함께 써야 빈으로 등록 — Spring 본가 패턴 그대로.

### 5.3 메타데이터 컴포넌트

```java
// sfs-aop/src/main/java/com/choisk/sfs/aop/support/AdviceType.java
public enum AdviceType { BEFORE, AFTER, AROUND }

// AdviceInfo.java
public record AdviceInfo(
    AdviceType type,
    Class<? extends Annotation> targetAnnotation,
    Method adviceMethod,
    String aspectBeanName
) {}

// AspectRegistry.java
public class AspectRegistry {
    private final List<AdviceInfo> advices = new ArrayList<>();

    public void register(String aspectBeanName, Class<?> aspectClass) {
        // @Aspect 빈의 메서드를 순회하며 @Around/@Before/@After 발견 시 AdviceInfo 등록
    }

    public List<AdviceInfo> findApplicable(Method targetMethod) {
        // targetMethod에 부착된 애노테이션 또는 declaringClass에 부착된 애노테이션이
        // 등록된 advice의 targetAnnotation과 일치하는 항목 반환
    }
}
```

### 5.4 JoinPoint API

```java
// sfs-aop/src/main/java/com/choisk/sfs/aop/support/JoinPoint.java
public interface JoinPoint {
    Object getTarget();
    Method getMethod();
    Object[] getArgs();
}

// ProceedingJoinPoint.java
public interface ProceedingJoinPoint extends JoinPoint {
    Object proceed() throws Throwable;
}

// MethodInvocationJoinPoint.java — 두 인터페이스 단일 구현
public class MethodInvocationJoinPoint implements ProceedingJoinPoint {
    private final Object target;
    private final Method method;
    private final Object[] args;
    private final Callable<Object> innerCall;  // null이면 @Around 미적용 케이스

    @Override
    public Object proceed() throws Throwable {
        if (innerCall == null) {
            throw new IllegalStateException(
                "proceed() called on JoinPoint without inner call — @Around가 아닌 advice에서 호출됨");
        }
        return innerCall.call();
    }
    // ... getter
}
```

### 5.5 핵심 인프라 컴포넌트

```java
// sfs-aop/src/main/java/com/choisk/sfs/aop/support/AspectEnhancingBeanPostProcessor.java
public class AspectEnhancingBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;  // base 타입 — getBean(name)만 사용, 다운캐스팅 불필요
    private final AspectRegistry registry = new AspectRegistry();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) { this.beanFactory = beanFactory; }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 1. BPP 자기 격리: bean instanceof BeanPostProcessor → 그대로 반환
        if (bean instanceof BeanPostProcessor) return bean;

        // 2. @Aspect 빈 처리: registry에 등록 + 원본 그대로 반환
        if (bean.getClass().isAnnotationPresent(Aspect.class)) {
            registry.register(beanName, bean.getClass());
            return bean;
        }

        // 3. 일반 빈 처리: 매칭 검사 → 매칭 시 enhance + 필드 복사
        List<AdviceInfo> applicable = registry.findAnyApplicable(bean.getClass());
        if (applicable.isEmpty()) return bean;

        Class<?> enhanced = createEnhancedSubclass(bean.getClass());
        Object newInstance = newInstance(enhanced);
        copyFields(bean, newInstance);
        return newInstance;
    }
}

// AdviceInterceptor.java
public class AdviceInterceptor {
    private final BeanFactory beanFactory;  // getBean(name)만 사용 — base 타입으로 충분
    private final AspectRegistry registry;

    @RuntimeType
    public Object intercept(@SuperCall Callable<Object> superCall,
                            @Origin Method method,
                            @AllArguments Object[] args,
                            @This Object self) throws Throwable {
        List<AdviceInfo> applicable = registry.findApplicable(method);
        if (applicable.isEmpty()) return superCall.call();  // safety fallback

        // @Before + superCall + @After를 try/finally로 감싼 Callable 합성
        Callable<Object> innerCall = () -> {
            JoinPoint jp = new MethodInvocationJoinPoint(self, method, args, null);
            try {
                invokeAll(applicable, AdviceType.BEFORE, jp);
                Object result = superCall.call();
                return result;
            } finally {
                invokeAll(applicable, AdviceType.AFTER, jp);  // 예외 시에도 호출
            }
        };

        // @Around가 있으면 ProceedingJoinPoint에 연결, 없으면 innerCall 직통
        AdviceInfo around = findOne(applicable, AdviceType.AROUND);
        if (around != null) {
            ProceedingJoinPoint pjp = new MethodInvocationJoinPoint(self, method, args, innerCall);
            return invokeAdvice(around, pjp);
        }
        return innerCall.call();
    }

    private Object invokeAdvice(AdviceInfo info, JoinPoint jp) throws Throwable {
        Object aspectBean = beanFactory.getBean(info.aspectBeanName());
        return info.adviceMethod().invoke(aspectBean, jp);  // reflection
    }
}
```

> **시연 요점:** `AspectRegistry`는 빈으로 등록 안 함 (BPP의 내부 상태). 이 비대칭이 학습 가치 — *BPP는 컨테이너의 시민이 아닌 부속*. 본 phase의 advice 호출은 매번 `beanFactory.getBean()` 호출 후 reflection invoke — singleton cache hit이라 비용 무시 가능 (Phase 1B-α 3-level cache의 hot-path 보상).

### 5.6 시연 컴포넌트 (sfs-samples)

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/aspect/LoggingAspect.java
@Aspect
@Component
public class LoggingAspect {

    @Autowired
    private IdGenerator idGen;

    @Around(Loggable.class)
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long id = idGen.next();
        long start = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[Around id=" + id + "] " + pjp.getMethod().getName()
                + " 실행 시간 " + elapsedMs + "ms");
        }
    }

    @Before(Loggable.class)
    public void logCall(JoinPoint jp) {
        System.out.println("[Before] " + jp.getMethod().getName()
            + " 호출 — args=" + Arrays.toString(jp.getArgs()));
    }

    @After(Loggable.class)
    public void logExit(JoinPoint jp) {
        System.out.println("[After] " + jp.getMethod().getName() + " 종료");
    }
}
```

`TodoController`:
```java
@Controller
public class TodoController {
    // ...
    @Loggable
    public Todo create(Long ownerId, String title) { ... }
}
```

`AppConfig`:
```java
@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
public class AppConfig {
    @Bean public AspectEnhancingBeanPostProcessor aspectBpp() {
        return new AspectEnhancingBeanPostProcessor();
    }
    @Bean public Clock systemClock() { return Clock.systemDefaultZone(); }
    @Bean public IdGenerator idGenerator(Clock clock) { return new IdGenerator(clock); }
}
```

---

## 6. 데이터 흐름

### 6.1 부트스트랩 시퀀스

```
1. main(): new AnnotationConfigApplicationContext(AppConfig.class)
2. ConfigurationClassPostProcessor (BFPP, Phase 2A 산출):
   ├─ @ComponentScan → LoggingAspect, TodoController, ... BD 등록
   └─ @Bean → AspectEnhancingBeanPostProcessor BD 등록
3. AbstractApplicationContext.refresh():
   ├─ registerBeanPostProcessors() → AspectBPP 우선 생성, BPP 목록에 추가
   └─ preInstantiateSingletons() — 일반 빈 단계
4. LoggingAspect 빈 생성 → AspectBPP.postProcessAfterInit():
   ├─ @Aspect 인지 → registry.register("loggingAspect", LoggingAspect.class)
   └─ 원본 그대로 반환
5. TodoController 빈 생성 → AspectBPP.postProcessAfterInit():
   ├─ registry.findAnyApplicable() → @Loggable 매칭 advice 발견
   ├─ byte-buddy로 TodoController 서브클래스 생성, AdviceInterceptor 적용
   ├─ 서브클래스 newInstance() + 원본 필드 reflection 복사
   └─ enhance된 인스턴스 반환 (컨테이너 reference 교체)
6. 모든 빈 생성 완료 → 사용자 코드 실행
```

### 6.2 런타임 호출 시퀀스 (Advice 합성)

`todoController.create(2L, "장보기")` 호출 (가장 복잡한 케이스: 3종 동시 적용):

```
[enhance된 TodoController.create()] 호출
        ↓
AdviceInterceptor.intercept(superCall, method, args, self)
        ↓
1. registry.findApplicable(method) → [@Around measure, @Before logCall, @After logExit]
2. innerCall = () -> {
        JoinPoint jp = new MethodInvocationJoinPoint(self, method, args, null);
        try {
            invokeAdvice(@Before logCall, jp)   // [Before] create 호출 — args=[2, 장보기]
            Object result = superCall.call()    // 진짜 TodoController.create() 실행
            return result
        } finally {
            invokeAdvice(@After logExit, jp)    // [After] create 종료 (예외 시에도)
        }
   }
3. ProceedingJoinPoint pjp = new MethodInvocationJoinPoint(self, method, args, innerCall)
4. return invokeAdvice(@Around measure, pjp)
   → measure가 pjp.proceed() 호출 → innerCall.call() → 위 try/finally 실행
   → measure가 elapsed 계산 후 결과 반환
```

### 6.3 데이터 흐름 다이어그램

```
┌──────────────────┐        ┌────────────────────┐
│  Container       │        │   AspectRegistry   │
│  (refresh)       │ -BPP-> │  - List<Advice>    │
└──────────────────┘        └────────────────────┘
        │                            ↑
        │ create bean                │ register (4단계)
        ↓                            │
┌──────────────────┐                 │
│  LoggingAspect   │─────────────────┘
│  (Aspect 빈)     │
└──────────────────┘
        │
        │ 일반 빈 생성 (5단계)
        ↓
┌──────────────────┐    enhance     ┌────────────────────────┐
│  TodoController  │──byte-buddy───►│ TodoController$Enhanced│
│  (원본)          │                 │ + AdviceInterceptor    │
└──────────────────┘                 └────────────────────────┘

[런타임]
user.create() → Enhanced.create() → AdviceInterceptor.intercept()
              → AspectRegistry.findApplicable()
              → @Around(@Before(superCall())@After) 합성
              → reflection invoke advice (aspectBean.method)
```

---

## 7. 에러 처리

### 7.1 설정 에러 (Fail-fast)

| 케이스 | 발생 시점 | 처리 |
|---|---|---|
| `@Around`/@Before`/@After` 메서드 시그니처 부정합 | `AspectRegistry.register()` (4단계) | `IllegalStateException("@Around method must accept ProceedingJoinPoint as first parameter: <className>.<methodName>")` |
| `@Around` 메서드가 `ProceedingJoinPoint` 아닌 `JoinPoint` 받음 | 동일 시점 | 명확 에러 메시지 |
| `@Aspect`만 부착, `@Component` 누락 | (빈 등록 자체가 안 됨) | **본 phase는 자동 검출 X** — 문서 안내. Phase 2C에서 검증 추가 후보 |
| `@Aspect` 빈에 `@Around`/@Before`/@After` 메서드 0개 | `register()` 시점 | 정상 (advice 0개 등록), 무해 |

### 7.2 byte-buddy 한계

| 케이스 | 처리 |
|---|---|
| `final` 클래스에 `@Loggable` 메서드 | `IllegalArgumentException("Cannot subclass final class: <className>. Remove final or relocate @Loggable")` — Phase 2A enhancer와 동일 메시지 패턴 |
| 메서드가 `final` | byte-buddy가 *override 안 함* → advice가 silent skip. **본 phase는 시연 코드 통제로 회피**, 자동 검출은 Phase 2C 박제 |
| no-arg 생성자 부재 | `NoSuchMethodException` → `"Cannot enhance <className>: requires accessible no-arg constructor"` |
| `final` 필드 존재 | reflection set 시 `IllegalAccessException` → `"Cannot copy final field <fieldName> on enhanced <className>. Remove final or use constructor injection (Phase 2C+)"` |

### 7.3 런타임 예외 — Advice 권한 사다리

| 케이스 | 흐름 | 결과 |
|---|---|---|
| 진짜 메서드 throw, `@Around`가 catch | `pjp.proceed()` throws → `@Around` catch 변환/흡수 | `@Around`가 결정권 가짐 (가장 강한 권한) |
| 진짜 메서드 throw, `@Around` 미흡수 | pjp.proceed() throw 그대로 propagate, finally의 `@After` invoke, `@Before`는 진입 후라 추가 호출 X | 호출자에게 전파 |
| `@Before` 자체가 throw | 진짜 메서드 실행 X, `@After` 호출 X (try 진입 전), 호출자에게 전파 | `@Before`가 메서드를 *차단*하는 권한 |
| `@After` 자체가 throw | finally의 throw가 진짜 메서드 결과 덮어씀 (Java try/finally semantics) | 호출자에게 전파 |

> **정리:** 본 phase는 예외를 *변형 없이 propagate*. advice 안에서 잡고 싶으면 `@Around`로 직접 try/catch — Q3 결정(3종 advice)의 자연 회수.

### 7.4 자기 참조 무한 루프 방지

`AspectEnhancingBeanPostProcessor` 자체가 다른 빈을 후처리하는데 *자기 자신*이 BPP 처리 단계에 또 등장하면? Phase 1B-β의 `registerBeanPostProcessors()`가 이미 *BPP 인스턴스를 별도 리스트에 격리*하므로 자연 만족. 추가 가드: `bean instanceof BeanPostProcessor`이면 enhance 시도 안 함 (5.5 본문). 사용자가 다른 BPP를 만들었을 때 advice가 BPP를 감싸지 않도록.

---

## 8. 모듈 의존 정책

| 모듈 | 의존 | 변동 |
|---|---|---|
| `sfs-core` | (외부만) | 변경 없음 |
| `sfs-beans` | `sfs-core` | 변경 없음 |
| `sfs-context` | `sfs-beans` + byte-buddy | 변경 없음 |
| **`sfs-aop` (신설)** | `sfs-context` + byte-buddy (직접 명시) | 신규 |
| `sfs-samples` | `sfs-context` → **`sfs-context` + `sfs-aop`** | `sfs-aop` 추가 |

byte-buddy가 sfs-aop에 직접 명시 의존인 이유:
1. *모듈 자치(self-contained) 원칙* — 자기 의존을 자기가 선언
2. transitive 의존만 받으면 sfs-context의 byte-buddy 의존이 바뀔 때 sfs-aop가 *모르게 깨질* 위험
3. Gradle/Maven 표준 패턴

---

## 9. 테스트 전략

### 9.1 TDD 적용 가이드 매핑 (CLAUDE.md)

| 컴포넌트 | TDD 적용? | 근거 |
|---|---|---|
| 애노테이션 5종 (시그니처만) | **제외** | 컨테이너 등록 통합 테스트로 간접 검증 |
| `AdviceInfo` (record) | **제외** | 데이터 컨테이너, getter만 |
| `AdviceType` (enum) | **제외** | 단순 정의 |
| `JoinPoint`/`ProceedingJoinPoint` 인터페이스 | **제외** | 시그니처 |
| `MethodInvocationJoinPoint` 구현 | **적용** (간단) | `proceed()` 동작 — 단위 테스트 1개 |
| `AspectRegistry` | **적용** | 매칭 분기, 등록/누적 동작 |
| `AspectEnhancingBeanPostProcessor` | **적용** | 라이프사이클 분기 (Aspect/일반/non-매칭/BPP 자기 격리) |
| `AdviceInterceptor` | **적용** | advice 종류별 분기, 호출 순서, 예외 propagation |
| `LoggingAspect` (samples) | **단위 X, 통합 O** | advice 메서드 자체는 단순 출력 — 통합 시연으로 검증 |

### 9.2 단위 테스트 (sfs-aop)

```
AspectRegistryTest
  ├─ registerAddsAdvicesFromAspectClass — @Aspect 빈에서 3종 advice 추출
  ├─ findApplicableReturnsMatchingAdvices — 메서드의 @Loggable 매칭 시 advice 반환
  ├─ findApplicableReturnsEmptyForUnannotatedMethod — 매칭 없으면 빈 리스트
  └─ findApplicableHonorsClassLevelAnnotation — 클래스에 @Loggable 있으면 모든 public 메서드 매칭

AspectEnhancingBeanPostProcessorTest
  ├─ aspectBeanRegistersAdvicesAndReturnsOriginal — @Aspect 빈은 enhance X, registry 등록만
  ├─ matchingBeanIsEnhancedWithFieldsCopied — 매칭 빈은 byte-buddy 서브클래스로 교체, 필드 복사
  ├─ nonMatchingBeanIsReturnedUnchanged — 매칭 없는 빈은 원본 그대로
  ├─ beanPostProcessorBeanIsNotEnhanced — BPP 자기 격리 (7.4)
  ├─ finalClassThrowsClearError — final 클래스 시 명확 에러 메시지
  └─ finalFieldThrowsClearError — final 필드 시 명확 에러 메시지

AdviceInterceptorTest
  ├─ aroundAdviceWrapsMethodCall — @Around만 있으면 proceed() 호출 시 진짜 메서드 실행
  ├─ aroundAdviceCanSkipProceed — proceed() 미호출 시 진짜 메서드 실행 X
  ├─ beforeAdviceRunsBeforeMethodAndAfterRunsAfter — @Before/@After 호출 순서
  ├─ afterAdviceRunsEvenWhenMethodThrows — @After는 finally에서 호출 (예외 시에도)
  ├─ beforeAdviceThrowingPreventsMethodCall — @Before throw → 진짜 메서드 차단
  ├─ aroundComposesBeforeAndAfter — 3종 동시 적용 시 호출 순서
  └─ methodWithoutMatchingAnnotationCallsSuperDirectly — registry 매칭 없으면 superCall 직통

MethodInvocationJoinPointTest
  └─ proceedDelegatesToInnerCallable — proceed() → innerCall.call()

[총 신규 단위 테스트: ~18개]
```

### 9.3 통합 테스트 (sfs-aop)

```
AspectIntegrationTest (AnnotationConfigApplicationContext 사용)
  ├─ aspectBeanIsRegisteredAndTargetIsEnhanced
  ├─ adviceIsInvokedWhenTargetMethodCalled
  ├─ aspectInjectedDependenciesAreAvailable — Q5 핵심 (advice 안에서 @Autowired 의존 사용)
  └─ classLevelLoggableEnhancesAllPublicMethods (옵션)

[총 신규 통합 테스트: ~4개]
```

### 9.4 시연 박제 (sfs-samples)

기존 `TodoDemoApplicationTest` 확장 + 신규 `LoggingAspectDemoTest` (옵션):
- B2 끝: 8라인 → 9라인 (+@Around)
- C1 끝: 9라인 → 10라인 (+@Before)
- C2 끝: 10라인 → 11라인 (+@After)

각 task 끝마다 출력 박제가 *task별 RED → GREEN 전환*을 자동 검출.

### 9.5 마일스톤 박제 표

| Task 끝 | TodoDemoApplicationTest 출력 | 박제 형태 |
|---|---|---|
| Phase 2A 끝 | 8 라인 | (기존) |
| **B2 끝** | 9 라인 (+@Around 1줄) | `[Around id=N] create 실행 시간 N ms` |
| **C1 끝** | 10 라인 (+@Before 1줄) | `[Before] create 호출 — args=[2, 장보기]` |
| **C2 끝** | 11 라인 (+@After 1줄) | `[After] create 종료` |

### 9.6 회귀 테스트 카운트 예상

| 모듈 | Phase 2A 종료 | Phase 2B 종료 (예상) | 변동 |
|---|---|---|---|
| sfs-core | 28 | 28 | 0 |
| sfs-beans | 58 | 58 | 0 |
| sfs-context | 51 | 51 | 0 |
| **sfs-aop (신설)** | (없음) | ~22 (단위 18 + 통합 4) | **+22** |
| sfs-samples | 8 | 9~11 (advice 출력 라인 누적) | +1~3 |
| **합계** | **145** | **166~169** | **+21~24** |

> **추정 보수성:** 위는 단위 18 + 통합 4 + 시연 1~3 신규의 보수 추정. 실제 작업 중 발견되는 경계 케이스 (multiple `@Loggable` 메서드 상호작용, BPP 빈 등록 순서 검증 등)에서 +2~3 추가 가능 — 실행 기록에 박제.

---

## 10. Definition of Done

**모듈 신설:**
- [ ] 1. `settings.gradle.kts`에 `sfs-aop` 추가 + `sfs-aop/build.gradle.kts` 신설 (byte-buddy 직접 의존)
- [ ] 2. `sfs-samples/build.gradle.kts`에 `implementation(project(":sfs-aop"))` 추가

**신규 클래스 — sfs-aop:**
- [ ] 3. 애노테이션 5종 (`@Aspect`, `@Loggable`, `@Around`, `@Before`, `@After`)
- [ ] 4. `AdviceType` enum + `AdviceInfo` record
- [ ] 5. `JoinPoint`/`ProceedingJoinPoint` 인터페이스 + `MethodInvocationJoinPoint` 구현 + `MethodInvocationJoinPointTest` (1건, `proceed()` 위임 검증)
- [ ] 6. `AspectRegistry` + 단위 테스트 (~4건)
- [ ] 7. `AdviceInterceptor` + 단위 테스트 (~7건, advice 종류별 분기 + 합성 + 예외)
- [ ] 8. `AspectEnhancingBeanPostProcessor` + 단위 테스트 (~6건, Aspect 등록 / 일반 enhance / BPP 자기 격리 / final 한계)
- [ ] 9. `AspectIntegrationTest` 통합 테스트 (~4건)

**시연 — sfs-samples:**
- [ ] 10. `LoggingAspect` 신설 (`@Aspect @Component`, `@Around`/`@Before`/`@After` 3종 + `@Autowired IdGenerator`)
- [ ] 11. `TodoController.create()`에 `@Loggable` 부착
- [ ] 12. `AppConfig`에 `@Bean AspectEnhancingBeanPostProcessor` 추가
- [ ] 13. `TodoDemoApplicationTest` 출력 박제 갱신 (8 → 11 라인, B2/C1/C2 단계별)

**문서:**
- [ ] 14. `sfs-aop/README.md` 신설 — 모듈 책임, advice 종류, 사용법, 한계
- [ ] 15. `sfs-samples/README.md` Phase 2B 학습 가치 반영 (LoggingAspect 시연 + 마일스톤 표 갱신)

**품질:**
- [ ] 16. `./gradlew :sfs-aop:test :sfs-samples:test` 모두 PASS
- [ ] 17. `./gradlew build` 전체 PASS + 누적 ~166~169 PASS / 0 FAIL
- [ ] 18. 마감 게이트 3단계 (다관점 리뷰 + 리팩토링 + simplify 패스) 실행 후 기록

---

## 11. 후속 작업 (본 spec 범위 외)

- **Phase 2B → main 머지** — `feat/phase2b-aop` 브랜치 + GitHub PR (Phase 2A 동일 패턴)
- **Phase 2C** — Phase 2A 이월 4건 + 본 phase 박제 후속 (final 메서드 silent skip 검출, 인스턴스 교체 시점 self-reference 처리, `@Aspect`/`@Component` 누락 자동 검출)
- **Phase 3 (가칭)** — `@AfterReturning`/`@AfterThrowing` advice, AspectJ 표현식 파서 mini 버전, advice 우선순위 (`@Order`), `@EnableAspect`/`@Import` 자동 등록 트리거

---

## 12. 학습 가치 매핑

| Phase 2B 항목 | 학습 가치 | 이후 재활용 |
|---|---|---|
| `sfs-aop` 모듈 분리 | 모듈 자치 + 의존 방향 | Phase 2C+의 추가 모듈 (sfs-tx 등) |
| 애노테이션 매칭 (Q2-A) | "AOP의 본질 = 메타데이터 기반 가로채기" | Spring Boot `@Transactional`/`@Cacheable` 사용 패턴 직결 |
| advice 3종 (Q3-B) | 권한 사다리 (@Before≠@Around) | Phase 3 5종 확장 시 *반대 방향 학습* |
| BFPP/BPP 시점 분리 (Q4-C) | Phase 1B-β 라이프사이클 분리의 진짜 보상 | Cache/Tx 등 다른 BPP 후손 |
| Aspect = 빈 (Q5-A) | "AOP = IoC 안에 접어 넣은 시스템" | advice가 컨테이너 의존 사용 가능 |
| advice 합성 = 함수 합성 | lambda 중첩으로 chain 본질 박제 | Phase 3 advice 우선순위/체인 확장 |
| 인스턴스 교체 (필드 복사) | reflection의 한계 (final 필드) 직접 체험 | Phase 3 target 위임 wrapper |
| BPP 자기 격리 | `registerBeanPostProcessors()` 단계 분리의 진짜 시험 | 다중 BPP 병존 phase |

> **본 phase의 메타 학습 가치:** "Phase 2A에서 깐 byte-buddy 인프라 + 라이프사이클 분리는 *AOP 같은 2차 추상*이 등장할 때 *어떻게 자연스럽게 재활용되는가*를 보여주는 phase. 새로 만드는 추상이 적고, 대부분 *기존 인프라를 한 번 더 호출*하는 형태." 학습 phase가 누적적으로 압축되는 모습의 첫 사례.
