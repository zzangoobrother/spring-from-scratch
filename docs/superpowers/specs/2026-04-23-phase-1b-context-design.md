# Phase 1B — sfs-context (ApplicationContext + 애노테이션 처리기) 설계 스펙

- **작성일:** 2026-04-23
- **페이즈:** Phase 1B (Phase 1A 완료 후속)
- **관련 문서:**
  - 상위 설계: `docs/superpowers/specs/2026-04-19-ioc-container-design.md`
  - 선행 plan: `docs/superpowers/plans/2026-04-19-phase-1a-scaffolding-and-beans.md` (완료)
- **선행 조건:** Plan 1A 완료 (sfs-core, sfs-beans 64 테스트 PASS, main 머지 8c8664f)

---

## 1. 배경과 목표

### 1.1 Phase 1A 완료 시점의 상태

Plan 1A 완료로 다음이 갖춰졌다:

- `sfs-core`: 예외 계층, 유틸, ASM 기반 클래스패스 스캐너
- `sfs-beans`: `BeanFactory`/`BeanDefinition`/3-level 싱글톤 캐시/`AbstractBeanFactory`/`DefaultListableBeanFactory`/BPP·IABPP·SIABPP·BFPP 인터페이스/세터 순환 참조 해결
- 통합 테스트 64개 PASS (DoD #7 Gap 메우기 포함)

그러나 다음 시나리오는 아직 동작하지 않는다:

```java
@Component
class MyService { @Autowired MyRepository repo; }

var ctx = new AnnotationConfigApplicationContext("com.example");
ctx.getBean(MyService.class).repo;  // null — 자동 주입 미구현
```

### 1.2 Phase 1B의 목표

위 사용 패턴이 동작하도록 `sfs-context` 모듈을 추가한다. 구체적으로:

1. `ApplicationContext` 계층과 `refresh()` 8단계 라이프사이클 도입
2. `@Component` 계열 애노테이션 + 메타-애노테이션 인식 스캐너
3. `@Configuration` + `@Bean` 클래스 처리 (byte-buddy enhance)
4. `@Autowired`/`@PostConstruct`/`@PreDestroy` 처리기 3종

### 1.3 Phase 1B 종료 시점의 상태

- 위 1.1 사용 패턴이 그대로 동작
- `sfs-context` 모듈에서 100+ 테스트 추가
- spec line 47의 의존성 정책 amendment 반영 (byte-buddy 추가)

---

## 2. 분할 전략 — 1B-α / 1B-β

Plan 1A가 33 Task였던 점을 고려해 1B를 **두 개의 독립 plan md**로 분리한다. 각 plan은 자체 DoD를 가지며, 사이에 main 머지 게이트를 둔다.

### 2.1 Plan 1B-α: 컨테이너 인프라 + 라이프사이클

**범위 (예상 18~22 Task)**

- 새 모듈 `sfs-context` (의존: `sfs-beans`)
- `gradle/libs.versions.toml`에 byte-buddy 1.14.x 카탈로그 등록 (1B-α는 미사용, 1B-β 대비)
- 애노테이션 10종: `@Component`, `@Service`, `@Repository`, `@Controller`, `@Configuration`, `@Bean`, `@Scope`, `@Lazy`, `@Primary`, `@Qualifier`
- `ApplicationContext` 계층 5종: `ApplicationContext`, `ConfigurableApplicationContext`, `AbstractApplicationContext`, `GenericApplicationContext`, `AnnotationConfigApplicationContext`
- 보조 클래스: `AnnotatedBeanDefinitionReader`, `ClassPathBeanDefinitionScanner`, `BeanNameGenerator` (기본 구현)
- `AnnotationUtils` 신규 (sfs-core, 메타-애노테이션 재귀 인식)
- `refresh()` 8단계 골격 (5/6단계는 처리기 컬렉션이 비어있어 사실상 no-op)
- `close()` + JVM shutdown hook + refresh 실패 시 자동 destroy

**1B-α 끝 시점 동작:** `AnnotationConfigApplicationContext` 사용 가능, 단 `@Autowired`는 효과 없음 (필드 null).

### 2.2 Plan 1B-β: 처리기 3종 + 자동 주입

**범위 (예상 14~18 Task)**

- 애노테이션 3종: `@Autowired`, `@PostConstruct`, `@PreDestroy`
- `ConfigurationClassPostProcessor` (BFPP) — byte-buddy로 `@Configuration` enhance, `@Bean` 메서드 → BeanDefinition (`factoryBeanName` + `factoryMethodName` 모드)
- `AutowiredAnnotationBeanPostProcessor` (IABPP) — 필드/세터/생성자 주입, 단일 ctor 자동, `List<T>`/`Map<String, T>`, `required=false`
- `CommonAnnotationBeanPostProcessor` (BPP) — `@PostConstruct`/`@PreDestroy`
- 통합 테스트 — refresh 8단계 + 자동 주입 + 라이프사이클 종합

**1B-β 끝 시점:** spec line 418~419 Phase 1 종료 DoD 모두 만족.

---

## 3. 모듈 구조 + 의존성 변경

### 3.1 Gradle 모듈 트리

```
spring-from-scratch/
├── sfs-core/        (기존)
├── sfs-beans/       (기존)
├── sfs-context/     ← 신규 추가
│   ├── build.gradle.kts
│   └── src/{main,test}/java/com/choisk/sfs/context/
└── settings.gradle.kts  ← include("sfs-context") 추가
```

**의존 방향 (CLAUDE.md 그래프 준수):**

```
sfs-context ──► sfs-beans ──► sfs-core
```

### 3.2 sfs-context build.gradle.kts (요지)

```kotlin
plugins { id("sfs.java-library") }
dependencies {
    api(project(":sfs-beans"))
    implementation(libs.bytebuddy)            // 1B-β에서 ConfigurationClassEnhancer가 사용
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
```

### 3.3 byte-buddy 의존 정책 (Section 2 결정 — 안 C)

- **선언 시점:** 1B-α 첫 Task에서 카탈로그(`gradle/libs.versions.toml`) + `sfs-context/build.gradle.kts`에 동시 선언
- **사용 시점:** 1B-β의 `ConfigurationClassEnhancer`에서만
- **버전:** `1.14.x` (Java 25 호환 확인된 라인). 카탈로그에 `bytebuddy = "1.14.x"` 형태로 고정
- **README/주석 기록:** `sfs-context/README.md`에 "1B-β에서 사용 예정" 명시

### 3.4 패키지 구조 (1B-α + 1B-β 통합)

```
com.choisk.sfs.context
├── ApplicationContext              (interface)
├── ConfigurableApplicationContext  (interface)
├── annotation/
│   ├── Component, Service, Repository, Controller, Configuration  (1B-α)
│   ├── Bean, Scope, Lazy, Primary, Qualifier                      (1B-α)
│   └── Autowired, PostConstruct, PreDestroy                       (1B-β)
└── support/
    ├── AbstractApplicationContext                  (1B-α)
    ├── GenericApplicationContext                   (1B-α)
    ├── AnnotationConfigApplicationContext          (1B-α)
    ├── AnnotatedBeanDefinitionReader               (1B-α)
    ├── ClassPathBeanDefinitionScanner              (1B-α)
    ├── AnnotationBeanNameGenerator                 (1B-α)
    ├── ConfigurationClassPostProcessor             (1B-β)
    ├── ConfigurationClassEnhancer                  (1B-β, byte-buddy)
    ├── AutowiredAnnotationBeanPostProcessor        (1B-β)
    ├── CommonAnnotationBeanPostProcessor           (1B-β)
    └── DependencyDescriptor                        (1B-β)
```

**메타-애노테이션 처리 위치:** `sfs-core/annotation/AnnotationUtils` (Section 2 결정). 향후 `sfs-aop`에서도 재사용 가능.

### 3.5 spec line 47 amendment (1B-α 첫 Task로 적용)

**현재 spec line 47:**
> ASM 외 외부 의존이 없다

**amendment 후:**
> 외부 런타임 의존: ASM(클래스패스 스캔), byte-buddy(`@Configuration` 클래스 enhance).
> 그 외 의존 추가는 spec 개정을 요구한다.

amendment 커밋과 첫 빌드 변경(카탈로그 + module 추가)은 **단일 커밋**으로 묶는다 (`docs+chore: spec 의존성 정책 amendment + sfs-context 모듈 신설`).

---

## 4. Plan 1B-α 핵심 클래스 설계

### 4.1 인터페이스 계층

```java
// com.choisk.sfs.context.ApplicationContext
public interface ApplicationContext extends BeanFactory {
    String getId();
    String getApplicationName();
    long getStartupDate();
}

// com.choisk.sfs.context.ConfigurableApplicationContext
public interface ConfigurableApplicationContext extends ApplicationContext, AutoCloseable {
    void refresh();
    void close();
    boolean isActive();
    void registerShutdownHook();
    ConfigurableListableBeanFactory getBeanFactory();   // Section 3 결정 - 스펙 일치
    void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);
}
```

### 4.2 AbstractApplicationContext — refresh() 8단계 템플릿

```java
public abstract class AbstractApplicationContext implements ConfigurableApplicationContext {
    private final List<BeanFactoryPostProcessor> bfpps = new ArrayList<>();
    private volatile boolean active = false;
    private final Object startupShutdownMonitor = new Object();
    private Thread shutdownHook;
    private long startupDate;
    private final String id = ObjectUtils.identityToString(this);

    public final void refresh() {
        synchronized (startupShutdownMonitor) {
            prepareRefresh();                             // 1
            ConfigurableListableBeanFactory bf =
                obtainFreshBeanFactory();                 // 2
            prepareBeanFactory(bf);                       // 3
            try {
                postProcessBeanFactory(bf);               // 4 (hook)
                invokeBeanFactoryPostProcessors(bf);      // 5
                registerBeanPostProcessors(bf);           // 6
                finishBeanFactoryInitialization(bf);      // 7
                finishRefresh();                          // 8
                active = true;
            } catch (RuntimeException ex) {
                destroyBeans();
                cancelRefresh(ex);
                throw ex;
            }
        }
    }

    protected abstract void refreshBeanFactory();
    protected abstract ConfigurableListableBeanFactory getBeanFactory();
}
```

**1B-α에서 5/6단계 동작:** BFPP/BPP 컬렉션은 비어있어 사실상 no-op. 단 호출 코드는 들어있어 1B-β에서 그대로 작동.

### 4.3 GenericApplicationContext

```java
public class GenericApplicationContext extends AbstractApplicationContext {
    private final DefaultListableBeanFactory beanFactory;
    private final AtomicBoolean refreshed = new AtomicBoolean(false);

    public GenericApplicationContext() {
        this.beanFactory = new DefaultListableBeanFactory();
    }
    public GenericApplicationContext(DefaultListableBeanFactory bf) {
        this.beanFactory = Objects.requireNonNull(bf);
    }

    public void registerBeanDefinition(String name, BeanDefinition bd) {
        beanFactory.registerBeanDefinition(name, bd);
    }

    @Override protected void refreshBeanFactory() {
        if (!refreshed.compareAndSet(false, true)) {
            throw new IllegalStateException("GenericApplicationContext refresh() already called");
        }
    }
    @Override public ConfigurableListableBeanFactory getBeanFactory() { return beanFactory; }
}
```

**single-shot 정책:** Spring 원본과 동일하게 `refresh()`는 한 번만 허용.

### 4.4 AnnotationConfigApplicationContext

```java
public class AnnotationConfigApplicationContext extends GenericApplicationContext {
    private final AnnotatedBeanDefinitionReader reader;
    private final ClassPathBeanDefinitionScanner scanner;

    public AnnotationConfigApplicationContext() {
        this.reader = new AnnotatedBeanDefinitionReader(this);
        this.scanner = new ClassPathBeanDefinitionScanner(this);
        // 1B-β 시작 Task에서 처리기 3종 자동 등록 추가
    }
    public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
        this();
        register(componentClasses);
        refresh();
    }
    public AnnotationConfigApplicationContext(String... basePackages) {
        this();
        scan(basePackages);
        refresh();
    }

    public void register(Class<?>... componentClasses) { reader.register(componentClasses); }
    public void scan(String... basePackages)          { scanner.scan(basePackages); }
}
```

**두 진입점 모두 1B-α에서 지원** (Q3-A 결정).

### 4.5 ClassPathBeanDefinitionScanner

```java
public class ClassPathBeanDefinitionScanner {
    private final BeanDefinitionRegistry registry;
    private final BeanNameGenerator nameGenerator;  // 기본: AnnotationBeanNameGenerator

    public int scan(String... basePackages) {
        int count = 0;
        for (String pkg : basePackages) {
            for (Class<?> clazz : ClassPathScanner.scan(pkg)) {  // 기존 sfs-core 유틸 재사용
                if (!AnnotationUtils.isAnnotated(clazz, Component.class)) continue;
                String name = nameGenerator.generate(clazz);
                BeanDefinition bd = new BeanDefinition(clazz);
                applyScopeAndLazy(bd, clazz);
                registry.registerBeanDefinition(name, bd);
                count++;
            }
        }
        return count;
    }
}
```

**메타-애노테이션 인식:** `AnnotationUtils.isAnnotated(class, target)`이 재귀 탐색 + 사이클 방지 (Q5 결정).

### 4.6 BeanNameGenerator 정책

- 기본: `AnnotationBeanNameGenerator` — FQN의 클래스 단순명을 camelCase로 (`com.x.MyService` → `myService`)
- `@Component("custom")` 명시 시 그 값이 우선 (Section 3 Q4 OK)

### 4.7 close() + shutdown hook

```java
@Override
public void close() {
    synchronized (startupShutdownMonitor) {
        if (!active) return;          // idempotent
        doClose();
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignore) {}  // JVM shutdown 진행 중이면 정상
        }
    }
}

@Override
public void registerShutdownHook() {
    if (shutdownHook != null) return;  // idempotent
    shutdownHook = new Thread(this::doClose, "sfs-context-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
}

private void doClose() {
    active = false;
    destroyBeans();  // -> beanFactory.destroySingletons()
}
```

**refresh() 실패 시:** 위 4.2의 catch가 자동으로 destroyBeans + cancelRefresh.

### 4.8 1B-α 끝 시점 동작 시나리오

```java
var ctx = new AnnotationConfigApplicationContext("com.example.demo");
//  → 1) 스캐너가 @Component 클래스 발견
//  → 2) BeanDefinition 등록
//  → 3) refresh() 호출 → preInstantiateSingletons()까지 완료
//  → 4) 단, @Autowired 필드는 null 상태 (1B-β 전)

MyService svc = ctx.getBean(MyService.class);
ctx.close();  // destroy 콜백 + shutdown hook 정리
```

---

## 5. Plan 1B-β 처리기 3종 설계

### 5.1 처리기 매핑표

| 처리기 | 타입 | 트리거 시점 | 책임 |
|---|---|---|---|
| `ConfigurationClassPostProcessor` | BFPP | refresh() 5단계 | `@Configuration` enhance(byte-buddy) + `@Bean` 메서드 → BeanDefinition |
| `AutowiredAnnotationBeanPostProcessor` | IABPP | 4단계 + 빈 생성의 properties phase | `@Autowired` 필드/세터/생성자 자동 주입 |
| `CommonAnnotationBeanPostProcessor` | BPP | 빈 생성의 init/destroy phase | `@PostConstruct` 호출, `@PreDestroy` 등록 |

### 5.2 ConfigurationClassPostProcessor

**책임 1 — `@Configuration` 클래스 enhance:**

```java
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            if (!isConfigurationClass(bd)) continue;

            // proxyBeanMethods=false면 enhance 스킵 (Q3 지원)
            Configuration cfg = bd.getBeanClass().getAnnotation(Configuration.class);
            if (cfg.proxyBeanMethods()) {
                Class<?> enhanced = ConfigurationClassEnhancer.enhance(bd.getBeanClass(), bf);
                bd.setBeanClass(enhanced);
            }

            // @Bean 메서드 → 새 BeanDefinition
            for (Method m : bd.getBeanClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) continue;
                BeanDefinition beanBd = new BeanDefinition(m.getReturnType());
                beanBd.setFactoryBeanName(name);
                beanBd.setFactoryMethodName(m.getName());
                applyScopeAndLazy(beanBd, m);
                bf.registerBeanDefinition(resolveBeanName(m), beanBd);
            }
        }
    }
}
```

**책임 2 — byte-buddy enhance 핵심:**

```java
class BeanMethodInterceptor {
    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args) {
        String beanName = resolveBeanName(method);
        return beanFactory.getBean(beanName);  // 컨테이너 라우팅
    }
}
```

inter-bean reference: `@Bean methodA()` 안에서 `methodB()`를 호출해도 enhance가 가로채 `getBean("methodB")`로 라우팅 → 항상 동일 싱글톤.

### 5.3 BeanDefinition 확장 (factoryMethod 모드)

> **수정 대상 모듈:** `sfs-beans` (1B-β에서 `sfs-context`가 이 기능을 요구). 모듈 의존 그래프상 `sfs-beans`가 `sfs-context`를 알 필요가 없으므로 BeanDefinition에 필드만 추가하고, factoryMethod 호출 분기는 `sfs-beans`의 `AbstractAutowireCapableBeanFactory`(또는 그에 상응하는 createBean 경로)에서 처리한다.

```java
private String factoryBeanName;    // null이 아니면 factoryBean.factoryMethod(args) 호출
private String factoryMethodName;
```

`AbstractAutowireCapableBeanFactory.createBean` 분기 (1B-β에서 sfs-beans에 추가):

```java
if (def.getFactoryMethodName() != null) {
    Object factoryBean = getBean(def.getFactoryBeanName());
    Method m = factoryBean.getClass().getMethod(def.getFactoryMethodName(), ...);
    return m.invoke(factoryBean, resolvedArgs);  // ← inter-bean ref은 enhance가 가로챔
} else {
    return instantiateViaConstructor(def);  // 1A 기존 경로
}
```

### 5.4 AutowiredAnnotationBeanPostProcessor

**3가지 주입 경로:**

| 경로 | 시점 | 메서드 |
|---|---|---|
| 생성자 주입 | 인스턴스 생성 시 | `determineCandidateConstructors` |
| 필드 주입 | properties phase | `postProcessProperties` (reflection field.set) |
| 세터 주입 | properties phase | `postProcessProperties` (setter 호출) |

**생성자 자동 검출 (Spring 4.3+ 정책, Q10):**

```java
public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
    Constructor<?>[] all = beanClass.getDeclaredConstructors();
    var explicit = Arrays.stream(all).filter(c -> c.isAnnotationPresent(Autowired.class)).toList();
    if (!explicit.isEmpty()) return explicit.toArray(Constructor[]::new);
    if (all.length == 1 && all[0].getParameterCount() > 0) return all;
    return null;
}
```

**의존 해석 (`resolveDependency` — `DefaultListableBeanFactory`에 추가):**

```java
public Object resolveDependency(DependencyDescriptor desc, String requestingBeanName) {
    Class<?> type = desc.getDependencyType();

    // List<T>
    if (List.class.isAssignableFrom(type)) {
        Class<?> elementType = desc.getResolvableType().getGenerics()[0];
        return new ArrayList<>(getBeansOfType(elementType).values());
    }
    // Map<String, T>
    if (Map.class.isAssignableFrom(type)) { ... }

    Map<String, Object> matches = getBeansOfType(type);
    if (matches.isEmpty()) {
        if (desc.isRequired()) throw new NoSuchBeanDefinitionException(...);
        return null;  // required=false 지원 (Q4 지원)
    }
    if (matches.size() == 1) return matches.values().iterator().next();

    // 다수 후보: @Primary → @Qualifier → 필드명 매칭 (Q2 결정)
    return determineAutowireCandidate(matches, desc);
}
```

### 5.5 CommonAnnotationBeanPostProcessor

```java
public class CommonAnnotationBeanPostProcessor implements BeanPostProcessor {
    public Object postProcessBeforeInitialization(Object bean, String name) {
        for (Method m : bean.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(PostConstruct.class)) {
                m.setAccessible(true);
                m.invoke(bean);
            }
        }
        return bean;
    }

    public Object postProcessAfterInitialization(Object bean, String name) {
        var preDestroyMethods = findAnnotated(bean.getClass(), PreDestroy.class);
        if (!preDestroyMethods.isEmpty()) {
            registry.registerDisposableBean(name, () -> invokeAll(preDestroyMethods, bean));
        }
        return bean;
    }
}
```

**라이프사이클 통합 순서 (1A 검증 + 1B-β 추가):**

```
awareName  →  BPP:before (= @PostConstruct 호출) →  afterProps  →  customInit  →  BPP:after
                                                                                      ↓
                                                                          @PreDestroy 등록
```

### 5.6 @Lazy / @Primary / @Qualifier 처리

| 애노테이션 | 처리 위치 | 처리 모듈 | 동작 |
|---|---|---|---|
| `@Lazy` (class-level, Q9) | 스캐너에서 `bd.lazy=true` 설정 + `preInstantiateSingletons`에서 분기 | sfs-context 스캐너 + sfs-beans `preInstantiateSingletons` 보강 | `bd.lazy=true` 빈은 preInstantiate skip → 첫 `getBean()` 시 생성 |
| `@Primary` | `resolveDependency` 다수 후보 분기 | sfs-beans `resolveDependency` 신규 메서드 | 매칭 빈 중 `bd.primary=true` 우선 선택 |
| `@Qualifier` | `DependencyDescriptor` | sfs-beans `DependencyDescriptor` + sfs-context의 IABPP에서 descriptor 생성 | descriptor의 qualifier 값으로 이름/qualifier 필터링 |

> **모듈 경계 정리:** `BeanDefinition`/`DependencyDescriptor`/`resolveDependency`/`preInstantiateSingletons` 보강은 `sfs-beans`의 일반 컨테이너 기능이므로 `sfs-beans`에서 처리. 애노테이션 메타정보를 BeanDefinition으로 옮기는 책임은 `sfs-context`의 스캐너/리더/IABPP에 위치.

### 5.7 자동 처리기 등록

`AnnotationConfigApplicationContext` 생성자 (1B-β 첫 Task에서 추가):

```java
public AnnotationConfigApplicationContext() {
    this.reader = new AnnotatedBeanDefinitionReader(this);
    this.scanner = new ClassPathBeanDefinitionScanner(this);

    // 1B-β: 처리기 3종 자동 등록
    addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
    getBeanFactory().addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(getBeanFactory()));
    getBeanFactory().addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(getBeanFactory()));
}
```

---

## 6. 테스트 전략

### 6.1 TDD 적용/제외 매트릭스 (CLAUDE.md 가이드)

**Plan 1B-α**

| 컴포넌트 | TDD | 근거 |
|---|---|---|
| `ApplicationContext` interface | 제외 | 시그니처만 |
| `ConfigurableApplicationContext` interface | 제외 | 시그니처만 |
| 애노테이션 10종 | 제외 | 메타정보만 |
| `AbstractApplicationContext` (refresh 템플릿) | **적용** | try-catch cleanup, single-shot 분기 본질 |
| `GenericApplicationContext` | **적용** | single-shot 가드 |
| `AnnotationConfigApplicationContext` | 제외 | reader/scanner 위임. 통합 테스트로 검증 |
| `ClassPathBeanDefinitionScanner` | **적용** | 패키지 스캔, 메타-인식 분기, scope/lazy 적용 |
| `AnnotatedBeanDefinitionReader` | **적용** | `@Component`/`@Scope`/`@Lazy`/`@Primary` 추출 분기 |
| `AnnotationUtils` (sfs-core) | **적용** | 메타 재귀 탐색 + 사이클 방지가 본질 |
| `BeanNameGenerator` | **적용** | FQN→camelCase + `@Component("custom")` 우선순위 |
| close() / shutdown hook | **적용** | idempotent + JVM shutdown 진행 중 예외 처리 |

**Plan 1B-β**

| 컴포넌트 | TDD | 근거 |
|---|---|---|
| `ConfigurationClassPostProcessor` | **적용** | `@Bean` 추출, factoryBean/Method BD 생성 분기 |
| `ConfigurationClassEnhancer` (byte-buddy) | **적용** | enhance + intercept가 컨테이너 라우팅 보장 |
| `AutowiredAnnotationBeanPostProcessor` | **적용** | 필드/세터/생성자 3경로, `required=false`, 단일 ctor 자동 |
| `CommonAnnotationBeanPostProcessor` | **적용** | `@PostConstruct` 호출 시점, `@PreDestroy` 등록 |
| `DependencyDescriptor` | **적용** | `required` 플래그, 제네릭 추출 |
| `resolveDependency` | **적용** | List/Map 분기, @Primary→@Qualifier→이름 폴백 |
| 애노테이션 3종 | 제외 | 메타정보만 |

### 6.2 테스트 패키지 구조

```
sfs-context/src/test/java/com/choisk/sfs/context/
├── support/
│   ├── AbstractApplicationContextTest.java
│   ├── GenericApplicationContextTest.java
│   ├── ClassPathBeanDefinitionScannerTest.java
│   ├── AnnotatedBeanDefinitionReaderTest.java
│   ├── ConfigurationClassPostProcessorTest.java         (1B-β)
│   ├── AutowiredAnnotationBeanPostProcessorTest.java    (1B-β)
│   └── CommonAnnotationBeanPostProcessorTest.java       (1B-β)
├── integration/
│   ├── RefreshLifecycleIntegrationTest.java             (1B-α)
│   ├── CloseAndShutdownHookTest.java                    (1B-α)
│   ├── RefreshFailureCleanupTest.java                   (1B-α)
│   ├── ComponentScanIntegrationTest.java                (1B-α)
│   ├── AnnotationConfigContextIntegrationTest.java      (1B-β)
│   ├── ConfigurationInterBeanReferenceTest.java         (1B-β)
│   ├── AutowiredCollectionInjectionTest.java            (1B-β)
│   ├── PrimaryAndQualifierTest.java                     (1B-β)
│   ├── LazyInitializationTest.java                      (1B-β)
│   └── PostConstructPreDestroyOrderTest.java            (1B-β)
└── samples/
    ├── basic/             (1B-α)
    ├── config/            (1B-β)
    ├── autowired/         (1B-β)
    └── lifecycle/         (1B-β)
```

**샘플 위치:** `src/test/java/.../samples/` 단일 위치 (Q1 OK).
**Tracing 컨텍스트:** 통합 테스트 파일 내부 inner class (Q2, 1A 패턴 일치).

### 6.3 핵심 통합 테스트 시나리오

**1B-α 필수 4선:**

- `RefreshLifecycleIntegrationTest.refreshExecutesEightStepsInOrder` — 8단계 순서 검증
- `RefreshFailureCleanupTest.refreshFailureTriggersDestroyBeans` — 5단계 throw 시 destroy 검증
- `ComponentScanIntegrationTest.scanRegistersComponentsAndMetaAnnotated` — `@Service` 메타 인식
- `CloseAndShutdownHookTest.closeIsIdempotentAndRemovesShutdownHook` — idempotent + hook 제거

**1B-β 필수 6선:**

- `ConfigurationInterBeanReferenceTest.interBeanReferenceReturnsSameInstance` — byte-buddy 검증의 핵심
- `AutowiredCollectionInjectionTest.listInjectionContainsAllMatchingBeans`
- `PrimaryAndQualifierTest.primaryWinsOverOthers` + `qualifierOverridesPrimary`
- `LazyInitializationTest.lazyBeanIsNotInstantiatedAtRefresh`
- `PostConstructPreDestroyOrderTest.postConstructFiresAfterAwareBeforeAfterProps`
- `AutowiredRequiredFalseTest.requiredFalseAllowsNullDependency`

### 6.4 byte-buddy 테스트 시 주의사항

- enhance 클래스는 `getClass().getSimpleName()` 직접 비교 금지 (Spring처럼 `$$EnhancerByByteBuddy$$` 접미사 가능)
- `getSuperclass().getSimpleName()` 또는 `getDeclaredField`/`getDeclaredMethod`로 검증
- ClassLoader 격리는 하지 않음 (학습 프로젝트 범위 over-engineering, Q3 OK)
- enhance 결과는 stateless하게 설계 — 테스트 간 캐시되어도 무방

### 6.5 회귀 테스트 정책

각 Plan Task 완료 시:

```bash
./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test
```

| 시점 | 누적 테스트 수 (예상) |
|---|---|
| 1A 완료 | 64 |
| 1B-α 완료 | 89~94 (1B-α +25~30) |
| 1B-β 완료 | 114~119 (1B-β +25) |

이 명령은 1B Plan 문서의 모든 Task verification에 명시한다 (Q4 OK).

---

## 7. DoD (Definition of Done)

### 7.1 Plan 1B-α DoD (14항목)

**기능적 DoD**

1. `AnnotationConfigApplicationContext("com.x.pkg")` 사용 시 `@Component` 클래스가 BeanDefinition으로 등록된다
2. `AnnotationConfigApplicationContext(MyConfig.class)` 사용 시 명시 등록 + refresh가 동작한다
3. `@Service`/`@Repository`/`@Controller`는 `@Component` 메타-인식으로 자동 등록된다
4. `refresh()`는 8단계를 순서대로 실행한다 (`TracingApplicationContext` 통합 테스트로 검증)
5. `refresh()` 5/6단계는 BFPP/BPP 컬렉션이 비어 있어도 정상 통과한다
6. `refresh()` 도중 예외 발생 시 `destroyBeans()` + `cancelRefresh()`가 자동 호출되어 부분 생성 빈이 정리된다
7. `close()`는 idempotent하다 (두 번 호출해도 안전)
8. `registerShutdownHook()` 후 JVM 종료 시 destroy 콜백이 실행된다
9. `@Scope("prototype")` 클래스는 prototype BeanDefinition으로 등록된다
10. `@Lazy` 클래스는 `preInstantiateSingletons()`에서 skip되고, 첫 `getBean()` 시 생성된다
11. `@Component("custom")` 명시 시 그 이름이 `BeanNameGenerator` 기본값을 override한다
12. `AnnotationUtils.isAnnotated`는 메타-애노테이션을 재귀 탐색하며 사이클 방지가 동작한다

**품질 DoD**

13. `./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test` 모두 PASS
14. `sfs-context` 모듈 README 작성 (Spring 매핑표 + 1B-α 시점 동작 시나리오)

### 7.2 Plan 1B-β DoD (13항목)

**기능적 DoD**

1. `@Configuration` + `@Bean` 클래스의 빈이 등록되며, `@Bean methodName()`이 빈 이름이 된다
2. `@Bean("custom")` 명시 시 그 이름이 우선한다
3. `@Configuration` 클래스의 inter-bean reference는 컨테이너 싱글톤을 반환한다 (byte-buddy enhance 검증)
4. `@Configuration(proxyBeanMethods = false)`는 enhance를 생략한다
5. `@Autowired` 필드 주입이 동작한다
6. `@Autowired` 세터 주입이 동작한다
7. 단일 non-default 생성자는 `@Autowired` 없이도 자동 검출된다 (Spring 4.3+)
8. `@Autowired(required=false)`는 매칭 빈이 없을 때 null을 주입한다
9. `List<T>`/`Map<String, T>` 컬렉션 주입이 동작한다 (모든 매칭 빈 수집)
10. 다수 후보 시 `@Primary` → `@Qualifier` → 필드명 매칭 폴백 순서가 적용된다
11. `@PostConstruct` 메서드는 `BPP:before` 시점에 호출된다 (라이프사이클 5단계 검증)
12. `@PreDestroy` 메서드는 `close()` 시 등록 역순으로 호출된다

**품질 DoD**

13. spec line 418~419의 Phase 1 종료 DoD 전 항목 만족 확인

---

## 8. spec amendment 항목 (Phase 1B 진행 중 적용)

### A. line 47 (1B-α 첫 Task로 적용)

ASM 단독 → ASM + byte-buddy 정책 변경. 위 3.5 참조.

### B. line 180~250 (sfs-context 클래스 + refresh 8단계)

1B-α 첫 Task에서 spec 원문을 다시 정독하고, 본 design doc과 차이가 있으면 spec을 본 design doc 기준으로 amendment. 후보:

- `getBeanFactory()` 반환 타입 `ConfigurableListableBeanFactory` 명시
- `register/scan` 두 진입점 모두 1B-α 지원 명시
- `close()` idempotent + JVM shutdown 진행 중 예외 처리 정책 추가

### C. line 355~434 (실패 복구 + DoD)

1B-α 첫 Task에서 spec 원문 정독. 본 design doc 7.1/7.2와 차이 있으면 spec 보강.

---

## 9. 다음 단계 (이 spec 승인 후)

1. **Plan 1B-α 작성:** `docs/superpowers/plans/2026-04-23-phase-1b-alpha-context-infra.md`
2. **Plan 1B-β 작성:** `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md`
3. `superpowers:writing-plans` skill 호출 (NEW 파일 작성 지침)
4. Plan 1B-α 실행 → main 머지 게이트
5. Plan 1B-β 실행 → main 머지 → Phase 1 종료

---

## 부록 — 결정 사항 요약 (브레인스토밍 Q&A)

| Q# | 결정 |
|---|---|
| Q1 | Plan을 1B-α/β로 분리 (B) |
| Q2 | 애노테이션 10/3 분리 (B) |
| Q3 | `register`/`scan` 두 진입점 모두 1B-α (A) |
| Q4 | `close()`는 1B-α (A) |
| Q5 | 메타-애노테이션 재귀 인식 (A) |
| Q6 | `@Configuration` enhance 지원 (B) |
| Q7 | byte-buddy 라이브러리 사용 (A) |
| Q8 | `List` + `Map` 컬렉션 주입 (A) |
| Q9 | `@Lazy` class-level only (B) |
| Q10 | 단일 ctor 자동 (Spring 4.3+) (A) |
| Q11 | JSR-330 미지원 (B) |
| Section 2 | byte-buddy: 안 C (1B-α 카탈로그 등록, β 사용) / `AnnotationUtils`는 sfs-core / spec amendment 문구 OK |
| Section 3 | `getBeanFactory()` 스펙 일치 / `AnnotatedBeanDefinitionReader` 별도 클래스 / single-shot OK / BeanNameGenerator OK |
| Section 4 | `@Bean` 메서드명 OK / 폴백 3단 OK / `proxyBeanMethods=false` 지원 / `required=false` 지원 |
| Section 5 | 샘플 위치 OK / Tracing inner class / ClassLoader 격리 없음 / 회귀 자동화 OK |
| Section 6 | DoD 14+13 OK / Plan md 분리 명명 OK / design doc 파일명 OK / amendment 시점 OK |
