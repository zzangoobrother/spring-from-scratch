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

> **사전 분석 (2026-04-25):** `BeanDefinition.java`를 확인한 결과, `factoryBeanName`/`factoryMethodName` 필드와 getter/setter가 **이미 존재**한다 (1B-α 품질 게이트 시점에 선제 추가된 것으로 추정). 따라서 구현 Step은 없고, 해당 필드가 올바르게 동작하는지 TDD로 검증하는 테스트만 작성한다. 실패 테스트는 `BeanDefinitionFactoryMethodTest` 신규 파일로 작성.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BeanDefinition의 factoryBeanName / factoryMethodName 라운드-트립 검증.
 * factoryMethodName != null 분기가 @Bean 메서드 인스턴스화 경로의 진입 조건이 되므로
 * 필드 설정 + 조회가 정확해야 한다.
 */
class BeanDefinitionFactoryMethodTest {

    static class MyConfig {}
    static class MyRepo {}

    @Test
    void factoryBeanNameRoundTrip() {
        BeanDefinition bd = new BeanDefinition(MyRepo.class);
        bd.setFactoryBeanName("myConfig");
        assertThat(bd.getFactoryBeanName()).isEqualTo("myConfig");
    }

    @Test
    void factoryMethodNameRoundTrip() {
        BeanDefinition bd = new BeanDefinition(MyRepo.class);
        bd.setFactoryMethodName("repo");
        assertThat(bd.getFactoryMethodName()).isEqualTo("repo");
    }

    @Test
    void bothFieldsSetTogether() {
        BeanDefinition bd = new BeanDefinition(MyRepo.class);
        bd.setFactoryBeanName("myConfig");
        bd.setFactoryMethodName("repo");
        assertThat(bd.getFactoryBeanName()).isEqualTo("myConfig");
        assertThat(bd.getFactoryMethodName()).isEqualTo("repo");
    }

    @Test
    void defaultValuesAreNull() {
        BeanDefinition bd = new BeanDefinition(MyRepo.class);
        assertThat(bd.getFactoryBeanName()).isNull();
        assertThat(bd.getFactoryMethodName()).isNull();
    }

    @Test
    void fluentSetterReturnsThis() {
        BeanDefinition bd = new BeanDefinition(MyRepo.class);
        // fluent setter는 this를 반환하므로 체이닝이 가능해야 함
        BeanDefinition returned = bd.setFactoryBeanName("cfg").setFactoryMethodName("makeRepo");
        assertThat(returned).isSameAs(bd);
        assertThat(bd.getFactoryBeanName()).isEqualTo("cfg");
        assertThat(bd.getFactoryMethodName()).isEqualTo("makeRepo");
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.BeanDefinitionFactoryMethodTest"
```

예상: 필드가 이미 존재하므로 실제로는 PASS일 가능성이 높음. PASS라면 Step 3(구현)은 생략하고 Step 4로 직행.
FAIL 시 예상 메시지: `cannot find symbol: method setFactoryBeanName(String)` — 이 경우 Step 3을 실행.

- [ ] **Step 3: 구현 — `BeanDefinition`에 필드 추가 (Step 2에서 FAIL 시에만 실행)**

Step 2가 FAIL인 경우에만 아래 코드를 `BeanDefinition.java`에 추가한다.

```java
// BeanDefinition 클래스 내 필드 선언부에 추가
private String factoryBeanName;
private String factoryMethodName;

// getter
public String getFactoryBeanName() { return factoryBeanName; }
public String getFactoryMethodName() { return factoryMethodName; }

// fluent setter
public BeanDefinition setFactoryBeanName(String name) { this.factoryBeanName = name; return this; }
public BeanDefinition setFactoryMethodName(String name) { this.factoryMethodName = name; return this; }
```

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.BeanDefinitionFactoryMethodTest"
```

예상: PASS (5건).

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/src/test/java/com/choisk/sfs/beans/BeanDefinitionFactoryMethodTest.java
git commit -m "test(sfs-beans): BeanDefinition factoryBeanName/factoryMethodName 라운드-트립 검증"
```

---

### Task A2: `AbstractAutowireCapableBeanFactory.createBean`에 factoryMethod 분기 추가

> **TDD 적용 여부:** 적용 — 인스턴스화 경로의 본질적 분기. constructor 경로 대신 `factoryBean.factoryMethod(args)` 호출로 라우팅.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/AbstractAutowireCapableBeanFactory.java` (또는 `DefaultListableBeanFactory.createBean` 위치)
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/CreateBeanFactoryMethodTest.java`

> **사전 분석 (2026-04-25):** `createBean`은 `AbstractAutowireCapableBeanFactory`에 위치 (`sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java`). 현재 `createBean` → `resolveBeforeInstantiation` → `doCreateBean` → `instantiateBean` 경로만 있음. `instantiateBean`에서 constructor 경로만 처리하므로, `doCreateBean` 도입부에 `factoryMethodName != null` 분기를 추가해야 한다.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * factoryMethod 모드 인스턴스화 검증.
 * factoryBeanName + factoryMethodName이 설정된 BeanDefinition은
 * constructor가 아닌 팩토리 메서드로 인스턴스를 생성해야 한다.
 */
class CreateBeanFactoryMethodTest {

    // @Configuration 클래스 역할을 하는 팩토리 빈
    static class AppConfig {
        public MyRepo repo() { return new MyRepo("from-factory"); }
    }

    static class MyRepo {
        final String source;
        MyRepo(String source) { this.source = source; }
        // 기본 생성자 없음 — factoryMethod 경로를 강제하기 위해
    }

    @Test
    void factoryMethodCreatesBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        // appConfig 빈 등록 (팩토리 빈 자체)
        factory.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));

        // myRepo 빈: factoryBeanName + factoryMethodName 모드
        BeanDefinition repoDef = new BeanDefinition(MyRepo.class);
        repoDef.setFactoryBeanName("appConfig");
        repoDef.setFactoryMethodName("repo");
        factory.registerBeanDefinition("myRepo", repoDef);

        MyRepo repo = (MyRepo) factory.getBean("myRepo");
        assertThat(repo).isNotNull();
        assertThat(repo.source).isEqualTo("from-factory");
    }

    @Test
    void factoryMethodReturnsSingletonByDefault() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));

        BeanDefinition repoDef = new BeanDefinition(MyRepo.class);
        repoDef.setFactoryBeanName("appConfig");
        repoDef.setFactoryMethodName("repo");
        factory.registerBeanDefinition("myRepo", repoDef);

        Object first = factory.getBean("myRepo");
        Object second = factory.getBean("myRepo");
        // 싱글톤이므로 동일 인스턴스
        assertThat(first).isSameAs(second);
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.CreateBeanFactoryMethodTest"
```

예상: FAIL — `MyRepo`에 기본 생성자가 없으므로 현재 `instantiateBean`에서 `BeanCreationException`이 발생하거나, 팩토리 메서드 분기가 없으므로 잘못된 경로 진입.

- [ ] **Step 3: 구현 — `AbstractAutowireCapableBeanFactory.doCreateBean`에 factoryMethod 분기 추가**

`sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java`의 `doCreateBean` 메서드 시작 부분에 다음 분기를 추가한다.

```java
protected Object doCreateBean(String beanName, BeanDefinition definition) {
    // B-2: 인스턴스화 — factoryMethod 모드 우선, 없으면 constructor 경로
    Object bean;
    if (definition.getFactoryMethodName() != null) {
        bean = instantiateViaFactoryMethod(beanName, definition);
    } else {
        bean = instantiateBean(beanName, definition);
    }

    // B-3: 3차 캐시에 팩토리 등록 (조기 참조용)
    boolean earlySingletonExposure = definition.isSingleton();
    if (earlySingletonExposure) {
        registerSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, bean));
    }

    // B-4: 프로퍼티 주입
    populateBean(beanName, definition, bean);

    // B-5: 초기화
    Object exposed = initializeBean(beanName, definition, bean);

    // B-7: destroy 등록
    registerDisposableIfNeeded(beanName, definition, exposed);

    return exposed;
}

/**
 * factoryBeanName + factoryMethodName 모드 인스턴스화.
 * <p>팩토리 빈을 먼저 getBean으로 얻은 뒤, 지정된 메서드를 reflection으로 호출한다.
 * byte-buddy enhance 적용 후에는 inter-bean reference 시 getBean으로 라우팅되어
 * 컨테이너 싱글톤이 보장된다.
 */
private Object instantiateViaFactoryMethod(String beanName, BeanDefinition definition) {
    String factoryBeanName = definition.getFactoryBeanName();
    String factoryMethodName = definition.getFactoryMethodName();
    try {
        Object factoryBean = getBean(factoryBeanName);
        // enhance된 클래스의 슈퍼클래스에서 메서드를 찾아야 올바른 반환 타입을 얻음
        java.lang.reflect.Method method = null;
        Class<?> searchType = factoryBean.getClass();
        while (searchType != null && method == null) {
            for (java.lang.reflect.Method m : searchType.getDeclaredMethods()) {
                if (m.getName().equals(factoryMethodName) && m.getParameterCount() == 0) {
                    method = m;
                    break;
                }
            }
            searchType = searchType.getSuperclass();
        }
        if (method == null) {
            throw new com.choisk.sfs.core.BeanCreationException(beanName,
                    "Factory method '%s' not found on '%s'".formatted(factoryMethodName, factoryBeanName));
        }
        method.setAccessible(true);
        return method.invoke(factoryBean);
    } catch (java.lang.reflect.InvocationTargetException e) {
        throw new com.choisk.sfs.core.BeanCreationException(beanName,
                "Factory method '%s' threw exception".formatted(factoryMethodName), e.getCause());
    } catch (ReflectiveOperationException e) {
        throw new com.choisk.sfs.core.BeanCreationException(beanName,
                "Factory method invocation failed", e);
    }
}
```

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.CreateBeanFactoryMethodTest"
```

예상: PASS (2건).

회귀 확인:

```bash
./gradlew :sfs-beans:test
```

예상: 기존 테스트 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/CreateBeanFactoryMethodTest.java
git commit -m "feat(sfs-beans): AbstractAutowireCapableBeanFactory에 factoryMethod 인스턴스화 분기 추가"
```

---

## 섹션 B: `sfs-beans` 의존 해석 보강 (Task B1~B4)

### Task B1: `DependencyDescriptor` 신설

> **TDD 적용 여부:** 적용 — `required` 플래그 + 제네릭 추출 분기가 본질.

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/DependencyDescriptorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DependencyDescriptor 생성자 + getter 검증.
 * required 플래그와 제네릭 타입 추출이 resolveDependency 분기의 핵심 입력이므로
 * 모든 getter가 생성자에서 받은 값을 그대로 반환해야 한다.
 */
class DependencyDescriptorTest {

    interface MyService {}
    static class MyServiceImpl implements MyService {}

    @Test
    void requiredTrueByDefault_returnsCorrectType() {
        DependencyDescriptor desc = new DependencyDescriptor(
                MyService.class, null, true, "myService");
        assertThat(desc.getDependencyType()).isEqualTo(MyService.class);
        assertThat(desc.isRequired()).isTrue();
        assertThat(desc.getDependencyName()).isEqualTo("myService");
    }

    @Test
    void requiredFalse_flagIsPreserved() {
        DependencyDescriptor desc = new DependencyDescriptor(
                MyService.class, null, false, "optionalService");
        assertThat(desc.isRequired()).isFalse();
    }

    @Test
    void genericType_isStoredAndReturned() throws Exception {
        // List<MyService> 필드의 제네릭 타입을 직접 추출해서 전달하는 시나리오
        java.lang.reflect.Type listType = SampleHolder.class
                .getDeclaredField("services")
                .getGenericType();

        DependencyDescriptor desc = new DependencyDescriptor(
                List.class, listType, true, "services");
        assertThat(desc.getDependencyType()).isEqualTo(List.class);
        assertThat(desc.getGenericType()).isEqualTo(listType);
    }

    @Test
    void nullGenericType_isAllowed() {
        // 단순 타입(List/Map 아님)은 genericType null 허용
        DependencyDescriptor desc = new DependencyDescriptor(
                MyService.class, null, true, "svc");
        assertThat(desc.getGenericType()).isNull();
    }

    @Test
    void dependencyName_isPreserved() {
        DependencyDescriptor desc = new DependencyDescriptor(
                MyService.class, null, true, "primarySvc");
        assertThat(desc.getDependencyName()).isEqualTo("primarySvc");
    }

    // List<MyService> 필드를 선언하기 위한 홀더 (제네릭 타입 추출 용도)
    static class SampleHolder {
        List<MyService> services;
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.DependencyDescriptorTest"
```

예상: FAIL — `DependencyDescriptor` 클래스가 존재하지 않으므로 컴파일 에러.

- [ ] **Step 3: 구현 — `DependencyDescriptor.java` 신설**

`sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java` 파일 신규 생성:

```java
package com.choisk.sfs.beans;

import java.lang.reflect.Type;

/**
 * 의존성 주입 요청을 기술하는 디스크립터.
 * <p>Spring 원본: {@code org.springframework.beans.factory.config.DependencyDescriptor}.
 * AutowiredAnnotationBeanPostProcessor가 resolveDependency 호출 시 사용.
 *
 * <p>생성자 인자:
 * <ul>
 *   <li>{@code type} — 주입 대상의 선언 타입 (예: List.class, MyService.class)</li>
 *   <li>{@code genericType} — 제네릭 타입 정보 (List&lt;MyService&gt; 등). null이면 단순 타입</li>
 *   <li>{@code required} — true이면 매칭 빈 없을 때 예외. false이면 null 반환</li>
 *   <li>{@code name} — 필드/파라미터 이름. 폴백 매칭 및 Map&lt;String,T&gt; 키에 사용</li>
 * </ul>
 */
public class DependencyDescriptor {

    private final Class<?> type;
    private final Type genericType;
    private final boolean required;
    private final String name;

    public DependencyDescriptor(Class<?> type, Type genericType, boolean required, String name) {
        this.type = type;
        this.genericType = genericType;
        this.required = required;
        this.name = name;
    }

    /** 주입 대상의 선언 타입. */
    public Class<?> getDependencyType() { return type; }

    /** 제네릭 타입 정보. List&lt;T&gt;/Map&lt;String,T&gt; 원소 타입 추출에 사용. null이면 단순 타입. */
    public Type getGenericType() { return genericType; }

    /** required=false이면 매칭 빈 없을 때 null 반환. true이면 NoSuchBeanDefinitionException 발생. */
    public boolean isRequired() { return required; }

    /** 필드/파라미터 이름. @Qualifier 미명시 시 이름 기반 폴백 매칭에 사용. */
    public String getDependencyName() { return name; }
}
```

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.DependencyDescriptorTest"
```

예상: PASS (5건).

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/DependencyDescriptorTest.java
git commit -m "feat(sfs-beans): DependencyDescriptor 신설 — required 플래그 + 제네릭 타입 디스크립터"
```

---

### Task B2: `DefaultListableBeanFactory.resolveDependency` 신설

> **TDD 적용 여부:** 적용 — List/Map/단일/다수 후보/required=false 분기. 의존 해석 코어.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/ResolveDependencyTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultListableBeanFactory.resolveDependency 분기 검증.
 * List/Map/단일/다수/required=false 5개 경로가 모두 올바르게 동작해야 한다.
 */
class ResolveDependencyTest {

    interface Handler {}

    static class HandlerA implements Handler {}
    static class HandlerB implements Handler {}

    static class Standalone {}
    static class Consumer {}

    // List<Handler> 필드의 제네릭 타입을 추출하기 위한 홀더
    static class ListHolder {
        List<Handler> handlers;
    }

    // Map<String, Handler> 필드의 제네릭 타입을 추출하기 위한 홀더
    static class MapHolder {
        Map<String, Handler> handlers;
    }

    @Test
    void singleCandidate_returnsTheBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("standalone", new BeanDefinition(Standalone.class));

        DependencyDescriptor desc = new DependencyDescriptor(
                Standalone.class, null, true, "standalone");
        Object result = factory.resolveDependency(desc, "consumer");
        assertThat(result).isInstanceOf(Standalone.class);
    }

    @Test
    void noCandidate_required_throwsException() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        DependencyDescriptor desc = new DependencyDescriptor(
                Standalone.class, null, true, "standalone");
        assertThatThrownBy(() -> factory.resolveDependency(desc, "consumer"))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void noCandidate_requiredFalse_returnsNull() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        DependencyDescriptor desc = new DependencyDescriptor(
                Standalone.class, null, false, "standalone");
        Object result = factory.resolveDependency(desc, "consumer");
        assertThat(result).isNull();
    }

    @Test
    void listType_collectsAllMatchingBeans() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("handlerA", new BeanDefinition(HandlerA.class));
        factory.registerBeanDefinition("handlerB", new BeanDefinition(HandlerB.class));

        java.lang.reflect.Type genericType = ListHolder.class
                .getDeclaredField("handlers")
                .getGenericType();
        DependencyDescriptor desc = new DependencyDescriptor(
                List.class, genericType, true, "handlers");

        @SuppressWarnings("unchecked")
        List<Handler> result = (List<Handler>) factory.resolveDependency(desc, "consumer");
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(h -> h instanceof Handler);
    }

    @Test
    void mapType_collectsAllMatchingBeansWithNames() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("handlerA", new BeanDefinition(HandlerA.class));
        factory.registerBeanDefinition("handlerB", new BeanDefinition(HandlerB.class));

        java.lang.reflect.Type genericType = MapHolder.class
                .getDeclaredField("handlers")
                .getGenericType();
        DependencyDescriptor desc = new DependencyDescriptor(
                Map.class, genericType, true, "handlers");

        @SuppressWarnings("unchecked")
        Map<String, Handler> result = (Map<String, Handler>) factory.resolveDependency(desc, "consumer");
        assertThat(result).containsKeys("handlerA", "handlerB");
        assertThat(result.get("handlerA")).isInstanceOf(HandlerA.class);
        assertThat(result.get("handlerB")).isInstanceOf(HandlerB.class);
    }

    @Test
    void multipleCandidates_primaryWins() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("handlerA", new BeanDefinition(HandlerA.class));
        factory.registerBeanDefinition("handlerB",
                new BeanDefinition(HandlerB.class).setPrimary(true));

        DependencyDescriptor desc = new DependencyDescriptor(
                Handler.class, null, true, "handler");
        Object result = factory.resolveDependency(desc, "consumer");
        assertThat(result).isInstanceOf(HandlerB.class);
    }

    @Test
    void multipleCandidates_nameMatchFallback() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("handlerA", new BeanDefinition(HandlerA.class));
        factory.registerBeanDefinition("handlerB", new BeanDefinition(HandlerB.class));

        // dependencyName이 "handlerA"이면 이름 매칭으로 handlerA를 선택
        DependencyDescriptor desc = new DependencyDescriptor(
                Handler.class, null, true, "handlerA");
        Object result = factory.resolveDependency(desc, "consumer");
        assertThat(result).isInstanceOf(HandlerA.class);
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.ResolveDependencyTest"
```

예상: FAIL — `DefaultListableBeanFactory`에 `resolveDependency(DependencyDescriptor, String)` 메서드가 없으므로 컴파일 에러.

- [ ] **Step 3: 구현 — `DefaultListableBeanFactory`에 `resolveDependency` 메서드 추가**

`sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java`에 다음을 추가한다.

import 추가:

```java
import com.choisk.sfs.beans.DependencyDescriptor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
```

메서드 추가 (기존 `resolveBeanNameByType` 메서드 바로 아래):

```java
/**
 * DependencyDescriptor가 기술하는 의존성을 해석해 인스턴스를 반환한다.
 * <p>해석 순서:
 * <ol>
 *   <li>List&lt;T&gt; — 매칭 빈 전체를 List로 수집</li>
 *   <li>Map&lt;String, T&gt; — 매칭 빈 전체를 이름→인스턴스 Map으로 수집</li>
 *   <li>단일 후보 — 그대로 반환</li>
 *   <li>후보 없음 + required=false — null 반환</li>
 *   <li>후보 없음 + required=true — NoSuchBeanDefinitionException</li>
 *   <li>다수 후보 — determineAutowireCandidate(@Primary → 이름 매칭 폴백)</li>
 * </ol>
 *
 * @param desc               주입 대상 디스크립터
 * @param requestingBeanName 주입을 요청하는 빈 이름 (순환 참조 감지 등에 활용 가능)
 * @return 해석된 빈 인스턴스. required=false이고 매칭 빈이 없으면 null.
 */
public Object resolveDependency(DependencyDescriptor desc, String requestingBeanName) {
    Class<?> type = desc.getDependencyType();

    // List<T> 분기
    if (List.class.isAssignableFrom(type)) {
        Class<?> elementType = extractFirstGenericArg(desc.getGenericType());
        if (elementType != null) {
            return new java.util.ArrayList<>(getBeansOfType(elementType).values());
        }
    }

    // Map<String, T> 분기
    if (Map.class.isAssignableFrom(type)) {
        Class<?> elementType = extractSecondGenericArg(desc.getGenericType());
        if (elementType != null) {
            return getBeansOfType(elementType);
        }
    }

    // 단일 타입 해석
    Map<String, Object> matches = getBeansOfType(type);
    if (matches.isEmpty()) {
        if (desc.isRequired()) {
            throw new com.choisk.sfs.core.NoSuchBeanDefinitionException(
                    "No bean of type " + type.getName() + " found for dependency '" + desc.getDependencyName() + "'");
        }
        return null;
    }
    if (matches.size() == 1) {
        return matches.values().iterator().next();
    }

    // 다수 후보 — @Primary → 이름 매칭 폴백
    return determineAutowireCandidate(matches, desc);
}

/**
 * 다수 후보 중 주입 대상을 결정한다.
 * <p>우선순위: @Primary → dependencyName 이름 매칭 → NoUniqueBeanDefinitionException.
 */
private Object determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor desc) {
    // 1) @Primary 후보 찾기
    var primaries = new java.util.ArrayList<String>();
    for (var entry : candidates.entrySet()) {
        BeanDefinition bd = getBeanDefinition(entry.getKey());
        if (bd != null && bd.isPrimary()) {
            primaries.add(entry.getKey());
        }
    }
    if (primaries.size() == 1) {
        return candidates.get(primaries.get(0));
    }

    // 2) 이름 매칭 폴백 — dependencyName이 후보 이름과 일치하는 경우
    String depName = desc.getDependencyName();
    if (depName != null && candidates.containsKey(depName)) {
        return candidates.get(depName);
    }

    // 3) 해결 불가 — 예외
    throw new com.choisk.sfs.core.NoUniqueBeanDefinitionException(
            "Multiple beans of type " + desc.getDependencyType().getName()
                    + " found and no unique candidate: " + candidates.keySet(),
            new java.util.ArrayList<>(candidates.keySet()));
}

/** ParameterizedType의 첫 번째 타입 인자를 Class로 추출. List<T>에서 T 추출 용도. */
private Class<?> extractFirstGenericArg(Type genericType) {
    if (genericType instanceof ParameterizedType pt) {
        Type[] args = pt.getActualTypeArguments();
        if (args.length >= 1 && args[0] instanceof Class<?> cls) {
            return cls;
        }
    }
    return null;
}

/** ParameterizedType의 두 번째 타입 인자를 Class로 추출. Map<String,T>에서 T 추출 용도. */
private Class<?> extractSecondGenericArg(Type genericType) {
    if (genericType instanceof ParameterizedType pt) {
        Type[] args = pt.getActualTypeArguments();
        if (args.length >= 2 && args[1] instanceof Class<?> cls) {
            return cls;
        }
    }
    return null;
}
```

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.ResolveDependencyTest"
```

예상: PASS (7건).

회귀 확인:

```bash
./gradlew :sfs-beans:test
```

예상: 기존 테스트 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/ResolveDependencyTest.java
git commit -m "feat(sfs-beans): DefaultListableBeanFactory에 resolveDependency 신설 — List/Map/required=false/다수 후보 분기"
```

---

### Task B3: `@Lazy` skip 회귀 검증 (1B-α 보강 재확인)

> **TDD 적용 여부:** 적용 — 1B-α에서 추가된 분기가 1B-β 변경 후에도 PASS 유지 확인.

**Files:**
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/LazyInitSkipRegressionTest.java` (또는 1B-α의 `LazyInitializationTest` 그대로 회귀 PASS 확인)

> **이 task의 성격:** 새 테스트를 추가하거나 구현을 변경하지 않는다. 1B-α에서 구현한 `@Lazy` skip 분기가 1B-β의 sfs-beans 변경(Task A1, A2, B1, B2)이 적용된 후에도 그대로 동작하는지 확인하는 회귀 검증이다.

- [ ] **Step 1: 1B-α의 LazyInit 통합 테스트 회귀 실행**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.LazyInitializationTest"
```

예상: PASS (1건). `@Lazy` 클래스는 `preInstantiateSingletons()`에서 skip되고, 첫 `getBean()` 시 생성됨.

- [ ] **Step 2: sfs-beans 전체 회귀 실행 (1B-β 변경 영향 확인)**

```bash
./gradlew :sfs-beans:test
```

예상: 모든 테스트 PASS. Task A1/A2/B1/B2의 변경이 기존 lazy init 로직(`DefaultListableBeanFactory.preInstantiateSingletons`의 `!def.isLazyInit()` 조건)에 영향을 주지 않아야 한다.

- [ ] **Step 3: 회귀 결과 기록 (커밋 없음 — 코드 변경 없음)**

회귀 PASS 확인 후 본 plan 문서에 아래 메모를 추가한다:

> **회귀 PASS 확인 (실행 시점):** sfs-context `LazyInitializationTest` 1건 PASS. 1B-β sfs-beans 변경(factoryMethod 분기, DependencyDescriptor, resolveDependency)이 `preInstantiateSingletons`의 `isLazyInit()` 조건에 영향 없음 확인.

---

### Task B4: `registerSingleton`의 DisposableBean 감지를 `singletonLock` 안으로 atomic화 *(simplify 이월 B4)*

> **TDD 적용 여부:** 적용 — 동시성 안전성. 현재는 `singletonObjects.put`과 `disposableBeans.put`이 분리된 critical section.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java:34-44`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/RegisterSingletonAtomicTest.java`

> **사전 분석 (2026-04-25):** `DefaultSingletonBeanRegistry.registerSingleton`을 확인한 결과, `singletonObjects.put` 등 3-cache 조작은 `synchronized(singletonLock)` 안에 있으나, 이후 `DisposableBean` 감지 + `registerDisposableBean` 호출은 `singletonLock` **밖**에 있다 (라인 34-44). 두 작업 사이에 다른 스레드가 `destroySingletons`를 호출하면 DisposableBean 등록이 누락될 수 있는 구조적 허점. 이 task에서 두 작업을 같은 `synchronized` 블록으로 묶는다.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * registerSingleton의 DisposableBean 감지가 singletonLock 안에서 원자적으로 실행됨을 검증.
 * singletonObjects.put과 disposableBeans.put이 같은 임계 구역에 있어야
 * 동시 destroySingletons 호출 시 destroy 콜백이 누락되지 않는다.
 */
class RegisterSingletonAtomicTest {

    static class TrackingDisposable implements DisposableBean {
        final AtomicBoolean destroyed = new AtomicBoolean(false);

        @Override
        public void destroy() {
            destroyed.set(true);
        }
    }

    @Test
    void disposableBeanRegisteredWithinLock_destroyCallbackNotLost() throws InterruptedException {
        DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
        TrackingDisposable bean = new TrackingDisposable();

        registry.registerSingleton("tracked", bean);

        // destroySingletons 직후에도 destroy 콜백이 실행되어야 함
        registry.destroySingletons();

        assertThat(bean.destroyed.get())
                .as("DisposableBean.destroy()가 호출되어야 함 — registerSingleton과 registerDisposableBean이 원자적이어야 보장")
                .isTrue();
    }

    @Test
    void concurrentRegisterAndDestroy_destroyCallbackNotMissed() throws InterruptedException {
        // 여러 스레드가 registerSingleton과 destroySingletons를 동시 호출해도
        // DisposableBean 콜백이 누락되지 않음을 확률적으로 검증
        int iterations = 200;
        AtomicInteger missedDestroys = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
            TrackingDisposable bean = new TrackingDisposable();
            CountDownLatch startLatch = new CountDownLatch(1);

            Thread registrar = new Thread(() -> {
                try {
                    startLatch.await();
                    registry.registerSingleton("bean" + Thread.currentThread().threadId(), bean);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Thread destroyer = new Thread(() -> {
                try {
                    startLatch.await();
                    registry.destroySingletons();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            registrar.start();
            destroyer.start();
            startLatch.countDown();
            registrar.join(500);
            destroyer.join(500);

            // registerSingleton이 먼저 완료된 경우에 destroy가 호출되었는지 확인
            // (destroyer가 registerSingleton 전에 실행된 경우는 destroy 미호출이 정상)
            if (registry.getSingletonNames().length == 0 && !bean.destroyed.get()) {
                // destroySingletons가 registerSingleton 이후에 실행됐는데 destroy가 안 된 경우만 카운트
                // 이 검증은 결정적이지 않으므로 참고용
            }
        }

        // 결정적 테스트: 순서가 보장된 단일 스레드 시나리오에서 반드시 destroy가 실행
        DefaultSingletonBeanRegistry registry2 = new DefaultSingletonBeanRegistry();
        TrackingDisposable bean2 = new TrackingDisposable();
        registry2.registerSingleton("singleBean", bean2);
        registry2.destroySingletons();
        assertThat(bean2.destroyed.get()).isTrue();
    }

    @Test
    void nonDisposableBean_doesNotRegisterCallback() {
        DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
        // DisposableBean을 구현하지 않는 일반 객체
        Object plain = new Object();
        registry.registerSingleton("plain", plain);

        // destroySingletons를 호출해도 예외 없이 통과해야 함
        registry.destroySingletons();

        // singletonObjects가 비워진 상태 확인
        assertThat(registry.containsSingleton("plain")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL/PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.RegisterSingletonAtomicTest"
```

예상: 단일 스레드 테스트(`disposableBeanRegisteredWithinLock_destroyCallbackNotLost`, `nonDisposableBean_doesNotRegisterCallback`)는 현재 코드에서도 PASS할 수 있음. 그러나 `concurrentRegisterAndDestroy_destroyCallbackNotMissed`의 결정적 부분과 구조적 atomic 보장 관점에서 변경이 필요하다.

- [ ] **Step 3: 구현 — `DefaultSingletonBeanRegistry.registerSingleton`의 DisposableBean 감지를 `singletonLock` 안으로 이동**

`sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`의 `registerSingleton` 메서드를 다음으로 교체한다.

변경 전 (`singletonLock` 블록이 끝난 **뒤**에 DisposableBean 감지):
```java
public void registerSingleton(String name, Object bean) {
    Assert.hasText(name, "name");
    Assert.notNull(bean, "bean");
    synchronized (singletonLock) {
        Object existing = singletonObjects.get(name);
        if (existing != null) {
            throw new IllegalStateException(
                    "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
        }
        singletonObjects.put(name, bean);
        earlySingletonObjects.remove(name);
        singletonFactories.remove(name);
    }
    // DisposableBean을 직접 등록한 경우에도 destroy 콜백이 실행되도록 자동 등록
    if (bean instanceof DisposableBean disposable) {
        registerDisposableBean(name, () -> {
            try {
                disposable.destroy();
            } catch (Exception e) {
                throw new RuntimeException("DisposableBean.destroy failed for '" + name + "'", e);
            }
        });
    }
}
```

변경 후 (`singletonLock` **안**에서 DisposableBean 감지와 콜백 등록을 원자적으로):
```java
public void registerSingleton(String name, Object bean) {
    Assert.hasText(name, "name");
    Assert.notNull(bean, "bean");
    synchronized (singletonLock) {
        Object existing = singletonObjects.get(name);
        if (existing != null) {
            throw new IllegalStateException(
                    "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
        }
        singletonObjects.put(name, bean);
        earlySingletonObjects.remove(name);
        singletonFactories.remove(name);
        // DisposableBean 감지 + 콜백 등록을 같은 임계 구역에서 수행 (simplify 이월 B4).
        // singletonObjects.put과 disposableBeans.put 사이에 destroySingletons가 끼어드는
        // 구조적 허점을 제거한다.
        if (bean instanceof DisposableBean disposable) {
            disposableBeans.put(name, () -> {
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    throw new RuntimeException("DisposableBean.destroy failed for '" + name + "'", e);
                }
            });
        }
    }
}
```

> **주의:** `registerDisposableBean`은 내부적으로 `synchronized(singletonLock)`을 다시 획득하므로, 같은 스레드에서 중첩 호출하면 교착 상태가 발생한다. 따라서 `disposableBeans.put`을 직접 호출해야 한다. `disposableBeans` 필드가 `private`이므로 접근 가능성을 확인하고 필요 시 `protected`로 변경하거나 동일 클래스 내에서 직접 접근.

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.RegisterSingletonAtomicTest"
```

예상: PASS (3건).

회귀 확인:

```bash
./gradlew :sfs-beans:test
```

예상: 기존 테스트 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/RegisterSingletonAtomicTest.java
git commit -m "refactor(sfs-beans): registerSingleton의 DisposableBean 감지를 singletonLock 안으로 atomic화 (simplify 이월 B4)"
```

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
