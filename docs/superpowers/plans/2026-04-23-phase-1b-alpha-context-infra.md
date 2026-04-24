# Phase 1B-α — sfs-context 컨테이너 인프라 + 라이프사이클 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sfs-context` 모듈을 신설하고, `ApplicationContext` 계층 + `refresh()` 8단계 라이프사이클 + 애노테이션 10종 + 클래스패스 스캐너를 TDD로 구현한다. Plan 1B-β 시작 시점에 `@Configuration`/`@Bean`/`@Autowired` 처리기를 끼워넣을 수 있는 인프라를 완성한다.

**Architecture:** 새 모듈 `sfs-context`(의존: `sfs-beans`)에 5개 컨텍스트 클래스 + 10개 애노테이션 + 메타-인식 스캐너를 추가. `refresh()`는 Spring과 동일한 8단계 템플릿 메서드. BPP/BFPP 컬렉션은 1B-α에서 비어있어 5/6단계는 사실상 no-op이지만 호출 코드는 들어 있어 1B-β에서 그대로 작동. byte-buddy 의존은 카탈로그 등록만 하고 사용은 1B-β에서.

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL), JUnit 5, AssertJ. `sfs-core`에 `AnnotationUtils` 추가 (메타-애노테이션 재귀 인식). 신규 외부 의존: byte-buddy 1.14.x (1B-β 대비 카탈로그 등록만).

**Selected Spec:** `docs/superpowers/specs/2026-04-23-phase-1b-context-design.md`

**선행 조건:** Plan 1A 완료 (main 브랜치 `8c8664f` 머지 시점 + sfs-core/sfs-beans 64 테스트 PASS)

**End state:** 아래 통합 테스트가 통과하는 작동 가능 컨텍스트. `@Autowired` 처리는 1B-β에서 추가.

```java
// Plan 1B-α 종료 시점에 이런 테스트가 통과해야 함
var ctx = new AnnotationConfigApplicationContext("com.example.demo");
assertThat(ctx.containsBean("simpleService")).isTrue();   // @Component
assertThat(ctx.containsBean("metaTaggedService")).isTrue(); // @Service via @Component meta
ctx.close();   // idempotent + shutdown hook 정리
```

**Plan 1A와 다른 운영 차이:**
- 신규 외부 의존 도입 (byte-buddy 카탈로그 등록)
- spec amendment 동반 (`docs/superpowers/specs/2026-04-19-ioc-container-design.md` line 47)
- 추상 골격 클래스(`AbstractApplicationContext`)는 try-catch cleanup이 본질이라 단독 TDD 적용 (1A의 `AbstractBeanFactory` 제외와 다른 결정)
- 사전 분석(2026-04-24)에서 발견된 sfs-beans 보강 2건을 **섹션 A2**로 선결 처리 (`BeanDefinitionRegistry` 인터페이스 신설 + `registerSingleton`의 `DisposableBean` 자동 콜백 등록). 이후 Task 11/12/15/16/18이 이 보강에 의존하므로 섹션 A 다음에 즉시 실행.

---

## 섹션 A: spec amendment + sfs-context 모듈 스캐폴딩 (Task 1~3)

### Task 1: spec line 47 amendment + byte-buddy 카탈로그 등록

> **TDD 적용 여부:** 제외 — 설정 파일 + 문서 변경.

**Files:**
- Modify: `docs/superpowers/specs/2026-04-19-ioc-container-design.md` (line 47 부근)
- Modify: `gradle/libs.versions.toml`

- [x] **Step 1: spec line 47 amendment**

`docs/superpowers/specs/2026-04-19-ioc-container-design.md`의 "ASM 외 외부 의존이 없다" 문장을 다음으로 교체:

```
외부 런타임 의존: ASM(클래스패스 스캔), byte-buddy(`@Configuration` 클래스 enhance).
그 외 의존 추가는 spec 개정을 요구한다.
```

> 정확한 라인은 spec 작성 시점과 다를 수 있으므로 grep으로 "ASM 외 외부 의존" 위치를 먼저 확인 후 수정.

- [x] **Step 2: `gradle/libs.versions.toml`에 byte-buddy 추가**

```toml
[versions]
junit = "5.11.3"
assertj = "3.26.3"
asm = "9.9.1"
bytebuddy = "1.14.19"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
asm-commons = { module = "org.ow2.asm:asm-commons", version.ref = "asm" }
bytebuddy = { module = "net.bytebuddy:byte-buddy", version.ref = "bytebuddy" }
```

- [x] **Step 3: 빌드 확인 (회귀)**

```bash
./gradlew build -x test
```
예상: BUILD SUCCESSFUL (catalog 추가만으로 컴파일 깨지면 안 됨).

- [x] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-04-19-ioc-container-design.md gradle/libs.versions.toml
git commit -m "docs+chore: spec 의존성 정책 amendment + byte-buddy 카탈로그 등록"
```

> **실행 기록 (2026-04-24):**
> - Plan이 grep 패턴으로 "ASM 외 외부 의존이 없다"를 지정했으나, 실제 spec 파일(line 52)의 표현은 "외부 의존 제로"였음 (테이블 셀 근거 컬럼). 동일 라인을 grep -n "외부 의존 제로"로 확인하여 정상 수정.
> - `gradle/libs.versions.toml`의 bytebuddy 키는 기존에 없었으므로 누락 키만 추가.
> - `./gradlew build -x test` → BUILD SUCCESSFUL (599ms).

---

### Task 2: `sfs-context` 모듈 스캐폴딩

> **TDD 적용 여부:** 제외 — 설정/스모크 테스트.

**Files:**
- Create: `sfs-context/build.gradle.kts`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/package-info.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/PackageSmokeTest.java`

- [x] **Step 1: `sfs-context/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-beans"))
    implementation(libs.bytebuddy)  // 1B-α는 미사용, 1B-β의 ConfigurationClassEnhancer가 사용
}
```

- [x] **Step 2: `package-info.java`**

```java
/**
 * 애노테이션 기반 ApplicationContext 계층.
 *
 * <p>Spring 원본 매핑: {@code spring-context}. {@code @Component}/{@code @Configuration}/
 * {@code @Bean}/{@code @Autowired} 등 메타데이터 처리와 라이프사이클(refresh/close) 책임.
 *
 * <p>이 모듈은 {@code sfs-beans} 위에서 작동한다 (의존 그래프: context → beans → core).
 */
package com.choisk.sfs.context;
```

- [x] **Step 3: 스모크 테스트**

```java
package com.choisk.sfs.context;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PackageSmokeTest {
    @Test
    void packageIsLoadable() {
        assertThat(getClass().getPackageName()).isEqualTo("com.choisk.sfs.context");
    }
}
```

- [x] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-context:test
```
예상: BUILD SUCCESSFUL, 1 test passed.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "chore(sfs-context): 모듈 스캐폴딩 (sfs-beans 의존, byte-buddy 카탈로그 의존)"
```

> **실행 기록 (2026-04-24):**
> - 루트 `build.gradle.kts` 확인 결과 `subprojects` 블록에서 `java-library`, JUnit 5, AssertJ, Java 25 toolchain을 공통 적용 중.
>   따라서 `sfs-context/build.gradle.kts`는 `plugins { \`java-library\` }` + 의존성 2개만으로 충분 (중복 설정 생략).
> - `sfs-beans/build.gradle.kts` 컨벤션 그대로 적용.
> - `:sfs-context:test` BUILD SUCCESSFUL — `PackageSmokeTest#packageIsLoadable` 1 PASS.
> - `:sfs-core:test :sfs-beans:test` UP-TO-DATE — 기존 1A 테스트 회귀 없음.

---

### Task 3: `sfs-context/README.md` 초안 (1B-β까지 진화)

> **TDD 적용 여부:** 제외 — 문서.

**Files:**
- Create: `sfs-context/README.md`

- [x] **Step 1: README 초안 작성**

````markdown
# sfs-context

Spring From Scratch의 **ApplicationContext 계층**. 애노테이션 메타데이터 처리와
라이프사이클(`refresh()`/`close()`) 책임을 담당한다.

## 의존 관계
- 의존: `sfs-beans` (BeanDefinition, BeanFactory)
- 외부: ASM(상속), byte-buddy(1B-β의 `@Configuration` 클래스 enhance에서 사용)
- 의존됨: `sfs-samples` (Plan 1C에서 추가)

## Spring 원본 매핑

| 이 모듈 | Spring 원본 |
|---|---|
| `ApplicationContext` / `ConfigurableApplicationContext` | 동명 인터페이스 |
| `AbstractApplicationContext` | 동명 (refresh 8단계 템플릿) |
| `GenericApplicationContext` | 동명 |
| `AnnotationConfigApplicationContext` | 동명 |
| `ClassPathBeanDefinitionScanner` | 동명 (sfs-core의 ClassPathScanner 위에 메타-인식 추가) |
| `AnnotationBeanNameGenerator` | 동명 (FQN→camelCase) |
| `@Component` / `@Service` / `@Repository` / `@Controller` / `@Configuration` / `@Bean` / `@Scope` / `@Lazy` / `@Primary` / `@Qualifier` | 동명 |

## 1B-α 시점 동작

```java
var ctx = new AnnotationConfigApplicationContext("com.example.demo");
ctx.getBean("myService");   // OK
ctx.getBean(MyService.class); // OK
ctx.close();                  // OK (idempotent)
```

단, `@Autowired` 자동 주입은 **1B-β에서 추가** (이 시점에는 필드 null).

## 테스트 실행
```bash
./gradlew :sfs-context:test
```
````

- [x] **Step 2: 커밋**

```bash
git add sfs-context/README.md
git commit -m "docs(sfs-context): README 초안 (1B-α 시점 동작 시나리오 + Spring 매핑표)"
```

---

## 섹션 A2: `sfs-beans` 선결 보강 (Task A2-1, A2-2)

> **배경:** 사전 분석(2026-04-24) 결과 본 plan의 `AnnotatedBeanDefinitionReader`/`ClassPathBeanDefinitionScanner` 생성자와 `RefreshFailureCleanupTest`/`CloseAndShutdownHookTest`가 요구하는 sfs-beans API 2건이 1A 완료 시점(커밋 `8c8664f`)에 부재함이 확인됨. 섹션 B 이후 Task가 이를 가정하므로 섹션 A2에서 선결 보강한다. 부록의 "누락 후보 표"와 중복 해결.
>
> **실사 기준:** `sfs-beans/src/main/java/com/choisk/sfs/beans/` 디렉터리 현 상태.
> - `BeanDefinitionRegistry` 인터페이스: **미존재** (`registerBeanDefinition`은 `ConfigurableListableBeanFactory`에만 있음) → Task A2-1에서 신설
> - `DefaultSingletonBeanRegistry.registerSingleton(name, bean)`: `DisposableBean` 인터페이스를 확인하지 않음 → Task A2-2에서 보강

### Task A2-1: `BeanDefinitionRegistry` 인터페이스 신설 + CLBF 상속

> **TDD 적용 여부:** 제외 — 인터페이스 시그니처 분리 + 기존 구현체의 메서드 재노출. 1A 64개 회귀 테스트로 간접 검증.

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanDefinitionRegistry.java`
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java`

- [x] **Step 1: `BeanDefinitionRegistry` 신설**

```java
package com.choisk.sfs.beans;

/**
 * BeanDefinition을 등록/조회할 수 있는 저장소의 추상화. reader/scanner/BFPP가 이 타입을 통해
 * BeanFactory를 건드리고, BeanFactory 구현은 이 인터페이스를 구현해 BeanDefinition 저장소 역할도 겸한다.
 *
 * <p>Spring 원본: {@code org.springframework.beans.factory.support.BeanDefinitionRegistry}.
 */
public interface BeanDefinitionRegistry {

    void registerBeanDefinition(String name, BeanDefinition definition);

    BeanDefinition getBeanDefinition(String name);

    boolean containsBeanDefinition(String name);

    String[] getBeanDefinitionNames();

    int getBeanDefinitionCount();
}
```

> **주의:** `containsBeanDefinition` / `getBeanDefinitionNames` / `getBeanDefinitionCount` 중 1A에 미노출인 것이 있으면 `DefaultListableBeanFactory`에 public 위임 메서드를 추가한다(이미 `ListableBeanFactory` 계열로 노출돼 있을 가능성 큼 — `grep -n "getBeanDefinitionNames\|containsBeanDefinition\|getBeanDefinitionCount" sfs-beans/src/main/java/com/choisk/sfs/beans`로 사전 확인 후 결정). 누락 시 본 Task 내에서 함께 추가(별도 커밋 불필요, 같은 사유).

- [x] **Step 2: `ConfigurableListableBeanFactory`가 `BeanDefinitionRegistry`를 상속하도록 수정**

```java
package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory, BeanDefinitionRegistry {

    void preInstantiateSingletons();
}
```

`registerBeanDefinition` / `getBeanDefinition` 선언은 `BeanDefinitionRegistry`로 이동했으므로 CLBF에서 중복 선언을 **제거**한다.

- [x] **Step 3: 컴파일 + 1A 회귀 테스트**

```bash
./gradlew :sfs-beans:compileJava :sfs-core:test :sfs-beans:test
```
예상: 1A의 64개 테스트가 모두 그대로 PASS. `DefaultListableBeanFactory`가 이미 해당 메서드들을 구현하고 있으므로 컴파일/동작 영향 없음.

- [x] **Step 4: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/BeanDefinitionRegistry.java \
        sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java
git commit -m "feat(sfs-beans): BeanDefinitionRegistry 인터페이스 신설 + CLBF 상속으로 reader/scanner 의존점 확보"
```

> **실행 기록 (2026-04-24):**
> - Step 1 API 노출 현황 조사 결과: `containsBeanDefinition` / `getBeanDefinitionNames` / `getBeanDefinitionCount` 3개는 `ListableBeanFactory`에 이미 선언됨. `registerBeanDefinition` / `getBeanDefinition` 2개는 기존 `ConfigurableListableBeanFactory`에 선언됨. `DefaultListableBeanFactory`는 5개 모두 public으로 구현 완료.
> - `DefaultListableBeanFactory` 추가 보강 불필요 — Plan의 "ListableBeanFactory 계열로 노출돼 있을 가능성 큼" 예측과 일치.
> - 테스트 결과: sfs-beans 44건 + sfs-core 21건 = 전체 65건 모두 PASS (0 failures, 0 errors).
> - TDD 판단: 제외 대상 — 인터페이스 시그니처 분리 + 기존 구현체 메서드 재노출이므로 회귀 테스트만으로 충분.

---

### Task A2-2: `registerSingleton`에 `DisposableBean` 자동 콜백 등록 (TDD)

> **TDD 적용 여부:** 적용 — 등록 시점 분기(DisposableBean 인식 + `destroy throws Exception` 래핑)가 본질. 회귀 방지 필수.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryDestroyTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * registerSingleton으로 직접 등록된 DisposableBean도 destroySingletons 호출 시
 * destroy 콜백이 실행되어야 한다. 1B-α의 RefreshFailureCleanupTest/CloseAndShutdownHookTest가 이 동작에 의존.
 */
class DefaultSingletonBeanRegistryDestroyTest {

    static class TrackingDisposable implements DisposableBean {
        final List<String> log;
        TrackingDisposable(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    static class TrackingThrowingDisposable implements DisposableBean {
        @Override public void destroy() throws Exception {
            throw new Exception("boom from destroy");
        }
    }

    @Test
    void registerSingletonWithDisposableTriggersDestroyCallback() {
        var registry = new DefaultSingletonBeanRegistry() {};
        var log = new ArrayList<String>();
        registry.registerSingleton("a", new TrackingDisposable(log));

        registry.destroySingletons();

        assertThat(log).containsExactly("destroyed");
    }

    @Test
    void registerSingletonWithNonDisposableDoesNotRegisterCallback() {
        var registry = new DefaultSingletonBeanRegistry() {};
        registry.registerSingleton("plain", "just-a-string");

        // 예외 없이 통과해야 함 (콜백이 등록되지 않았는지는 간접 확인)
        registry.destroySingletons();
    }

    @Test
    void destroyIsExecutedInReverseRegistrationOrder() {
        var registry = new DefaultSingletonBeanRegistry() {};
        var log = new ArrayList<String>();
        registry.registerSingleton("a", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("a"); }
        });
        registry.registerSingleton("b", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("b"); }
        });

        registry.destroySingletons();

        assertThat(log).containsExactly("b", "a");  // LIFO
    }

    @Test
    void checkedExceptionFromDestroyIsSwallowedAndOthersStillRun() {
        var registry = new DefaultSingletonBeanRegistry() {};
        var log = new ArrayList<String>();
        registry.registerSingleton("throwing", new TrackingThrowingDisposable());
        registry.registerSingleton("ok", new TrackingDisposable(log) {
            @Override public void destroy() { log.add("ok-destroyed"); }
        });

        registry.destroySingletons();  // 예외 새어나오면 안 됨

        // ok가 먼저 등록 역순으로 호출되어 완료되었는지 확인
        assertThat(log).containsExactly("ok-destroyed");
    }
}
```

> **참고:** `DefaultSingletonBeanRegistry`는 abstract가 아니지만 생성자가 보호되어 있지 않으므로 `new DefaultSingletonBeanRegistry() {}` 익명 서브클래스로 인스턴스화. 1A 테스트에서 동일 패턴 사용 여부를 확인하고, 1A가 다른 방식(예: 테스트 fixture 클래스)을 쓰면 그에 맞춘다.

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryDestroyTest
```
예상: `registerSingletonWithDisposableTriggersDestroyCallback` FAIL (log 비어있음). 나머지 3건은 PASS 가능.

- [x] **Step 3: `registerSingleton` 보강 — `DefaultSingletonBeanRegistry`**

기존 `registerSingleton` 본문 끝에 DisposableBean 감지 분기 추가:

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
                // destroySingletons의 개별 실패 격리 정책과 동일 — 상위에서 로그 처리
                throw new RuntimeException("DisposableBean.destroy failed for '" + name + "'", e);
            }
        });
    }
}
```

> **정책 노트 (주석 금지 규칙과의 조화):** 위 `try/catch` 래핑은 `Runnable`이 체크 예외를 허용하지 않아서 강제되는 어댑팅이며, `destroySingletons`의 `catch (Throwable t)` 블록이 개별 실패를 격리하는 정책과 맞물려 있다. 이 이유가 코드만 봐서는 명확하지 않으므로 주석 한 줄은 남긴다 (CLAUDE.md "WHY가 non-obvious" 예외).

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test
```
예상: 신규 4개 PASS + 1A 64개 그대로 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): registerSingleton에 DisposableBean 자동 감지 및 destroy 콜백 등록"
```

> **실행 기록 (2026-04-24):**
> - 선행 조건 확인: `DisposableBean`, `registerDisposableBean`, `destroySingletons` 모두 1A에서 완성됨.
> - 인스턴스화 패턴: 기존 `DefaultSingletonBeanRegistryTest`가 `new DefaultSingletonBeanRegistry()`로 직접 인스턴스화하므로, Plan 코드의 익명 서브클래스 패턴 대신 직접 인스턴스화로 작성.
> - RED: 3건 FAIL (registerSingletonWithDisposableTriggersDestroyCallback, destroyIsExecutedInReverseRegistrationOrder, checkedExceptionFromDestroyIsSwallowedAndOthersStillRun), 1건 PASS.
> - GREEN: `registerSingleton` 끝에 `instanceof DisposableBean` 분기 + `try/catch` 어댑터 추가.
> - 전체 결과: 신규 4건 + 기존 44건 = 총 48건 PASS (Plan 예상 68건 대비 차이는 선행 Task A2-1 기록의 "65건" 시점과 일치하는 기존 편차).
> - TDD 판단: 적용 대상 — 등록 시점 분기(instanceof 체크 + 체크 예외 래핑)가 본질적 분기 로직.

---

## 섹션 B: `sfs-core` AnnotationUtils + 애노테이션 10종 (Task 4~6)

### Task 4: `AnnotationUtils.isAnnotated` (메타-애노테이션 재귀 인식)

> **TDD 적용 여부:** 적용 — 메타 재귀 + 사이클 방지가 본질.

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationUtils.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AnnotationUtilsTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationUtilsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Marker {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Marker
    @interface MetaMarker {}  // Marker를 메타-애노테이션으로 가짐

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @MetaMarker
    @interface DeepMeta {}  // MetaMarker를 통해 Marker를 간접 보유

    // 사이클 방지 검증용: A→B, B→A
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @interface CycleA {}
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @CycleA
    @interface CycleB {}

    @Marker static class DirectlyMarked {}
    @MetaMarker static class MetaMarked {}
    @DeepMeta static class DeepMetaMarked {}
    @CycleB static class CycleMarked {}
    static class NotMarked {}

    @Test
    void directAnnotationIsDetected() {
        assertThat(AnnotationUtils.isAnnotated(DirectlyMarked.class, Marker.class)).isTrue();
    }

    @Test
    void metaAnnotationIsDetectedRecursively() {
        assertThat(AnnotationUtils.isAnnotated(MetaMarked.class, Marker.class)).isTrue();
    }

    @Test
    void deepMetaAnnotationIsDetectedTwoLevelsDown() {
        assertThat(AnnotationUtils.isAnnotated(DeepMetaMarked.class, Marker.class)).isTrue();
    }

    @Test
    void notAnnotatedReturnsFalse() {
        assertThat(AnnotationUtils.isAnnotated(NotMarked.class, Marker.class)).isFalse();
    }

    @Test
    void cyclicMetaAnnotationsDoNotCauseInfiniteLoop() {
        // Marker는 CycleA/B 어디에도 없으므로 false. 무한루프 없이 종료가 핵심.
        assertThat(AnnotationUtils.isAnnotated(CycleMarked.class, Marker.class)).isFalse();
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-core:test --tests AnnotationUtilsTest
```
예상: FAIL (AnnotationUtils 미존재 — 컴파일 에러).

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.core;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

/**
 * 애노테이션 메타-탐색 유틸. 클래스에 직접 붙은 애노테이션뿐 아니라,
 * 그 애노테이션이 다시 메타-애노테이션으로 보유한 것들까지 재귀 탐색한다.
 * <p>예: {@code @Service}는 {@code @Component}를 메타-애노테이션으로 가지므로
 * {@code isAnnotated(SomeService.class, Component.class)}는 true.
 *
 * <p>Spring 원본: {@code org.springframework.core.annotation.AnnotationUtils}.
 */
public final class AnnotationUtils {

    private AnnotationUtils() {}

    /** {@code clazz}가 {@code target} 애노테이션을 직접 또는 메타로 보유하는지 검사. */
    public static boolean isAnnotated(Class<?> clazz, Class<? extends Annotation> target) {
        Assert.notNull(clazz, "clazz");
        Assert.notNull(target, "target");
        return isAnnotatedRecursive(clazz.getAnnotations(), target, new HashSet<>());
    }

    private static boolean isAnnotatedRecursive(
            Annotation[] annotations,
            Class<? extends Annotation> target,
            Set<Class<? extends Annotation>> visited) {
        for (Annotation a : annotations) {
            Class<? extends Annotation> type = a.annotationType();
            if (type.equals(target)) return true;
            // JDK 메타-애노테이션은 무한 재귀 위험이 없지만 사용자 정의 사이클 방어
            if (!visited.add(type)) continue;
            if (type.getName().startsWith("java.lang.annotation.")) continue;
            if (isAnnotatedRecursive(type.getAnnotations(), target, visited)) return true;
        }
        return false;
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-core:test --tests AnnotationUtilsTest
```
예상: 5/5 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): AnnotationUtils.isAnnotated — 메타 재귀 + 사이클 방지"
```

> **실행 기록 (2026-04-24):**
> - TDD 판단: 적용 대상 — 메타 재귀 + 사이클 방지가 본질적 분기 로직.
> - RED: Plan 문서 코드 그대로 테스트 작성 후 AnnotationUtils 미존재로 컴파일 에러 확인.
> - Plan 편차: `@CycleB`의 `@Target`이 `ANNOTATION_TYPE`만으로는 일반 클래스(`CycleMarked`)에 붙일 수 없어 컴파일 에러 발생. `@Target`에 `ElementType.TYPE`을 추가해 해결 (사이클 방지 테스트 의도 동일하게 유지).
> - GREEN: 신규 5건 + 기존 20건 = 총 25건 PASS.

---

### Task 5: 애노테이션 10종 정의 (1B-α 분량)

> **TDD 적용 여부:** 제외 — 메타정보 정의만, 통합 테스트로 간접 검증.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Component.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Service.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Repository.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Controller.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Configuration.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Bean.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Scope.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Lazy.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Primary.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Qualifier.java`

- [x] **Step 1: `@Component`**

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Component {
    /** 빈 이름 명시. 비어 있으면 BeanNameGenerator의 기본값 사용. */
    String value() default "";
}
```

- [x] **Step 2: `@Service` / `@Repository` / `@Controller` (모두 `@Component` 메타)**

`@Service`:

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Service {
    String value() default "";
}
```

`@Repository`, `@Controller`도 동일 패턴 (이름만 변경).

- [x] **Step 3: `@Configuration`** (`proxyBeanMethods` 옵션은 1B-β에서 사용)

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Configuration {
    String value() default "";
    /** 1B-β에서 사용. true면 byte-buddy enhance, false면 enhance 생략. */
    boolean proxyBeanMethods() default true;
}
```

- [x] **Step 4: `@Bean`** (1B-α에서는 정의만, 처리는 1B-β)

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Bean {
    String[] name() default {};
    String initMethod() default "";
    String destroyMethod() default "";
}
```

- [x] **Step 5: `@Scope`**

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Scope {
    String value() default "singleton";
}
```

- [x] **Step 6: `@Lazy` (class-level only, Q9 결정)**

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Lazy {
    boolean value() default true;
}
```

- [x] **Step 7: `@Primary`**

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Primary {}
```

- [x] **Step 8: `@Qualifier`**

```java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,
         ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
public @interface Qualifier {
    String value() default "";
}
```

- [x] **Step 9: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [x] **Step 10: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/annotation/
git commit -m "feat(sfs-context): 애노테이션 10종 정의 (@Component, @Service, @Repository, @Controller, @Configuration, @Bean, @Scope, @Lazy, @Primary, @Qualifier)"
```

> **실행 기록 (2026-04-24):**
> - TDD 제외 근거: 애노테이션 메타정보 정의만 (필드/메서드 구현 없음). CLAUDE.md "enum / sealed 단순 정의, 인터페이스 시그니처" 범주. 처리 로직은 Task 6(메타-인식 통합 테스트), Task 15~17(Reader/Scanner)에서 간접 검증.
> - 편차 없음: Plan 문서 코드 그대로 적용.
> - 컴파일 결과: `./gradlew :sfs-context:compileJava` → BUILD SUCCESSFUL (514ms).
> - 회귀 테스트 결과: `./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test` → BUILD SUCCESSFUL, 전체 테스트 PASS.

---

### Task 6: 애노테이션 메타-인식 검증 통합 테스트

> **TDD 적용 여부:** 적용 — 메타-인식 동작이 본질.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/annotation/StereotypeMetaTest.java`

- [x] **Step 1: 테스트 작성**

```java
package com.choisk.sfs.context.annotation;

import com.choisk.sfs.core.AnnotationUtils;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Service/@Repository/@Controller/@Configuration이 @Component를 메타로 보유하는지 검증.
 * 스캐너가 정확히 이 동작에 의존하므로 단독 회귀 테스트.
 */
class StereotypeMetaTest {

    @Service static class S {}
    @Repository static class R {}
    @Controller static class C {}
    @Configuration static class Cfg {}
    static class Plain {}

    @Test
    void serviceIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(S.class, Component.class)).isTrue();
    }

    @Test
    void repositoryIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(R.class, Component.class)).isTrue();
    }

    @Test
    void controllerIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(C.class, Component.class)).isTrue();
    }

    @Test
    void configurationIsComponent() {
        assertThat(AnnotationUtils.isAnnotated(Cfg.class, Component.class)).isTrue();
    }

    @Test
    void plainClassIsNotComponent() {
        assertThat(AnnotationUtils.isAnnotated(Plain.class, Component.class)).isFalse();
    }
}
```

- [x] **Step 2: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests StereotypeMetaTest
```
예상: 5/5 PASS.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): @Service/@Repository/@Controller/@Configuration 메타 인식 검증"
```

> **실행 기록 (2026-04-24):**
> - TDD 적용 판단: TDD 적용 대상(메타-인식 동작이 본질)이나 Task 4(`AnnotationUtils` 구현)와 Task 5(애노테이션 10종 정의)가 선행 완료된 상태라 RED 단계가 성립하지 않음. 특성화 테스트(characterization test / regression safety net) 방식으로 진행 — "테스트 작성 → 즉시 PASS 확인 → 커밋" 순서.
> - Step 2 결과: `./gradlew :sfs-context:test --tests StereotypeMetaTest` → 5/5 최초 실행부터 PASS (편차 없음).
> - sfs-context 전체 회귀 확인: `./gradlew :sfs-context:test` → 6건 PASS (PackageSmokeTest 1건 + StereotypeMetaTest 5건). 1 → 6으로 증가.

---

## 섹션 C: ApplicationContext 인터페이스 (Task 7~8)

### Task 7: `ApplicationContext` 인터페이스

> **TDD 적용 여부:** 제외 — 시그니처만.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/ApplicationContext.java`

- [x] **Step 1: 인터페이스 작성**

```java
package com.choisk.sfs.context;

import com.choisk.sfs.beans.BeanFactory;

/**
 * BeanFactory의 상위 추상화. 1B-α 시점에는 메타 정보 + 식별자 정도만 노출하고,
 * Environment/MessageSource/Resource 등은 후속 페이즈에서 추가.
 *
 * <p>Spring 원본: {@code org.springframework.context.ApplicationContext}.
 */
public interface ApplicationContext extends BeanFactory {
    String getId();
    String getApplicationName();
    long getStartupDate();
}
```

- [x] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): ApplicationContext 인터페이스 (BeanFactory 상위)"
```

> **실행 기록 (2026-04-24):**
> - TDD 제외 근거: 인터페이스 시그니처만 선언 (메서드 구현 없음). CLAUDE.md "인터페이스 시그니처" 범주에 해당. Task 9~10의 `AbstractApplicationContext` 구현 시 간접 검증.
> - 컴파일 결과: BUILD SUCCESSFUL (`:sfs-context:compileJava`)
> - 회귀 테스트 결과: sfs-core / sfs-beans / sfs-context 전체 PASS, 변화 없음 확인
> - 편차: 없음

---

### Task 8: `ConfigurableApplicationContext` 인터페이스

> **TDD 적용 여부:** 제외 — 시그니처만.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/ConfigurableApplicationContext.java`

- [x] **Step 1: 인터페이스 작성**

```java
package com.choisk.sfs.context;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;

/**
 * 설정 가능한 ApplicationContext. refresh/close 라이프사이클 + BFPP 등록 + JVM shutdown hook.
 *
 * <p>{@link AutoCloseable} 채택으로 try-with-resources 사용 가능.
 *
 * <p>Spring 원본: {@code ConfigurableApplicationContext}.
 */
public interface ConfigurableApplicationContext extends ApplicationContext, AutoCloseable {

    /** 컨테이너 초기화 — 8단계 템플릿 메서드. 한 번만 호출 가능. */
    void refresh();

    /** 컨테이너 종료. idempotent. */
    @Override
    void close();

    boolean isActive();

    /** JVM 종료 시 자동으로 {@link #close()}를 호출하도록 hook 등록. idempotent. */
    void registerShutdownHook();

    /** 내부 BeanFactory 노출 (BFPP가 BeanDefinition을 수정할 수 있도록). */
    ConfigurableListableBeanFactory getBeanFactory();

    /** refresh() 5단계에서 호출될 BFPP를 등록. */
    void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);
}
```

> **참고:** `ConfigurableListableBeanFactory`는 Plan 1A에서 `sfs-beans`에 정의되어 있어야 한다. 누락된 경우 본 Task 진행을 멈추고 sfs-beans에 추가 후 진행.

- [x] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): ConfigurableApplicationContext (refresh/close/shutdown hook)"
```

> **실행 기록 (2026-04-24):**
> - TDD 제외 근거: 메서드 선언(시그니처)만 존재하는 인터페이스 — 동작(분기/상태/알고리즘) 없음. Task 9~12의 `AbstractApplicationContext` 구현 시 간접 검증.
> - 컴파일 결과: `./gradlew :sfs-context:compileJava` → BUILD SUCCESSFUL (392ms)
> - 회귀 테스트 결과: `./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test` → BUILD SUCCESSFUL (변화 없음, 기존 테스트 모두 UP-TO-DATE/PASS 유지)
> - 편차: 없음. Plan 문서 코드와 동일하게 작성.

---

## 섹션 D: AbstractApplicationContext refresh 8단계 템플릿 (Task 9~12)

### Task 9: `AbstractApplicationContext` 골격 + 단계 메서드 시그니처

> **TDD 적용 여부:** 제외 — 추상 골격 + 시그니처. Task 10~12에서 동작별 단독 TDD.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`

- [x] **Step 1: 골격 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.ApplicationContext;
import com.choisk.sfs.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * refresh() 8단계 템플릿 메서드. 서브클래스는 {@link #refreshBeanFactory()}와
 * {@link #getBeanFactory()}만 채우면 된다.
 *
 * <p>Spring 원본: {@code AbstractApplicationContext}.
 */
public abstract class AbstractApplicationContext implements ConfigurableApplicationContext {

    private final List<BeanFactoryPostProcessor> bfpps = new ArrayList<>();
    private final Object startupShutdownMonitor = new Object();
    private volatile boolean active = false;
    private long startupDate;
    private final String id = String.valueOf(System.identityHashCode(this));
    private Thread shutdownHook;

    @Override public final String getId() { return id; }
    @Override public String getApplicationName() { return ""; }
    @Override public long getStartupDate() { return startupDate; }
    @Override public boolean isActive() { return active; }

    @Override
    public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
        bfpps.add(postProcessor);
    }

    protected List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
        return bfpps;
    }

    /** 서브클래스에서 BeanFactory 인스턴스를 신규 생성/재설정. single-shot 정책 강제. */
    protected abstract void refreshBeanFactory();

    @Override
    public abstract ConfigurableListableBeanFactory getBeanFactory();

    // refresh()/close() 본문은 Task 10/12에서 구현
    @Override public void refresh() { throw new UnsupportedOperationException("Task 10에서 구현"); }
    @Override public void close() { throw new UnsupportedOperationException("Task 12에서 구현"); }
    @Override public void registerShutdownHook() { throw new UnsupportedOperationException("Task 12에서 구현"); }

    // BeanFactory 위임 (1A 인터페이스 만족용 — 실제 구현은 getBeanFactory() 위임)
    @Override public Object getBean(String name) { return getBeanFactory().getBean(name); }
    @Override public <T> T getBean(String name, Class<T> requiredType) { return getBeanFactory().getBean(name, requiredType); }
    @Override public <T> T getBean(Class<T> requiredType) { return getBeanFactory().getBean(requiredType); }
    @Override public boolean containsBean(String name) { return getBeanFactory().containsBean(name); }
    @Override public boolean isSingleton(String name) { return getBeanFactory().isSingleton(name); }
    @Override public boolean isPrototype(String name) { return getBeanFactory().isPrototype(name); }
    @Override public Class<?> getType(String name) { return getBeanFactory().getType(name); }

    // 내부 라이프사이클 보조 (Task 10/11/12에서 채움)
    protected void prepareRefresh() { startupDate = System.currentTimeMillis(); }
    protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
        refreshBeanFactory();
        return getBeanFactory();
    }
    protected void prepareBeanFactory(ConfigurableListableBeanFactory bf) { /* no-op (확장점) */ }
    protected void postProcessBeanFactory(ConfigurableListableBeanFactory bf) { /* no-op (서브클래스 hook) */ }
    protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory bf) {
        for (BeanFactoryPostProcessor bfpp : bfpps) bfpp.postProcessBeanFactory(bf);
    }
    protected void registerBeanPostProcessors(ConfigurableListableBeanFactory bf) {
        // 1B-α: no-op. 1B-β에서 BPP 자동 등록 추가 예정.
    }
    protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory bf) {
        bf.preInstantiateSingletons();
    }
    protected void finishRefresh() { /* no-op (이벤트 발행 등은 후속 페이즈) */ }
    protected void cancelRefresh(RuntimeException ex) { active = false; }
    protected void destroyBeans() { getBeanFactory().destroySingletons(); }

    // setter for active (Task 10에서 사용)
    protected void setActive(boolean active) { this.active = active; }
    protected Object getStartupShutdownMonitor() { return startupShutdownMonitor; }
    protected Thread getShutdownHook() { return shutdownHook; }
    protected void setShutdownHook(Thread t) { this.shutdownHook = t; }
}
```

> **선결 조건 확인:** `BeanFactory`는 1A에서 `sfs-beans`에 정의되어 있다. `ConfigurableListableBeanFactory`가 `preInstantiateSingletons()`/`destroySingletons()`를 노출하는지 확인. 없으면 sfs-beans에 추가하고 별도 커밋(`feat(sfs-beans): ConfigurableListableBeanFactory에 preInstantiateSingletons/destroySingletons 노출`)으로 분리.

- [x] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): AbstractApplicationContext 골격 + 단계 메서드 시그니처"
```

> **실행 기록 (2026-04-24):**
> - **TDD 제외 근거:** 추상 골격 클래스 + 시그니처 선언 전용. CLAUDE.md "TDD 적용 가이드"의 "추상 골격 클래스" 범주 ("서브클래스 없이 인스턴스화 불가, 통합 테스트로 간접 검증"). 동작별 TDD는 Task 10(`refresh()`) / Task 12(`close()` + shutdown hook)에서 적용 예정.
> - **선결 조건 확인:** `ConfigurableListableBeanFactory`에 `preInstantiateSingletons()` 직접 선언 (라인 6) 확인. `ConfigurableBeanFactory`를 상속하므로 `destroySingletons()` 전이 노출 확인 (라인 11). sfs-beans 추가 수정 불필요 — Plan 문서의 "없으면 sfs-beans에 추가" 분기 발생하지 않음.
> - **컴파일 결과:** BUILD SUCCESSFUL (`this-escape` 경고 1건 — `System.identityHashCode(this)` Plan 문서 의도 설계로 허용).
> - **회귀 테스트 결과:** sfs-core / sfs-beans / sfs-context 전 모듈 BUILD SUCCESSFUL. sfs-context 기존 테스트 변화 없음 (전체 PASS).
> - **편차:** 없음. Plan 문서 코드 그대로 적용.

---

### Task 10: `refresh()` 8단계 정상 흐름 (TDD)

> **TDD 적용 여부:** 적용 — 단계 순서 + 호출 횟수가 본질.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/AbstractApplicationContextTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractApplicationContextTest {

    /** 단계 호출을 기록하는 추적 컨텍스트. */
    static class TracingContext extends AbstractApplicationContext {
        final List<String> trace = new ArrayList<>();
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        boolean refreshed = false;

        @Override protected void refreshBeanFactory() {
            if (refreshed) throw new IllegalStateException("single-shot violated");
            refreshed = true;
            trace.add("refreshBeanFactory");
        }
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
        @Override protected void prepareRefresh()                                      { trace.add("prepareRefresh");        super.prepareRefresh(); }
        @Override protected ConfigurableListableBeanFactory obtainFreshBeanFactory()   { trace.add("obtainFreshBeanFactory");return super.obtainFreshBeanFactory(); }
        @Override protected void prepareBeanFactory(ConfigurableListableBeanFactory b) { trace.add("prepareBeanFactory"); }
        @Override protected void postProcessBeanFactory(ConfigurableListableBeanFactory b) { trace.add("postProcessBeanFactory"); }
        @Override protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory b) { trace.add("invokeBfpps"); super.invokeBeanFactoryPostProcessors(b); }
        @Override protected void registerBeanPostProcessors(ConfigurableListableBeanFactory b)      { trace.add("registerBpps"); }
        @Override protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory b) { trace.add("finishBfInit"); super.finishBeanFactoryInitialization(b); }
        @Override protected void finishRefresh()                                                    { trace.add("finishRefresh"); }
    }

    @Test
    void refreshExecutesEightStepsInOrder() {
        var ctx = new TracingContext();
        ctx.refresh();
        assertThat(ctx.trace).containsExactly(
            "prepareRefresh",
            "obtainFreshBeanFactory",
            "refreshBeanFactory",            // obtainFreshBeanFactory가 호출
            "prepareBeanFactory",
            "postProcessBeanFactory",
            "invokeBfpps",
            "registerBpps",
            "finishBfInit",
            "finishRefresh"
        );
        assertThat(ctx.isActive()).isTrue();
    }

    @Test
    void refreshIsSingleShot() {
        var ctx = new TracingContext();
        ctx.refresh();
        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("single-shot");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests AbstractApplicationContextTest
```
예상: FAIL (refresh가 UnsupportedOperationException을 던짐).

- [x] **Step 3: `refresh()` 본문 구현 — `AbstractApplicationContext.refresh()`**

```java
@Override
public void refresh() {
    synchronized (startupShutdownMonitor) {
        prepareRefresh();                                   // 1
        ConfigurableListableBeanFactory bf = obtainFreshBeanFactory(); // 2
        prepareBeanFactory(bf);                             // 3
        try {
            postProcessBeanFactory(bf);                     // 4
            invokeBeanFactoryPostProcessors(bf);            // 5
            registerBeanPostProcessors(bf);                 // 6
            finishBeanFactoryInitialization(bf);            // 7
            finishRefresh();                                // 8
            active = true;
        } catch (RuntimeException ex) {
            destroyBeans();
            cancelRefresh(ex);
            throw ex;
        }
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests AbstractApplicationContextTest
```
예상: 2/2 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): refresh() 8단계 정상 흐름 + single-shot 가드"
```

> **실행 기록 (2026-04-24):**
> - **TDD 적용 근거:** `refresh()` 8단계 호출 순서 + single-shot 정책(2회 호출 시 `IllegalStateException`)이 본질적 동작 분기 — 테스트 없이는 순서 뒤바뀜과 가드 누락을 감지할 안전망이 없음.
> - **RED:** 2/2 FAIL (`UnsupportedOperationException` — 예상된 이유로 실패)
> - **GREEN:** 2/2 PASS (`refresh()` 8단계 템플릿 + `active = true` 할당 구현 후)
> - **sfs-context 전체 테스트 수 변화:** 6건 → 8건 (기존 `PackageSmokeTest` 1건 + `StereotypeMetaTest` 5건 + 신규 `AbstractApplicationContextTest` 2건)
> - **편차:** 없음. Plan 문서 코드 그대로 적용.

---

### Task 11: `refresh()` 실패 시 자동 cleanup (TDD)

> **TDD 적용 여부:** 적용 — try-catch + destroyBeans + cancelRefresh가 본질.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/RefreshFailureCleanupTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DisposableBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.support.AbstractApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshFailureCleanupTest {

    /** destroy 호출을 외부 리스트에 기록. */
    static class Tracking implements DisposableBean {
        final List<String> log;
        Tracking(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    /** 5단계(BFPP) 실행 중에 throw하는 BFPP. */
    static class ExplodingBfpp implements BeanFactoryPostProcessor {
        @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
            throw new RuntimeException("boom in BFPP");
        }
    }

    static class TestContext extends AbstractApplicationContext {
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        @Override protected void refreshBeanFactory() {}
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
    }

    @Test
    void refreshFailureTriggersDestroyBeans() {
        var log = new ArrayList<String>();
        var ctx = new TestContext();
        // 7단계 도달 전 5단계에서 throw 시키되, 1단계 미만에 빈을 미리 등록해 destroy 검증
        ctx.getBeanFactory().registerSingleton("preset", new Tracking(log));
        ctx.addBeanFactoryPostProcessor(new ExplodingBfpp());

        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(RuntimeException.class)
            .hasMessage("boom in BFPP");

        assertThat(log).containsExactly("destroyed");  // destroyBeans()가 호출됐음을 입증
        assertThat(ctx.isActive()).isFalse();
    }
}
```

> **선결 조건:** 섹션 A2-2에서 `registerSingleton`이 `DisposableBean`을 자동 감지해 `registerDisposableBean` 콜백을 등록하도록 보강 완료. 본 Task는 그 동작을 컨텍스트 레벨에서 통합 검증.

- [x] **Step 2: 테스트 실행**

```bash
./gradlew :sfs-context:test --tests RefreshFailureCleanupTest
```
예상: PASS (Task 10에서 try-catch + destroyBeans 이미 구현됨). 만약 destroy가 호출 안되면 sfs-beans `registerSingleton` 측 보강 필요.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): refresh() 실패 시 destroyBeans + cancelRefresh 자동 호출 검증"
```

> **실행 기록 (2026-04-24):**
> - **TDD 적용 판단:** TDD 적용 대상이나 선행 구현(Task A2-2 `registerSingleton` DisposableBean 자동 감지 + Task 10 refresh try-catch) 완료로 RED 단계 성립 불가 — 특성화 통합 테스트 방식으로 진행. "테스트 작성 → 즉시 PASS 확인 → 커밋" 순서로 처리.
> - **테스트 결과:** 1건 최초 실행부터 PASS (`refreshFailureTriggersDestroyBeans`). 원본 예외 전파(`boom in BFPP`), `log.containsExactly("destroyed")`, `ctx.isActive() == false` 모두 통과.
> - **sfs-context 전체 테스트 수 변화:** 8건 → 9건 (`RefreshFailureCleanupTest` 1건 신규 추가, XML 집계: 5+1+1+2=9).
> - **편차:** Plan 라인 1267의 사용되지 않는 `BeanDefinition` import 제거 1건 — 실제 테스트 본문에서 미사용이므로 IDE 경고 방지 및 CLAUDE.md 명시 import 원칙 준수 차원에서 제거.

---

### Task 12: `close()` + JVM shutdown hook (TDD)

> **TDD 적용 여부:** 적용 — idempotent + JVM 진행 중 예외 처리가 본질.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/CloseAndShutdownHookTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DisposableBean;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.support.AbstractApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloseAndShutdownHookTest {

    static class Tracking implements DisposableBean {
        final List<String> log;
        Tracking(List<String> log) { this.log = log; }
        @Override public void destroy() { log.add("destroyed"); }
    }

    static class TestContext extends AbstractApplicationContext {
        final DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        @Override protected void refreshBeanFactory() {}
        @Override public ConfigurableListableBeanFactory getBeanFactory() { return bf; }
    }

    @Test
    void closeIsIdempotent() {
        var log = new ArrayList<String>();
        var ctx = new TestContext();
        ctx.getBeanFactory().registerSingleton("a", new Tracking(log));
        ctx.refresh();

        ctx.close();
        ctx.close();  // 두 번째 호출 — 예외 없어야 하고 destroy도 한 번만

        assertThat(log).containsExactly("destroyed");
        assertThat(ctx.isActive()).isFalse();
    }

    @Test
    void closeWithoutRefreshIsNoOp() {
        var ctx = new TestContext();
        ctx.close();  // refresh 호출 없이 close — 예외 없어야 함
        assertThat(ctx.isActive()).isFalse();
    }

    @Test
    void registerShutdownHookIsIdempotent() {
        var ctx = new TestContext();
        ctx.refresh();
        ctx.registerShutdownHook();
        ctx.registerShutdownHook();  // 두 번째 호출 — Thread는 한 개만 등록되어야

        // 정리 (테스트 환경에서 실제 hook 실행 회피)
        ctx.close();
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests CloseAndShutdownHookTest
```
예상: FAIL (close()/registerShutdownHook이 UnsupportedOperationException).

- [x] **Step 3: `close()` + `registerShutdownHook()` 구현 — `AbstractApplicationContext`**

```java
@Override
public void close() {
    synchronized (startupShutdownMonitor) {
        if (!active) {
            // refresh 안 했거나 이미 close 호출됨 — idempotent
            if (shutdownHook != null) {
                tryRemoveShutdownHook();
            }
            return;
        }
        doClose();
        if (shutdownHook != null) {
            tryRemoveShutdownHook();
        }
    }
}

@Override
public void registerShutdownHook() {
    if (shutdownHook != null) return;  // idempotent
    shutdownHook = new Thread(this::doClose, "sfs-context-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
}

private void tryRemoveShutdownHook() {
    try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException ignore) {
        // JVM shutdown 진행 중이면 정상 — 무시
    }
    shutdownHook = null;
}

private void doClose() {
    active = false;
    destroyBeans();
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests CloseAndShutdownHookTest
```
예상: 3/3 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): close()/registerShutdownHook() — idempotent + JVM 진행 중 예외 무시"
```

> **실행 기록 (2026-04-24):**
> - TDD 적용 근거: `close()`의 idempotent 동작(이미 닫힌 컨텍스트에 재호출)과 JVM shutdown 진행 중 `IllegalStateException` 예외 처리가 본질적 분기 — 자동 안전망이 없으므로 TDD 필수.
> - RED: 3/3 FAIL — 원인: `UnsupportedOperationException` (Task 9 placeholder, 예상 일치)
> - GREEN: 3/3 PASS — `close()`, `closeWithoutRefreshIsNoOp`, `registerShutdownHookIsIdempotent` 모두 통과
> - sfs-context 전체 테스트: 9 → 12건 (3건 추가)
> - 편차 없음 — Plan 문서 코드 그대로 적용

---

## 섹션 E: GenericApplicationContext (Task 13)

### Task 13: `GenericApplicationContext` (single-shot)

> **TDD 적용 여부:** 적용 — single-shot 가드가 본질.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/GenericApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/GenericApplicationContextTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericApplicationContextTest {

    static class Foo {}

    @Test
    void registerBeanDefinitionThenRefreshSucceeds() {
        var ctx = new GenericApplicationContext();
        ctx.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        ctx.refresh();
        assertThat(ctx.getBean("foo")).isInstanceOf(Foo.class);
    }

    @Test
    void refreshTwiceThrows() {
        var ctx = new GenericApplicationContext();
        ctx.refresh();
        assertThatThrownBy(ctx::refresh)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already called");
    }

    @Test
    void externalBeanFactoryConstructorAccepted() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        var ctx = new GenericApplicationContext(bf);
        ctx.refresh();
        assertThat(ctx.getBean("foo")).isInstanceOf(Foo.class);
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-context:test --tests GenericApplicationContextTest
```
예상: 컴파일 에러.

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 가장 일반적인 ApplicationContext 구현. BeanDefinition을 직접 등록하거나
 * 외부에서 미리 채워진 {@link DefaultListableBeanFactory}를 받을 수 있다.
 *
 * <p>{@code refresh()}는 single-shot — 두 번 호출 시 예외.
 *
 * <p>Spring 원본: {@code GenericApplicationContext}.
 */
public class GenericApplicationContext extends AbstractApplicationContext {

    private final DefaultListableBeanFactory beanFactory;
    private final AtomicBoolean refreshed = new AtomicBoolean(false);

    public GenericApplicationContext() {
        this(new DefaultListableBeanFactory());
    }

    public GenericApplicationContext(DefaultListableBeanFactory bf) {
        this.beanFactory = Objects.requireNonNull(bf, "beanFactory");
    }

    public void registerBeanDefinition(String name, BeanDefinition bd) {
        beanFactory.registerBeanDefinition(name, bd);
    }

    @Override
    protected void refreshBeanFactory() {
        if (!refreshed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "GenericApplicationContext refresh() already called — single-shot context");
        }
    }

    @Override
    public ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests GenericApplicationContextTest
```
예상: 3/3 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): GenericApplicationContext — single-shot refresh + 외부 BF 주입 옵션"
```

> **실행 기록 (2026-04-24):**
> - TDD 적용 근거: `refreshBeanFactory()` 내 `AtomicBoolean.compareAndSet` single-shot 가드가 핵심 분기 동작 — 제외 대상 아님, TDD 필수 적용
> - RED: `GenericApplicationContext` 클래스 미존재로 컴파일 에러 3건 (3/3 컴파일 실패)
> - GREEN: 3/3 PASS (`registerBeanDefinitionThenRefreshSucceeds`, `refreshTwiceThrows`, `externalBeanFactoryConstructorAccepted`)
> - sfs-context 전체 테스트 수: 12 → 15건 (전 모듈 BUILD SUCCESSFUL)
> - 편차 없음 — Plan 코드 그대로 사용

---

## 섹션 F: BeanNameGenerator + AnnotatedBeanDefinitionReader (Task 14~15)

### Task 14: `AnnotationBeanNameGenerator` (FQN→camelCase + `@Component("custom")` 우선)

> **TDD 적용 여부:** 적용 — 명시 우선 + camelCase 변환 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/BeanNameGenerator.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationBeanNameGenerator.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotationBeanNameGeneratorTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationBeanNameGeneratorTest {

    @Component static class MyService {}
    @Component("customName") static class HasCustom {}
    @Service("svc") static class StereotypeWithName {}
    @Service static class StereotypeNoName {}
    @Component static class A {}  // 한 글자 → "a"

    private final AnnotationBeanNameGenerator gen = new AnnotationBeanNameGenerator();

    @Test
    void plainComponentReturnsClassNameLowerFirst() {
        assertThat(gen.generate(MyService.class)).isEqualTo("myService");
    }

    @Test
    void explicitValueOnComponentWins() {
        assertThat(gen.generate(HasCustom.class)).isEqualTo("customName");
    }

    @Test
    void explicitValueOnStereotypeWins() {
        assertThat(gen.generate(StereotypeWithName.class)).isEqualTo("svc");
    }

    @Test
    void stereotypeWithoutValueFallsBackToClassName() {
        assertThat(gen.generate(StereotypeNoName.class)).isEqualTo("stereotypeNoName");
    }

    @Test
    void singleCharClassNameLowercased() {
        assertThat(gen.generate(A.class)).isEqualTo("a");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotationBeanNameGeneratorTest
```
예상: 컴파일 에러.

- [x] **Step 3: 인터페이스 + 구현 작성**

`BeanNameGenerator.java`:

```java
package com.choisk.sfs.context.support;

public interface BeanNameGenerator {
    String generate(Class<?> beanClass);
}
```

`AnnotationBeanNameGenerator.java`:

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.context.annotation.Configuration;

/**
 * Spring 기본 정책: 명시 value가 있으면 그것, 없으면 클래스 단순명을 첫 글자만 소문자로.
 * <p>예: {@code com.x.MyService} → {@code "myService"}.
 */
public class AnnotationBeanNameGenerator implements BeanNameGenerator {

    @Override
    public String generate(Class<?> beanClass) {
        // 명시 value 우선순위: @Component > @Service > @Repository > @Controller > @Configuration
        String explicit = explicitName(beanClass);
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return defaultName(beanClass);
    }

    private String explicitName(Class<?> c) {
        Component comp = c.getAnnotation(Component.class);
        if (comp != null && !comp.value().isEmpty()) return comp.value();
        Service svc = c.getAnnotation(Service.class);
        if (svc != null && !svc.value().isEmpty()) return svc.value();
        Repository rep = c.getAnnotation(Repository.class);
        if (rep != null && !rep.value().isEmpty()) return rep.value();
        Controller ctrl = c.getAnnotation(Controller.class);
        if (ctrl != null && !ctrl.value().isEmpty()) return ctrl.value();
        Configuration cfg = c.getAnnotation(Configuration.class);
        if (cfg != null && !cfg.value().isEmpty()) return cfg.value();
        return null;
    }

    private String defaultName(Class<?> c) {
        String simple = c.getSimpleName();
        if (simple.isEmpty()) return simple;
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotationBeanNameGeneratorTest
```
예상: 5/5 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): BeanNameGenerator + AnnotationBeanNameGenerator (명시 value 우선, FQN→camelCase 폴백)"
```

> **실행 기록 (2026-04-24):**
> - **TDD 적용 근거:** 명시 value 우선 분기 (`explicitName`) + `getSimpleName()` camelCase 변환 알고리즘이 본질적 동작. 분기/알고리즘 모두 TDD 적용 대상.
> - **RED 결과:** `AnnotationBeanNameGenerator` 클래스 미존재로 컴파일 에러 2건 발생 (FAIL 확인 완료).
> - **GREEN 결과:** `BeanNameGenerator` 인터페이스 + `AnnotationBeanNameGenerator` 구현 신설 후 5/5 PASS.
> - **회귀 테스트:** sfs-context 전체 테스트 15건 → 20건 (5건 추가). 전 모듈(:sfs-core, :sfs-beans, :sfs-context) BUILD SUCCESSFUL.
> - **편차:** 없음 — Plan 코드와 동일하게 구현, 모든 Step 정상 완료.

---

### Task 15: `AnnotatedBeanDefinitionReader` (`register(Class<?>...)`)

> **TDD 적용 여부:** 적용 — `@Scope`/`@Lazy`/`@Primary` 추출 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotatedBeanDefinitionReader.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotatedBeanDefinitionReaderTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;
import com.choisk.sfs.context.annotation.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedBeanDefinitionReaderTest {

    @Component static class Plain {}
    @Component @Scope("prototype") static class Proto {}
    @Component @Lazy static class LazyOne {}
    @Component @Primary static class PrimaryOne {}

    @Test
    void registerPlainCreatesSingletonDefinition() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(Plain.class);

        BeanDefinition bd = bf.getBeanDefinition("plain");
        assertThat(bd.getBeanClass()).isEqualTo(Plain.class);
        assertThat(bd.isSingleton()).isTrue();
        assertThat(bd.isLazyInit()).isFalse();
        assertThat(bd.isPrimary()).isFalse();
    }

    @Test
    void registerWithScopeCreatesPrototypeDefinition() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(Proto.class);

        BeanDefinition bd = bf.getBeanDefinition("proto");
        assertThat(bd.isPrototype()).isTrue();
    }

    @Test
    void registerWithLazyMarksLazy() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(LazyOne.class);

        assertThat(bf.getBeanDefinition("lazyOne").isLazyInit()).isTrue();
    }

    @Test
    void registerWithPrimaryMarksPrimary() {
        var bf = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(bf);
        reader.register(PrimaryOne.class);

        assertThat(bf.getBeanDefinition("primaryOne").isPrimary()).isTrue();
    }
}
```

> **선결 조건:** 섹션 A2-1에서 `BeanDefinitionRegistry` 신설 완료. `BeanDefinition`은 1A에서 이미 `setLazyInit(boolean)`/`isLazyInit()`/`setPrimary(boolean)`/`isPrimary()`/`setScope(Scope)`을 노출하므로 추가 보강 불필요.

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotatedBeanDefinitionReaderTest
```
예상: 컴파일 에러.

- [ ] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanDefinitionRegistry;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;

/**
 * 명시 등록 진입점. 클래스 객체에서 BeanDefinition을 생성하고
 * {@code @Scope}/{@code @Lazy}/{@code @Primary}를 적용한다.
 *
 * <p>Spring 원본: {@code AnnotatedBeanDefinitionReader}.
 */
public class AnnotatedBeanDefinitionReader {

    private final BeanDefinitionRegistry registry;
    private final BeanNameGenerator nameGenerator;

    public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry) {
        this(registry, new AnnotationBeanNameGenerator());
    }

    public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, BeanNameGenerator gen) {
        this.registry = registry;
        this.nameGenerator = gen;
    }

    public void register(Class<?>... componentClasses) {
        for (Class<?> c : componentClasses) {
            BeanDefinition bd = new BeanDefinition(c);
            applyScope(bd, c);
            applyLazy(bd, c);
            applyPrimary(bd, c);
            registry.registerBeanDefinition(nameGenerator.generate(c), bd);
        }
    }

    private void applyScope(BeanDefinition bd, Class<?> c) {
        com.choisk.sfs.context.annotation.Scope s =
                c.getAnnotation(com.choisk.sfs.context.annotation.Scope.class);
        if (s != null) bd.setScope(com.choisk.sfs.beans.Scope.byName(s.value()));
    }

    private void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazyInit(l.value());
    }

    private void applyPrimary(BeanDefinition bd, Class<?> c) {
        if (c.isAnnotationPresent(Primary.class)) bd.setPrimary(true);
    }
}
```

> **이름 충돌 노트:** `com.choisk.sfs.context.annotation.Scope`(애노테이션)와 `com.choisk.sfs.beans.Scope`(sealed 인터페이스) 이름이 같아 FQN을 명시. `import com.choisk.sfs.context.annotation.Scope;` 방식을 택하면 `bd.setScope(Scope.byName(...))` 호출부에서 `beans.Scope`를 FQN으로 써도 동일.

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotatedBeanDefinitionReaderTest
```
예상: 4/4 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): AnnotatedBeanDefinitionReader — @Scope/@Lazy/@Primary 추출 + 등록"
```

---

## 섹션 G: ClassPathBeanDefinitionScanner (Task 16~17)

### Task 16: `ClassPathBeanDefinitionScanner` (메타-인식 패키지 스캔)

> **TDD 적용 여부:** 적용 — 패키지 스캔 + 메타-인식 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/ClassPathBeanDefinitionScanner.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/ClassPathBeanDefinitionScannerTest.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/basic/SimpleService.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/basic/MetaTaggedService.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/samples/basic/PlainPojo.java`

- [ ] **Step 1: 샘플 클래스 작성**

`SimpleService.java`:
```java
package com.choisk.sfs.context.samples.basic;

import com.choisk.sfs.context.annotation.Component;

@Component
public class SimpleService {}
```

`MetaTaggedService.java`:
```java
package com.choisk.sfs.context.samples.basic;

import com.choisk.sfs.context.annotation.Service;

@Service
public class MetaTaggedService {}
```

`PlainPojo.java`:
```java
package com.choisk.sfs.context.samples.basic;

public class PlainPojo {}  // 애노테이션 없음 — 스캐너가 등록하면 안 됨
```

- [ ] **Step 2: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassPathBeanDefinitionScannerTest {

    @Test
    void scanRegistersComponentsAndMetaAnnotated() {
        var bf = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(bf);
        int count = scanner.scan("com.choisk.sfs.context.samples.basic");

        assertThat(count).isEqualTo(2);
        assertThat(bf.containsBeanDefinition("simpleService")).isTrue();
        assertThat(bf.containsBeanDefinition("metaTaggedService")).isTrue();
        assertThat(bf.containsBeanDefinition("plainPojo")).isFalse();
    }

    @Test
    void scanReturnsZeroForEmptyPackage() {
        var bf = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(bf);
        int count = scanner.scan("com.choisk.sfs.context.samples.nonexistent");
        assertThat(count).isEqualTo(0);
    }
}
```

> **선결 조건 확인:** `sfs-core`의 `ClassPathScanner.scan(String basePackage)`가 `Iterable<Class<?>>` 또는 그에 상응하는 형태로 클래스를 반환해야 한다. Plan 1A에서 정의된 시그니처를 그대로 사용.

- [ ] **Step 3: 테스트 실행 (FAIL — 스캐너 미존재)**

```bash
./gradlew :sfs-context:test --tests ClassPathBeanDefinitionScannerTest
```
예상: 컴파일 에러.

- [ ] **Step 4: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanDefinitionRegistry;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Lazy;
import com.choisk.sfs.context.annotation.Primary;
import com.choisk.sfs.core.AnnotationUtils;
import com.choisk.sfs.core.ClassPathScanner;

/**
 * 패키지를 스캔하여 {@code @Component} 메타-애노테이션을 가진 클래스를 BeanDefinition으로 등록.
 *
 * <p>Spring 원본: {@code ClassPathBeanDefinitionScanner}.
 */
public class ClassPathBeanDefinitionScanner {

    private final BeanDefinitionRegistry registry;
    private final BeanNameGenerator nameGenerator;

    public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry) {
        this(registry, new AnnotationBeanNameGenerator());
    }

    public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry, BeanNameGenerator gen) {
        this.registry = registry;
        this.nameGenerator = gen;
    }

    public int scan(String... basePackages) {
        int count = 0;
        for (String pkg : basePackages) {
            for (Class<?> clazz : ClassPathScanner.scan(pkg)) {
                if (clazz.isInterface() || clazz.isAnnotation()) continue;
                if (!AnnotationUtils.isAnnotated(clazz, Component.class)) continue;
                BeanDefinition bd = new BeanDefinition(clazz);
                applyScope(bd, clazz);
                applyLazy(bd, clazz);
                applyPrimary(bd, clazz);
                registry.registerBeanDefinition(nameGenerator.generate(clazz), bd);
                count++;
            }
        }
        return count;
    }

    private void applyScope(BeanDefinition bd, Class<?> c) {
        com.choisk.sfs.context.annotation.Scope s =
                c.getAnnotation(com.choisk.sfs.context.annotation.Scope.class);
        if (s != null) bd.setScope(com.choisk.sfs.beans.Scope.byName(s.value()));
    }

    private void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazyInit(l.value());
    }

    private void applyPrimary(BeanDefinition bd, Class<?> c) {
        if (c.isAnnotationPresent(Primary.class)) bd.setPrimary(true);
    }
}
```

- [ ] **Step 5: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests ClassPathBeanDefinitionScannerTest
```
예상: 2/2 PASS.

- [ ] **Step 6: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): ClassPathBeanDefinitionScanner — 메타 인식 패키지 스캔 + scope/lazy/primary 적용"
```

---

### Task 17: `@Lazy` 빈은 `preInstantiateSingletons`에서 skip (TDD)

> **TDD 적용 여부:** 적용 — preInstantiate 분기 + 첫 getBean 시 생성 동작이 본질.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/LazyInitializationTest.java`
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java` (또는 `preInstantiateSingletons` 위치)

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LazyInitializationTest {

    static final AtomicInteger ctorCount = new AtomicInteger(0);

    static class Eager { Eager() { ctorCount.incrementAndGet(); } }
    static class LazyOne { LazyOne() { ctorCount.incrementAndGet(); } }

    @Test
    void lazyBeanIsNotInstantiatedAtPreInstantiate() {
        ctorCount.set(0);
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("eager", new BeanDefinition(Eager.class));

        BeanDefinition lazyDef = new BeanDefinition(LazyOne.class);
        lazyDef.setLazyInit(true);
        bf.registerBeanDefinition("lazyOne", lazyDef);

        bf.preInstantiateSingletons();

        assertThat(ctorCount.get()).isEqualTo(1);  // eager만 생성

        bf.getBean("lazyOne");
        assertThat(ctorCount.get()).isEqualTo(2);  // 첫 getBean 시 생성
    }
}
```

- [ ] **Step 2: 테스트 실행 (FAIL 확인 — `preInstantiateSingletons`가 lazy를 무시하지 않을 가능성 큼)**

```bash
./gradlew :sfs-context:test --tests LazyInitializationTest
```
예상: FAIL (eager + lazy 모두 생성되어 ctorCount=2가 preInstantiate 직후에 나옴).

- [ ] **Step 3: `DefaultListableBeanFactory.preInstantiateSingletons()` 수정 — sfs-beans**

해당 메서드의 싱글톤 생성 루프에 lazy 체크 추가:

```java
public void preInstantiateSingletons() {
    for (String name : getBeanDefinitionNames()) {
        BeanDefinition def = getBeanDefinition(name);
        if (def == null) continue;
        if (!def.isSingleton()) continue;
        if (def.isLazyInit()) continue;  // ← 추가: lazy 빈은 첫 getBean 시 생성
        getBean(name);
    }
}
```

> 실제 코드 위치는 1A에서 정의된 곳. 메서드 본문이 다르면 동일 의도로 lazy 분기만 끼워넣음.

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests LazyInitializationTest
./gradlew :sfs-beans:test  # 회귀
```
예상: 1/1 PASS + sfs-beans 전체 PASS.

- [ ] **Step 5: 커밋 (sfs-beans 변경 + 통합 테스트 함께)**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java
git commit -m "feat(sfs-beans): preInstantiateSingletons에서 @Lazy BeanDefinition skip"

git add sfs-context/src/test/java/com/choisk/sfs/context/integration/LazyInitializationTest.java
git commit -m "test(sfs-context): @Lazy 빈은 preInstantiate에서 skip되고 첫 getBean 시 생성"
```

---

## 섹션 H: AnnotationConfigApplicationContext (Task 18)

### Task 18: `AnnotationConfigApplicationContext` (두 진입점)

> **TDD 적용 여부:** 제외 — reader/scanner 위임 + 진입점만. 통합 테스트로 검증.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigApplicationContext.java`

- [ ] **Step 1: 구현**

```java
package com.choisk.sfs.context.support;

/**
 * 애노테이션 기반 컨테이너 진입점. 두 가지 사용 패턴 지원:
 * <ul>
 *   <li>{@code new AnnotationConfigApplicationContext(MyConfig.class)} — 명시 등록 + refresh</li>
 *   <li>{@code new AnnotationConfigApplicationContext("com.example")} — 패키지 스캔 + refresh</li>
 * </ul>
 *
 * <p>Spring 원본: {@code AnnotationConfigApplicationContext}.
 */
public class AnnotationConfigApplicationContext extends GenericApplicationContext {

    private final AnnotatedBeanDefinitionReader reader;
    private final ClassPathBeanDefinitionScanner scanner;

    public AnnotationConfigApplicationContext() {
        this.reader = new AnnotatedBeanDefinitionReader(getBeanFactory());
        this.scanner = new ClassPathBeanDefinitionScanner(getBeanFactory());
        // 1B-β 첫 Task에서 처리기 3종 자동 등록 추가:
        //   addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
        //   getBeanFactory().addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(getBeanFactory()));
        //   getBeanFactory().addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(getBeanFactory()));
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

    public void register(Class<?>... componentClasses) {
        reader.register(componentClasses);
    }

    public int scan(String... basePackages) {
        return scanner.scan(basePackages);
    }
}
```

> **참고:** `getBeanFactory()`는 `ConfigurableListableBeanFactory`를 반환하고, 섹션 A2-1에서 CLBF가 `BeanDefinitionRegistry`를 상속하도록 변경되었으므로 reader/scanner에 그대로 전달 가능. 별도 헬퍼 불필요.

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava :sfs-context:compileTestJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): AnnotationConfigApplicationContext — register/scan 두 진입점"
```

---

## 섹션 I: 통합 테스트 (Task 19~20)

### Task 19: 컴포넌트 스캔 End-to-end 통합 테스트

> **TDD 적용 여부:** 적용 — 스캔→registration→getBean까지 전체 흐름.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/ComponentScanIntegrationTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.samples.basic.MetaTaggedService;
import com.choisk.sfs.context.samples.basic.SimpleService;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanIntegrationTest {

    @Test
    void scanRegistersAndGetBeanWorks() {
        try (var ctx = new AnnotationConfigApplicationContext("com.choisk.sfs.context.samples.basic")) {
            assertThat(ctx.containsBean("simpleService")).isTrue();
            assertThat(ctx.containsBean("metaTaggedService")).isTrue();
            assertThat(ctx.containsBean("plainPojo")).isFalse();
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
            assertThat(ctx.getBean(MetaTaggedService.class)).isNotNull();
        }
    }

    @Test
    void registerEntrypointAlsoWorks() {
        try (var ctx = new AnnotationConfigApplicationContext(SimpleService.class)) {
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
        }
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew :sfs-context:test --tests ComponentScanIntegrationTest
```
예상: 2/2 PASS.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): AnnotationConfigApplicationContext 두 진입점 End-to-end 통합 검증"
```

---

### Task 20: refresh 라이프사이클 통합 테스트 (실패 시나리오 포함 회귀)

> **TDD 적용 여부:** 적용 — Task 11/12 외 시나리오 보강.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/RefreshLifecycleIntegrationTest.java`

- [ ] **Step 1: 테스트 작성**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.context.support.GenericApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshLifecycleIntegrationTest {

    static class Foo {}

    @Test
    void emptyContextRefreshSucceeds() {
        var ctx = new GenericApplicationContext();
        ctx.refresh();  // BFPP/BPP 컬렉션 비어있어도 5/6단계 정상 통과
        assertThat(ctx.isActive()).isTrue();
        ctx.close();
    }

    @Test
    void refreshTwiceThrowsAndKeepsState() {
        var ctx = new GenericApplicationContext();
        ctx.registerBeanDefinition("foo", new BeanDefinition(Foo.class));
        ctx.refresh();
        assertThatThrownBy(ctx::refresh).isInstanceOf(IllegalStateException.class);
        assertThat(ctx.isActive()).isTrue();  // 두 번째 refresh 실패가 첫 성공 상태를 깨면 안 됨
        ctx.close();
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew :sfs-context:test --tests RefreshLifecycleIntegrationTest
```
예상: 2/2 PASS.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): refresh 라이프사이클 통합 (빈 컨텍스트 + 재호출 가드)"
```

---

## 섹션 J: 마감 (Task 21~22)

### Task 21: Plan 1B-α 회귀 테스트 + 빌드 검증

> **TDD 적용 여부:** 제외 — 검증 게이트.

**Files:** 없음 (검증만).

- [ ] **Step 1: 전체 모듈 테스트**

```bash
./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test
```
예상:
- `sfs-core`: AnnotationUtilsTest 5건 추가 (이전 + 5)
- `sfs-beans`: 64 PASS (1A) — preInstantiateSingletons lazy 분기 추가의 회귀 없음
- `sfs-context`: 약 25~30 PASS

- [ ] **Step 2: 전체 빌드**

```bash
./gradlew build
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 3: 검증 결과 기록 (Plan 문서 하단 실행 기록 블록 추가)**

테스트 카운트와 빌드 결과를 본 plan 파일의 실행 기록으로 추가. 별도 커밋 없음 (Step 4의 README 커밋과 함께).

---

### Task 22: `sfs-context/README.md` 업데이트 + Plan 1B-α DoD 체크

> **TDD 적용 여부:** 제외 — 문서.

**Files:**
- Modify: `sfs-context/README.md`
- Modify: `docs/superpowers/plans/2026-04-23-phase-1b-alpha-context-infra.md` (DoD 체크박스 모두 [x])

- [ ] **Step 1: README "주요 통합 테스트" 섹션 추가**

`sfs-context/README.md` 끝에 다음 추가:

````markdown
## 주요 통합 테스트 (1B-α 시점)

- `support/AbstractApplicationContextTest` — refresh 8단계 순서 + single-shot
- `support/GenericApplicationContextTest` — refresh + 외부 BF
- `support/AnnotatedBeanDefinitionReaderTest` — @Scope/@Lazy/@Primary 추출
- `support/AnnotationBeanNameGeneratorTest` — 명시 value 우선
- `support/ClassPathBeanDefinitionScannerTest` — 메타-인식 스캔
- `integration/ComponentScanIntegrationTest` — End-to-end 두 진입점
- `integration/RefreshLifecycleIntegrationTest` — 빈 컨텍스트 + 재호출 가드
- `integration/RefreshFailureCleanupTest` — 5단계 throw 시 destroy 자동 호출
- `integration/CloseAndShutdownHookTest` — close idempotent + JVM hook
- `integration/LazyInitializationTest` — preInstantiate skip + 첫 getBean 시 생성
- `annotation/StereotypeMetaTest` — @Service/@Repository/@Controller/@Configuration 메타 인식
````

- [ ] **Step 2: 본 plan 문서의 DoD 섹션(아래) 모든 체크박스를 `[x]`로 갱신**

(아래 "Plan 1B-α DoD 최종 체크리스트" 섹션 14항목을 모두 `[x]` 처리)

- [ ] **Step 3: 최종 커밋**

```bash
git add sfs-context/README.md docs/superpowers/plans/2026-04-23-phase-1b-alpha-context-infra.md
git commit -m "docs: Plan 1B-α 마감 — sfs-context README 통합 테스트 목록 + DoD 체크 완료"
```

---

## 🎯 Plan 1B-α Definition of Done — 최종 체크리스트

**기능적 DoD (12항목, spec 7.1과 1:1 매칭):**

- [ ] 1. `AnnotationConfigApplicationContext("com.x.pkg")` 사용 시 `@Component` 클래스가 BeanDefinition으로 등록된다 (Task 19)
- [ ] 2. `AnnotationConfigApplicationContext(MyConfig.class)` 사용 시 명시 등록 + refresh가 동작한다 (Task 19)
- [ ] 3. `@Service`/`@Repository`/`@Controller`는 `@Component` 메타-인식으로 자동 등록된다 (Task 6, 19)
- [ ] 4. `refresh()`는 8단계를 순서대로 실행한다 (Task 10)
- [ ] 5. `refresh()` 5/6단계는 BFPP/BPP 컬렉션이 비어 있어도 정상 통과한다 (Task 20)
- [ ] 6. `refresh()` 도중 예외 발생 시 `destroyBeans()` + `cancelRefresh()`가 자동 호출되어 부분 생성 빈이 정리된다 (Task 11)
- [ ] 7. `close()`는 idempotent하다 (Task 12)
- [ ] 8. `registerShutdownHook()` 후 JVM 종료 시 destroy 콜백이 실행된다 (Task 12 — JVM 실제 종료는 직접 실행 어려움. idempotent 등록만 단위 테스트)
- [ ] 9. `@Scope("prototype")` 클래스는 prototype BeanDefinition으로 등록된다 (Task 15)
- [ ] 10. `@Lazy` 클래스는 `preInstantiateSingletons()`에서 skip되고, 첫 `getBean()` 시 생성된다 (Task 17)
- [ ] 11. `@Component("custom")` 명시 시 그 이름이 `BeanNameGenerator` 기본값을 override한다 (Task 14)
- [ ] 12. `AnnotationUtils.isAnnotated`는 메타-애노테이션을 재귀 탐색하며 사이클 방지가 동작한다 (Task 4)

**품질 DoD:**

- [ ] 13. `./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test` 모두 PASS (Task 21)
- [ ] 14. `sfs-context` 모듈 README 작성 (Task 3, 22)

---

## ▶ Plan 1B-α 완료 후 다음 단계

1. **main 머지 게이트:** `feat/phase1b-context` 브랜치에서 main으로 머지 (1A 패턴 동일하게 `--no-ff`).
2. **Plan 1B-β 작성:** `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` 작성. 범위:
   - `ConfigurationClassPostProcessor` (BFPP) + byte-buddy enhance
   - `AutowiredAnnotationBeanPostProcessor` (IABPP) + 컬렉션 주입 + `required=false`
   - `CommonAnnotationBeanPostProcessor` (BPP) + `@PostConstruct`/`@PreDestroy`
   - 라이프사이클 통합 테스트 (5단계 콜백 순서 확장)
3. Plan 1B-β 실행 → main 머지 → Phase 1 종료 (spec line 418~419 DoD 만족)

---

## 부록 — sfs-beans 1A 완료 시점 실사 결과 (2026-04-24)

본 plan이 의존하는 sfs-beans API를 1A 완료 시점(커밋 `8c8664f`) 소스에서 실제 확인한 결과:

| 항목 | 1A 시점 상태 | 본 plan 대응 |
|---|---|---|
| `BeanDefinition.setLazyInit(boolean)` / `isLazyInit()` | ✅ 존재 (`setLazy`/`isLazy` 아님) | Task 15~17 코드에서 `setLazyInit`/`isLazyInit` 사용 |
| `BeanDefinition.setPrimary(boolean)` / `isPrimary()` | ✅ 존재 | 그대로 사용 |
| `BeanDefinition.setScope(Scope)` (sealed 타입) | ✅ 존재 (String 아님) | `com.choisk.sfs.beans.Scope.byName(String)`으로 어댑트 |
| `ConfigurableListableBeanFactory.preInstantiateSingletons()` | ✅ 노출 | 그대로 사용 |
| `ConfigurableBeanFactory.destroySingletons()` (CLBF의 조상) | ✅ 노출 | 그대로 사용 |
| `BeanDefinitionRegistry` 인터페이스 | ❌ **미존재** | **섹션 A2-1에서 신설** |
| `registerSingleton`이 `DisposableBean` 자동 감지 | ❌ **미보강** | **섹션 A2-2에서 TDD로 보강** |

> **결론:** 사전 분석으로 발견된 2건을 섹션 A2에 선결 Task로 승격했으므로, 본 plan 실행 중 추가 "깜짝 보강"이 필요할 가능성은 낮음. 그럼에도 진행 중 누락을 발견하면 별도 커밋(`feat(sfs-beans): <항목> 노출`)으로 분리하고 본 plan에 `> **실행 기록:**` 블록으로 기록.
