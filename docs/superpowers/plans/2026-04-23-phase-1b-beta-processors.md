# Phase 1B-β — sfs-context 처리기 3종 (학습용 최소 스코프, Phase 1 종료)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@Autowired` 필드 주입 / `@PostConstruct` / `@PreDestroy` / `@Bean` 기본 등록(매개변수 의존성 자동 해석 포함)이 동작하는 처리기 3종을 추가하여 Phase 1을 마감한다. **학습용 최소 스코프로 의도적으로 축소**했으며, byte-buddy enhance / 컬렉션 주입 / `@Primary`·`@Qualifier` 폴백 / 세터·생성자 주입 / ASM 사전 필터는 보류한다.

**Architecture:** `ConfigurationClassPostProcessor` (BFPP, `@Bean` 메서드 → BeanDefinition만, enhance 없이), `AutowiredAnnotationBeanPostProcessor` (IABPP, 필드 주입만), `CommonAnnotationBeanPostProcessor` (BPP, `@PostConstruct`/`@PreDestroy`) 3종 신설. `sfs-beans`에 `BeanDefinition.factoryBeanName/factoryMethodName` 활용 + `DependencyDescriptor` + `DefaultListableBeanFactory.resolveDependency` (단일 매칭 + `required=false` 분기만) 보강. **`@Bean` factoryMethod의 매개변수는 컨테이너가 `resolveDependency`로 자동 주입**한다 (enhance 없이도 inter-bean reference의 가장 흔한 형태가 동작).

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL), JUnit 5, AssertJ. byte-buddy는 카탈로그 등록만 유지 (1B-α에서 추가됨, 본 plan에서 사용하지 않음).

**Selected Spec:** `docs/superpowers/specs/2026-04-23-phase-1b-context-design.md` (단, Section 5의 byte-buddy enhance / 컬렉션 주입 / @Primary 폴백은 본 축소판 plan 범위 외)

**선행 조건:** Plan 1B-α 완료 (main 브랜치 `0f1f47f` 머지 시점 + sfs-core 25 + sfs-beans 48 + sfs-context 31 = 104 테스트 PASS) + 품질 게이트 통과 (1B-α plan 하단 게이트 기록 블록 참조)

**End state:** 아래 시나리오가 동작하는 컨테이너. Phase 1 종료 시점.

```java
// Plan 1B-β 종료 시점 통과해야 하는 시나리오
@Configuration
class AppConfig {
    @Bean public Repo repo() { return new Repo(); }
    @Bean public Service service(Repo repo) { return new Service(repo); }  // ← 매개변수 자동 주입
}

@Component class Worker {
    @Autowired Service service;
    @PostConstruct void init() { /* 호출됨 */ }
    @PreDestroy void cleanup() { /* close() 시 호출됨 */ }
}

var ctx = new AnnotationConfigApplicationContext(AppConfig.class, Worker.class);
Worker w = ctx.getBean(Worker.class);
assertThat(w.service).isNotNull();
assertThat(w.service.repo()).isSameAs(ctx.getBean(Repo.class));  // 컨테이너 라우팅
ctx.close();  // @PreDestroy 호출 + shutdown hook 정리
```

**스코프 결정 메모 (2026-04-25):** 학습 프로젝트 규모가 점점 커지는 우려에 따라, design doc Section 5의 1B-β 전체 범위(30 task)를 **13 task 최소 스코프**로 축소. 보류 항목은 plan 끝 "보류 — 추후 학습" 섹션에 명시. deep version은 git history `75842e5`에 보존 (1417 LOC).

**조정 메모 (2026-04-25):** 초안(12 task)에서 **factoryMethod 인자 자동 주입**을 추가하여 13 task로 보강. 이유: 인자 없는 `@Bean` 메서드만 지원하면 학습 시연의 가장 흔한 패턴(`@Bean Service service(Repo repo)`)이 동작하지 않음. byte-buddy enhance가 필요한 건 *@Bean 본문에서 다른 @Bean 메서드를 직접 호출*하는 경우뿐이고, 매개변수 라우팅은 컨테이너가 `resolveDependency`로 처리 가능. 추가 비용은 C1 task의 인자 해석 +5줄 / 테스트 +1개 수준이며, 학습 가치 대비 매우 저렴. 동시에 섹션 순서를 재배치하여 D1(`AnnotationConfigUtils`)을 H1으로 이동 — 처리기 3종 클래스가 실재하는 상태에서 자동 등록 헬퍼를 만들게 되어 "빈 껍데기 우회" 마찰이 사라짐.

**1B-α와 다른 운영 차이:**
- `sfs-beans`에 의존성 해석 도입 (`resolveDependency(DependencyDescriptor)` 신설)
- 처리기 3종이 `AnnotationConfigApplicationContext` 생성자에서 자동 등록 → 1B-α의 빈 hook(`registerBeanPostProcessors`/`invokeBeanFactoryPostProcessors`)이 본 plan에서 *실제로* 동작 시작
- 1B-α 품질 게이트 simplify 이월 4건 중 **B3 (`getBeanFactory()` 경로 통일) + B4 (`registerSingleton` atomic)**만 본 plan에서 처리. **B1 (ASM 사전 필터) + B2 (`ClassUtils.forName`)은 보류** (Configuration enhance 자체를 단순화하므로 필요성이 줄어듦)

---

## 실행 시 발견된 구조 차이 (2026-04-25, 누적 발견)

plan 본문은 학습 가독성을 위해 일부 import 경로 / 파일 위치 / 시그니처를 단순화했지만, 실제 1B-α 결과물의 구조와 아래 차이가 있다. 후속 task의 Sonnet 위임 시 plan 코드 블록을 *그대로* 복사하지 말고 본 매핑을 적용하라. (위임 프롬프트에 "plan 본문 + 본 블록을 함께 참조하라" 한 줄을 추가하는 형태로 활용한다.)

| plan 표기 | 실제 코드 (1A/1B-α 산출물) | 영향 받는 task | 상태 |
|---|---|---|---|
| `com.choisk.sfs.beans.NoSuchBeanDefinitionException` | `com.choisk.sfs.core.NoSuchBeanDefinitionException` | B2, F1, 그 외 의존 해석 분기 호출처 | B2 반영 완료 |
| `sfs-beans/.../beans/DefaultListableBeanFactory.java` | `sfs-beans/.../beans/support/DefaultListableBeanFactory.java` (`support/` 하위) | B2, C1, F1, H1 등 modify 대상 (단 `DefaultSingletonBeanRegistry`는 `beans/` 직속 — B3에서 확인) | B2 반영 완료 |
| `getBeansOfType()` 단독 사용 | `BeanDefinition` 기반 빈과 `registerSingleton` 직접 등록 빈이 분리 → 합산 헬퍼 필요 | B2에서 `resolveBeansOfType` private 헬퍼 신설로 해결. F1의 `resolveDependency` 호출은 본 헬퍼 경로를 그대로 사용하므로 추가 작업 불요 | B2 반영 완료 |
| C1 plan 표기 `instantiateViaConstructor` | 실제 메서드명 `instantiateBean` | C1 | C1 반영 완료 |
| `@Bean` 애노테이션의 `value()` 단일 String | 실제 시그니처 `name() String[]` (다중 alias 지원, Spring 본가와 동일) | E1, 그 외 `@Bean` 메서드 추출처 | E1 반영 완료 (`name()[0]` 비어있지 않으면 사용, 아니면 메서드명) |
| `getBeanDefinitionNames()`가 `List<String>` 반환 (plan은 `.toArray(new String[0])` 사용) | 실제 반환 타입 `String[]` → `.clone()` 한 번이면 컬렉션 변경 방지 충분 | E1 등 BD 순회 task | E1 반영 완료 |

> **Sonnet 위임 가이드:** 본 plan의 modify 대상 코드 블록을 보낼 때, "이 plan 본문은 학습 가독성을 위해 단순화되어 있으니, 실제 시그니처/import는 grep으로 확인하라. 특히 위 표의 매핑을 우선 적용하라"는 한 줄을 항상 포함할 것. 이렇게 하면 위임이 자기 보정(self-correcting)된다.

---

## 섹션 구조 (Task 한 줄 요약)

| 섹션 | 범위 | Task | TDD |
|---|---|---|---|
| **A** | `sfs-beans` 선결 — `BeanDefinition` factoryMethod 필드 회귀 | A1 | 적용 |
| **B** | `sfs-beans` 의존 해석 단순판 + simplify B4 | B1~B3 | 모두 적용 |
| **C** | `sfs-beans` `doCreateBean` factoryMethod 분기 (인자 자동 주입 포함) | C1 | 적용 |
| **D** | `sfs-context` 애노테이션 3종 정의 | D1 | 제외 |
| **E** | `ConfigurationClassPostProcessor` 단순판 (`@Bean` → BD, enhance 없이) | E1 | 적용 |
| **F** | `AutowiredAnnotationBeanPostProcessor` — 필드 주입만 | F1 | 적용 |
| **G** | `CommonAnnotationBeanPostProcessor` — `@PostConstruct` + `@PreDestroy` | G1~G2 | 모두 적용 |
| **H** | `AnnotationConfigUtils` 자동 등록(simplify B3) + 통합 시연 + 마감 | H1~H3 | 적용 (H1, H2) + 제외 (H3) |

총 **13 Task**. 누적 테스트 카운트 예상: 1B-α 완료 시점 104 → 1B-β 완료 시점 **~118~122** (1B-β +14~18).

**의존 흐름:** `B1 → B2 → C1`(sfs-beans 상향 빌드) → `D1`(애노테이션) → `E1, F1, G1, G2`(처리기 본문) → `H1`(자동 등록) → `H2`(통합) → `H3`(마감).

---

## 섹션 A: `sfs-beans` 선결 — factoryMethod 필드 회귀 (Task A1)

### Task A1: `BeanDefinition` factoryBeanName/factoryMethodName 회귀 검증

> **TDD 적용 여부:** 적용 — 1A 시점에 이미 추가된 필드(`cce97e8`)가 1B-β에서도 그대로 동작함을 round-trip으로 회귀 검증. 사전 분석에서 필드 실재 확인됨.

**Files:**
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/BeanDefinitionFactoryMethodTest.java`

- [x] **Step 1: 회귀 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BeanDefinitionFactoryMethodTest {
    @Test
    void factoryMethodFieldsRoundTrip() {
        BeanDefinition bd = new BeanDefinition(MyConfig.class);
        bd.setFactoryBeanName("myConfig");
        bd.setFactoryMethodName("repo");
        assertThat(bd.getFactoryBeanName()).isEqualTo("myConfig");
        assertThat(bd.getFactoryMethodName()).isEqualTo("repo");
    }

    @Test
    void factoryMethodFieldsDefaultNull() {
        BeanDefinition bd = new BeanDefinition(MyConfig.class);
        assertThat(bd.getFactoryBeanName()).isNull();
        assertThat(bd.getFactoryMethodName()).isNull();
    }

    static class MyConfig {}
}
```

- [x] **Step 2: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.BeanDefinitionFactoryMethodTest"
```
예상: PASS 2건 (1A 시점 필드가 그대로 살아있음).

- [x] **Step 3: 커밋**

```bash
git add sfs-beans/src/test/java/com/choisk/sfs/beans/BeanDefinitionFactoryMethodTest.java
git commit -m "test(sfs-beans): BeanDefinition factoryMethod 필드 회귀 검증 (1A 보강 유지 확인)"
```

> **실행 기록 (2026-04-25):** 커밋 `09cfa22` — PASS 2/2.

---

## 섹션 B: `sfs-beans` 의존 해석 단순판 (Task B1~B3)

### Task B1: `DependencyDescriptor` 신설 (단순판)

> **TDD 적용 여부:** 적용 — `required` 플래그 + 타입 정보 캡슐화가 본질.

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/DependencyDescriptorTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DependencyDescriptorTest {
    @Test
    void capturesTypeAndRequired() {
        DependencyDescriptor d = new DependencyDescriptor(String.class, true, "myField");
        assertThat(d.getDependencyType()).isEqualTo(String.class);
        assertThat(d.isRequired()).isTrue();
        assertThat(d.getDependencyName()).isEqualTo("myField");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.DependencyDescriptorTest"
```

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.beans;

public class DependencyDescriptor {
    private final Class<?> dependencyType;
    private final boolean required;
    private final String dependencyName;

    public DependencyDescriptor(Class<?> dependencyType, boolean required, String dependencyName) {
        this.dependencyType = dependencyType;
        this.required = required;
        this.dependencyName = dependencyName;
    }

    public Class<?> getDependencyType() { return dependencyType; }
    public boolean isRequired() { return required; }
    public String getDependencyName() { return dependencyName; }
}
```

> **축소판 메모:** 제네릭 추출(`getGenericType` / `ResolvableType`)은 보류 (List/Map 컬렉션 주입을 안 하므로 불필요).

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.DependencyDescriptorTest"
```

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/DependencyDescriptor.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/DependencyDescriptorTest.java
git commit -m "feat(sfs-beans): DependencyDescriptor 신설 (단순판 — type+required+name)"
```

> **실행 기록 (2026-04-25):** 커밋 `0a46c0e` — PASS 1/1 (DependencyDescriptorTest). 회귀 전체 PASS.

---

### Task B2: `DefaultListableBeanFactory.resolveDependency` 신설 (단순판 — 단일 매칭 + `required=false`만)

> **TDD 적용 여부:** 적용 — 단일/0매칭/required=false 3분기.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/ResolveDependencyTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolveDependencyTest {
    static class Repo {}

    @Test
    void resolvesSingleMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("repo", new Repo());
        Object result = bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null);
        assertThat(result).isInstanceOf(Repo.class);
    }

    @Test
    void requiredFalseReturnsNullWhenNoMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        Object result = bf.resolveDependency(new DependencyDescriptor(Repo.class, false, "repo"), null);
        assertThat(result).isNull();
    }

    @Test
    void requiredTrueThrowsWhenNoMatch() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        assertThatThrownBy(() ->
            bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    void multipleCandidatesThrowExplicitlyWithLearningMessage() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("a", new Repo());
        bf.registerSingleton("b", new Repo());
        assertThatThrownBy(() ->
            bf.resolveDependency(new DependencyDescriptor(Repo.class, true, "repo"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@Primary")
            .hasMessageContaining("@Qualifier");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.ResolveDependencyTest"
```

- [x] **Step 3: 구현**

```java
// DefaultListableBeanFactory에 추가
public Object resolveDependency(DependencyDescriptor desc, String requestingBeanName) {
    java.util.Map<String, Object> matches = getBeansOfType(desc.getDependencyType());
    if (matches.isEmpty()) {
        if (desc.isRequired()) {
            throw new NoSuchBeanDefinitionException(
                "No bean of type " + desc.getDependencyType().getName() + " found for " + desc.getDependencyName());
        }
        return null;
    }
    if (matches.size() == 1) {
        return matches.values().iterator().next();
    }
    // 다수 후보 폴백(@Primary/@Qualifier/이름)은 본 축소판 보류 — 발견 시 명시적 예외로 *왜 정책이 필요한지* 학습
    throw new IllegalStateException(
        "Multiple beans of type " + desc.getDependencyType().getName() + " found: " + matches.keySet()
        + ". This learning-scope plan does not implement multi-candidate resolution. "
        + "Real Spring resolves this with @Primary → @Qualifier → field/parameter name fallback.");
}
```

> **축소판 메모:** 다수 후보가 발생하면 `IllegalStateException`으로 명확히 차단. 메시지에 `@Primary`/`@Qualifier` 폴백 정책이 *왜* 필요한지를 명시하여 학습 시점에 의도가 전달되도록 설계.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.ResolveDependencyTest"
./gradlew :sfs-beans:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultListableBeanFactory.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/ResolveDependencyTest.java
git commit -m "feat(sfs-beans): resolveDependency 단순판 (단일 매칭 + required=false. 다수 후보는 명시적 예외)"
```

> **실행 기록 (2026-04-25):** 커밋 `f136236` — PASS 4/4 (ResolveDependencyTest). 회귀 전체 PASS.
>
> **편차 기록:**
> - `NoSuchBeanDefinitionException`은 `com.choisk.sfs.core` 패키지 (plan의 `com.choisk.sfs.beans`와 다름) → import를 `com.choisk.sfs.core.NoSuchBeanDefinitionException`으로 수정.
> - `DefaultListableBeanFactory`는 `com.choisk.sfs.beans.support` 패키지 → 테스트에 `import com.choisk.sfs.beans.support.DefaultListableBeanFactory` 추가.
> - `getBeansOfType`은 BeanDefinition 기반만 검색 → `registerSingleton`으로 직접 등록된 싱글톤을 찾지 못함. `resolveDependency` 내부에 `resolveBeansOfType` 헬퍼를 추가하여 `getSingletonNames()` + `getSingleton()` 로 직접 싱글톤도 포함. 4분기 동작은 plan 그대로 유지.

---

### Task B3: `registerSingleton`의 DisposableBean 감지 atomic화 *(simplify 이월 B4)*

> **TDD 적용 여부:** 적용 — 동시성 안전성. 현재는 `singletonObjects.put`과 `disposableBeans.put`이 분리된 critical section.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/RegisterSingletonAtomicTest.java`

- [x] **Step 1: 회귀 테스트 작성**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RegisterSingletonAtomicTest {
    static class TestDisposable implements DisposableBean {
        boolean destroyed = false;
        @Override public void destroy() { destroyed = true; }
    }

    @Test
    void disposableRegisteredAtomically() {
        DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
        TestDisposable bean = new TestDisposable();
        registry.registerSingleton("d", bean);
        registry.destroySingletons();
        assertThat(bean.destroyed)
            .as("registerSingleton 직후 destroySingletons 호출 시 destroy가 반드시 실행되어야 함")
            .isTrue();
    }
}
```

- [x] **Step 2: 테스트 실행 (현재 PASS 가능 — atomic 보장 명문화)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.RegisterSingletonAtomicTest"
```

- [x] **Step 3: 구현 — DisposableBean 감지를 `singletonLock` 안으로 이동**

```java
// DefaultSingletonBeanRegistry.registerSingleton 변경
public void registerSingleton(String beanName, Object singletonObject) {
    synchronized (singletonLock) {
        singletonObjects.put(beanName, singletonObject);
        earlySingletonObjects.remove(beanName);
        singletonFactories.remove(beanName);
        registeredSingletons.add(beanName);

        // atomic — 같은 lock 안에서 disposable 등록
        // singletonObjects.put과 disposableBeans.put이 분리되면 destroySingletons 사이에 race 가능
        if (singletonObject instanceof DisposableBean disposable) {
            disposableBeans.put(beanName, () -> {
                try { disposable.destroy(); }
                catch (Exception e) { System.err.println("destroy failed for " + beanName + ": " + e); }
            });
        }
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인) + 회귀**

```bash
./gradlew :sfs-beans:test
```
예상: 전체 sfs-beans PASS (DisposableBean 회귀 4건 포함).

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/RegisterSingletonAtomicTest.java
git commit -m "fix(sfs-beans): registerSingleton의 DisposableBean 감지 atomic화 (simplify 이월 B4)"
```

---

## 섹션 C: `sfs-beans` `doCreateBean` factoryMethod 분기 (Task C1)

### Task C1: `AbstractAutowireCapableBeanFactory.doCreateBean`에 factoryMethod 분기 추가 — 인자 자동 주입 포함

> **TDD 적용 여부:** 적용 — 인스턴스화 경로의 본질적 분기 + **매개변수 의존성 해석**이 핵심. constructor 경로 대신 `factoryBean.factoryMethod(...resolveDependency...)` 호출.

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/AbstractAutowireCapableBeanFactory.java`
- Test: `sfs-beans/src/test/java/com/choisk/sfs/beans/CreateBeanFactoryMethodTest.java`

- [x] **Step 1: 실패 테스트 작성 — no-arg + 인자 있는 케이스 둘 다**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CreateBeanFactoryMethodTest {

    static class Repo {
        String tag = "repo";
    }

    static class MyConfig {
        public String greeting() { return "hello"; }
        public Repo repo() { return new Repo(); }
        public String describe(Repo r) { return "repo-tag=" + r.tag; }  // ← 매개변수 있음
    }

    @Test
    void factoryMethodNoArgCreatesBean() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("myConfig", new BeanDefinition(MyConfig.class));

        BeanDefinition bd = new BeanDefinition(String.class);
        bd.setFactoryBeanName("myConfig");
        bd.setFactoryMethodName("greeting");
        bf.registerBeanDefinition("greeting", bd);

        assertThat(bf.getBean("greeting")).isEqualTo("hello");
    }

    @Test
    void factoryMethodWithArgsResolvesDependencies() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("myConfig", new BeanDefinition(MyConfig.class));

        // repo 빈 등록 (factoryMethod로)
        BeanDefinition repoBd = new BeanDefinition(Repo.class);
        repoBd.setFactoryBeanName("myConfig");
        repoBd.setFactoryMethodName("repo");
        bf.registerBeanDefinition("repo", repoBd);

        // describe 빈 등록 (Repo를 매개변수로 받음)
        BeanDefinition descBd = new BeanDefinition(String.class);
        descBd.setFactoryBeanName("myConfig");
        descBd.setFactoryMethodName("describe");
        bf.registerBeanDefinition("describe", descBd);

        assertThat(bf.getBean("describe")).isEqualTo("repo-tag=repo");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.CreateBeanFactoryMethodTest"
```
예상: FAIL — factoryMethod 분기 미구현으로 `String` 클래스를 생성자로 인스턴스화하려 시도.

- [x] **Step 3: 구현 — `doCreateBean`에 factoryMethod 분기 + 인자 해석 추가**

```java
// AbstractAutowireCapableBeanFactory.doCreateBean(String name, BeanDefinition bd)
// 인스턴스화 시점에 factoryMethod 분기 우선 검사

if (bd.getFactoryMethodName() != null) {
    Object factoryBean = getBean(bd.getFactoryBeanName());
    java.lang.reflect.Method m = findFactoryMethod(factoryBean.getClass(), bd.getFactoryMethodName());
    if (m == null) {
        throw new BeanCreationException(name, "factoryMethod not found: " + bd.getFactoryMethodName());
    }
    Object[] args = resolveFactoryMethodArguments(m, name);
    try {
        m.setAccessible(true);
        return m.invoke(factoryBean, args);
    } catch (ReflectiveOperationException e) {
        throw new BeanCreationException(name, "factoryMethod invocation failed", e);
    }
}
// 기존 constructor 경로 그대로
return instantiateViaConstructor(bd);
```

```java
// 헬퍼 — 동일 이름 메서드는 하나만 가정 (학습용 단순화)
private java.lang.reflect.Method findFactoryMethod(Class<?> type, String methodName) {
    for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
        if (m.getName().equals(methodName)) return m;  // 첫 매치
    }
    return null;
}

// 헬퍼 — 매개변수마다 resolveDependency로 빈을 찾아 인자 배열 생성
private Object[] resolveFactoryMethodArguments(java.lang.reflect.Method m, String requestingBeanName) {
    Class<?>[] paramTypes = m.getParameterTypes();
    Object[] args = new Object[paramTypes.length];
    for (int i = 0; i < paramTypes.length; i++) {
        DependencyDescriptor desc = new DependencyDescriptor(
            paramTypes[i], true, paramTypes[i].getSimpleName());
        // this 가 DefaultListableBeanFactory인 전제 (1A 시점 계층 구조에 따라 캐스팅 필요)
        args[i] = ((DefaultListableBeanFactory) this).resolveDependency(desc, requestingBeanName);
    }
    return args;
}
```

> **축소판 메모:** 동일 이름 오버로드는 *하나만* 존재한다고 가정 (학습용에선 충분). `Parameter.getName()` 대신 `getSimpleName()`을 dependency name으로 사용 — name은 에러 메시지용이라 큰 의미 없으며, `-parameters` 컴파일 옵션 의존을 피함.

> **선결 의존:** B1(`DependencyDescriptor`), B2(`resolveDependency`)가 먼저 구현되어 있어야 컴파일 통과. 섹션 순서 B → C가 그 흐름을 보장.

> **enhance 부재의 한계:** `@Bean` 메서드 본문에서 *다른 @Bean 메서드를 직접 호출*하는 경우(`return new Service(repo())`)는 매번 새 인스턴스가 생긴다. 본 plan은 *인자 형태 inter-bean reference만* 컨테이너 라우팅으로 처리하며, 직접 호출은 H2 통합 시나리오에서 *깨지는 사례*로 학습 시연.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-beans:test --tests "com.choisk.sfs.beans.CreateBeanFactoryMethodTest"
./gradlew :sfs-beans:test
```
예상: PASS, 회귀 영향 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/AbstractAutowireCapableBeanFactory.java \
        sfs-beans/src/test/java/com/choisk/sfs/beans/CreateBeanFactoryMethodTest.java
git commit -m "feat(sfs-beans): doCreateBean factoryMethod 분기 + 매개변수 자동 주입 (resolveDependency)"
```

> **실행 기록 (2026-04-25):** 커밋 `6afbd19` — PASS 2/2 (CreateBeanFactoryMethodTest). 회귀 56 → 58 전체 PASS.
>
> **편차 기록:**
> - `AbstractAutowireCapableBeanFactory` 실제 경로: `sfs-beans/.../beans/support/` (plan의 `beans/` 직속과 다름) — 구조 차이 표에 이미 등록된 항목과 동일.
> - factoryMethod 분기를 `doCreateBean` 인라인 대신 `createBeanViaFactoryMethod` 헬퍼로 분리 — 메서드 길이 제어 및 가독성 향상 (동작 동일).
> - `instantiateViaConstructor` 호출 대신 기존 `instantiateBean` 호출 유지 — plan 표기와 실제 메서드명이 다름.

---

## 섹션 D: `sfs-context` 애노테이션 3종 정의 (Task D1)

### Task D1: `@Autowired` / `@PostConstruct` / `@PreDestroy` 정의

> **TDD 적용 여부:** 제외 — 메타정보만. 컴파일 + 회귀 테스트만 검증.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/Autowired.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/PostConstruct.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/PreDestroy.java`

- [x] **Step 1: 구현**

```java
// Autowired.java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface Autowired {
    boolean required() default true;
}
```

```java
// PostConstruct.java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostConstruct {}
```

```java
// PreDestroy.java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreDestroy {}
```

- [x] **Step 2: 컴파일 + 회귀**

```bash
./gradlew :sfs-context:compileJava :sfs-context:test
```
예상: PASS.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/annotation/Autowired.java \
        sfs-context/src/main/java/com/choisk/sfs/context/annotation/PostConstruct.java \
        sfs-context/src/main/java/com/choisk/sfs/context/annotation/PreDestroy.java
git commit -m "feat(sfs-context): @Autowired/@PostConstruct/@PreDestroy 애노테이션 정의"
```

> **실행 기록 (2026-04-25):** 3개 애노테이션 생성 + sfs-context 31 PASS / 0 FAIL 회귀 유지 (1B-α 시점과 동일 카운트). `Autowired#required`에 비-자명 동작(false → null 주입)을 한 줄 Javadoc으로 명시. 메인 스레드에서 직접 처리(D1은 trivial하여 Sonnet 위임 오버헤드가 작업보다 큼).

---

## 섹션 E: `ConfigurationClassPostProcessor` 단순판 (Task E1)

### Task E1: `ConfigurationClassPostProcessor` 신설 — `@Bean` 메서드 → BeanDefinition (enhance 없이)

> **TDD 적용 여부:** 적용 — `@Bean` 추출, factoryBean/Method BD 생성 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessorTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationClassPostProcessorTest {

    @Configuration
    static class AppConfig {
        @Bean public String greeting() { return "hello"; }
    }

    @Test
    void registersBeanMethodAsFactoryMethodBeanDefinition() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));

        new ConfigurationClassPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.containsBeanDefinition("greeting")).isTrue();
        BeanDefinition bd = bf.getBeanDefinition("greeting");
        assertThat(bd.getFactoryBeanName()).isEqualTo("appConfig");
        assertThat(bd.getFactoryMethodName()).isEqualTo("greeting");
    }

    @Test
    void beanInstanceCreatedViaFactoryMethod() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("appConfig", new BeanDefinition(AppConfig.class));
        new ConfigurationClassPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.getBean("greeting")).isEqualTo("hello");
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.ConfigurationClassPostProcessorTest"
```

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;

import java.lang.reflect.Method;

public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        for (String name : bf.getBeanDefinitionNames().toArray(new String[0])) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            if (bd.getBeanClass() == null) continue;
            if (!bd.getBeanClass().isAnnotationPresent(Configuration.class)) continue;

            for (Method m : bd.getBeanClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) continue;
                Bean beanAnno = m.getAnnotation(Bean.class);
                String beanName = beanAnno.value().isEmpty() ? m.getName() : beanAnno.value();

                BeanDefinition beanBd = new BeanDefinition(m.getReturnType());
                beanBd.setFactoryBeanName(name);
                beanBd.setFactoryMethodName(m.getName());
                bf.registerBeanDefinition(beanName, beanBd);
            }
        }
    }
}
```

> **축소판 메모:** byte-buddy enhance 없음. inter-bean reference의 두 형태 중:
> - **인자 형태** (`@Bean Service service(Repo repo)`) → C1의 `resolveDependency` 라우팅으로 동작 ✅
> - **직접 호출 형태** (`@Bean Service service() { return new Service(repo()); }`) → 매번 새 인스턴스 ❌ (H2에서 시연)

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessorTest.java
git commit -m "feat(sfs-context): ConfigurationClassPostProcessor 단순판 (@Bean → factoryMethod BD, enhance 없이)"
```

> **실행 기록 (2026-04-25):** 커밋 `35f16f4` — PASS 2/2 (ConfigurationClassPostProcessorTest). 회귀 전체 33 PASS (31→33).
>
> **편차 기록:**
> - `@Bean` 애노테이션 `value()` 속성 없음 → 실제 코드는 `name() String[]` 속성 사용. `name()[0]`이 비어있지 않은 경우 해당 값을 빈 이름으로 사용하도록 구현.
> - `DefaultListableBeanFactory`는 `com.choisk.sfs.beans.support` 패키지 → 테스트 import 수정.
> - `getBeanDefinitionNames()`가 `String[]` 반환 → `.clone()`으로 배열 복사 후 순회 (`.toArray()` 대신).

---

## 섹션 F: `AutowiredAnnotationBeanPostProcessor` (Task F1)

### Task F1: `@Autowired` 필드 주입

> **TDD 적용 여부:** 적용 — `@Autowired` 필드 발견 + `resolveDependency` 호출 + reflection 주입 경로.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredFieldInjectionTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Autowired;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AutowiredFieldInjectionTest {

    static class Repo {}
    static class Worker {
        @Autowired Repo repo;
    }

    @Test
    void autowiredFieldInjectedAfterConstruction() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("repo", new BeanDefinition(Repo.class));
        bf.registerBeanDefinition("worker", new BeanDefinition(Worker.class));

        bf.preInstantiateSingletons();

        Worker w = bf.getBean(Worker.class);
        assertThat(w.repo).isNotNull();
        assertThat(w.repo).isSameAs(bf.getBean(Repo.class));
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.AutowiredFieldInjectionTest"
```

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.beans.DependencyDescriptor;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.context.annotation.Autowired;

import java.lang.reflect.Field;

public class AutowiredAnnotationBeanPostProcessor
        implements InstantiationAwareBeanPostProcessor, BeanPostProcessor {

    private final DefaultListableBeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(DefaultListableBeanFactory bf) {
        this.beanFactory = bf;
    }

    @Override
    public void postProcessProperties(Object bean, String beanName) {
        for (Field f : bean.getClass().getDeclaredFields()) {
            Autowired anno = f.getAnnotation(Autowired.class);
            if (anno == null) continue;

            Object dep = beanFactory.resolveDependency(
                new DependencyDescriptor(f.getType(), anno.required(), f.getName()), beanName);

            if (dep == null) continue;  // required=false + 매칭 없음

            f.setAccessible(true);
            try {
                f.set(bean, dep);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to autowire field " + f.getName(), e);
            }
        }
    }

    @Override public Object postProcessBeforeInitialization(Object bean, String beanName) { return bean; }
    @Override public Object postProcessAfterInitialization(Object bean, String beanName) { return bean; }
}
```

> **선결 확인:** `InstantiationAwareBeanPostProcessor.postProcessProperties` 시그니처가 1A 시점에 존재해야 함. 1A `BeanPostProcessorExtensionsTest` 통과 시점에 이미 노출되어 있을 것이므로 그대로 활용. 만약 시그니처가 달라 컴파일 실패하면 1A 코드를 grep으로 확인 후 적응.

> **축소판 메모:** 세터 주입, 생성자 주입, 컬렉션 주입, 다수 후보 폴백은 보류. 필드 주입만으로도 학습 시연 충분.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/AutowiredFieldInjectionTest.java
git commit -m "feat(sfs-context): AutowiredAnnotationBeanPostProcessor — @Autowired 필드 주입 (단순판)"
```

> **실행 기록 (2026-04-25):**
> - `postProcessProperties` 시그니처 편차: plan은 `void postProcessProperties(Object bean, String beanName)` 가정이었으나, 실제 1A 결과물은 `PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName)`. 구현을 실제 시그니처에 맞춰 pvs를 그대로 반환하는 형태로 적용.
> - `BeanDefinition` 생성자: plan 코드 블록은 무인자 생성자 + setBeanClass 패턴이었으나 실제는 `BeanDefinition(Class<?> beanClass)` 단일 생성자. 테스트 코드 수정.
> - 커밋 해시: 889ddf3. sfs-context 34 PASS (이전 33 → +1).

---

## 섹션 G: `CommonAnnotationBeanPostProcessor` (Task G1~G2)

### Task G1: `@PostConstruct` 호출 (BPP:before 시점)

> **TDD 적용 여부:** 적용 — 호출 시점이 본질. `awareName → BPP:before(@PostConstruct) → afterProps → customInit → BPP:after` 라이프사이클 5단계 검증.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/PostConstructInvokeTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PostConstructInvokeTest {

    static class Worker {
        boolean initCalled = false;
        @PostConstruct void init() { initCalled = true; }
    }

    @Test
    void postConstructInvokedDuringBeanCreation() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("worker", new BeanDefinition(Worker.class));

        Worker w = bf.getBean(Worker.class);
        assertThat(w.initCalled).isTrue();
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.PostConstructInvokeTest"
```

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;

import java.lang.reflect.Method;

public class CommonAnnotationBeanPostProcessor implements BeanPostProcessor {

    private final DefaultListableBeanFactory beanFactory;

    public CommonAnnotationBeanPostProcessor(DefaultListableBeanFactory bf) {
        this.beanFactory = bf;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        for (Method m : bean.getClass().getDeclaredMethods()) {
            if (!m.isAnnotationPresent(PostConstruct.class)) continue;
            m.setAccessible(true);
            try {
                m.invoke(bean);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("@PostConstruct invocation failed for " + beanName, e);
            }
        }
        return bean;
    }

    @Override public Object postProcessAfterInitialization(Object bean, String beanName) { return bean; }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/PostConstructInvokeTest.java
git commit -m "feat(sfs-context): CommonAnnotationBeanPostProcessor — @PostConstruct 호출 (BPP:before)"
```

> **실행 기록 (2026-04-25):** 커밋 `8a05dbb` — PASS 1/1 (PostConstructInvokeTest). 회귀 34 → 35 전체 PASS.
>
> **편차 기록:**
> - plan의 import `com.choisk.sfs.beans.DefaultListableBeanFactory` → 실제 `com.choisk.sfs.beans.support.DefaultListableBeanFactory` (구조 차이 표 기존 항목 동일 적용).
> - `BeanDefinition` 생성자: `BeanDefinition(Class<?> beanClass)` 단일 생성자 사용 (plan 코드와 동일, 실제 코드도 동일).
> - plan 구현 코드와 실제 구현 일치. 추가로 한국어 Javadoc 주석 및 `beanFactory` 필드 목적 주석 보강.

---

### Task G2: `@PreDestroy` 등록 + 등록 역순 호출

> **TDD 적용 여부:** 적용 — `registerDisposableBean` 시점 + LIFO 순서가 본질.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/PreDestroyOrderTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PreDestroy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

class PreDestroyOrderTest {

    static List<String> destroyOrder = new ArrayList<>();

    static class A {
        @PreDestroy void cleanup() { destroyOrder.add("A"); }
    }
    static class B {
        @PreDestroy void cleanup() { destroyOrder.add("B"); }
    }

    @Test
    void preDestroyCalledInLifoOrder() {
        destroyOrder.clear();
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("a", new BeanDefinition(A.class));
        bf.registerBeanDefinition("b", new BeanDefinition(B.class));

        bf.preInstantiateSingletons();
        bf.destroySingletons();

        assertThat(destroyOrder).containsExactly("B", "A"); // LIFO
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.PreDestroyOrderTest"
```

- [x] **Step 3: 구현 — `postProcessAfterInitialization` 보강**

```java
// CommonAnnotationBeanPostProcessor에 추가/변경
import com.choisk.sfs.context.annotation.PreDestroy;

@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
    java.util.List<Method> preDestroyMethods = new java.util.ArrayList<>();
    for (Method m : bean.getClass().getDeclaredMethods()) {
        if (m.isAnnotationPresent(PreDestroy.class)) {
            m.setAccessible(true);
            preDestroyMethods.add(m);
        }
    }
    if (!preDestroyMethods.isEmpty()) {
        // LIFO 순서는 DefaultSingletonBeanRegistry.destroySingletons에서 보장됨 (1B-α 검증)
        // 본 처리기는 등록만 책임
        beanFactory.registerDisposableBean(beanName, () -> {
            for (Method m : preDestroyMethods) {
                try { m.invoke(bean); }
                catch (ReflectiveOperationException e) { System.err.println("@PreDestroy failed: " + e); }
            }
        });
    }
    return bean;
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/PreDestroyOrderTest.java
git commit -m "feat(sfs-context): CommonAnnotationBeanPostProcessor — @PreDestroy 등록 + LIFO 호출"
```

> **실행 기록 (2026-04-25):** 커밋 `c81a422` — PASS 1/1 (PreDestroyOrderTest). 회귀 35 → 36 전체 PASS.
>
> **편차 기록:**
> - `registerDisposableBean` 두 번째 인자 타입: `Runnable` — 람다를 그대로 전달 (plan 코드와 동일).
> - plan의 `java.util.List`/`java.util.ArrayList` 인라인 표기 대신 명시 import(`import java.util.ArrayList; import java.util.List;`) 적용 (CLAUDE.md 와일드카드 금지 규칙 준수).
> - `DefaultListableBeanFactory` 실제 패키지: `com.choisk.sfs.beans.support` (구조 차이 표 기존 항목 동일 적용).

---

## 섹션 H: 자동 등록 + 통합 시연 + 마감 (Task H1~H3)

### Task H1: `AnnotationConfigUtils` 신설 + 처리기 3종 자동 등록 *(simplify 이월 B3 통합)*

> **TDD 적용 여부:** 적용 — 등록 헬퍼의 멱등성 (이미 등록된 처리기 중복 등록 방지).
>
> **순서 메모:** 본 task는 E1/F1/G1이 모두 구현된 *후*에 진행되어야 한다 (처리기 3종 클래스가 실재해야 import + new 가능). 섹션 H 시작 시점에는 빈 껍데기 우회 없이 그대로 컴파일 통과.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java`
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigApplicationContext.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotationConfigUtilsTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.context.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationConfigUtilsTest {
    @Test
    void registersThreeProcessors() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // 처리기 3종이 BFPP/BPP 컬렉션에 들어갔는지 검증 (생성자에서 자동 등록)
        assertThat(ctx.getBeanFactoryPostProcessorCount()).isGreaterThanOrEqualTo(1);
        assertThat(ctx.getBeanFactory().getBeanPostProcessorCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void idempotentSecondCallDoesNotDuplicate() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        int firstBfppCount = ctx.getBeanFactoryPostProcessorCount();
        int firstBppCount = ctx.getBeanFactory().getBeanPostProcessorCount();

        AnnotationConfigUtils.registerAnnotationConfigProcessors(ctx);
        assertThat(ctx.getBeanFactoryPostProcessorCount()).isEqualTo(firstBfppCount);
        assertThat(ctx.getBeanFactory().getBeanPostProcessorCount()).isEqualTo(firstBppCount);
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — `AnnotationConfigUtils` 미존재)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.AnnotationConfigUtilsTest"
```

- [x] **Step 3: 구현**

```java
package com.choisk.sfs.context.support;

import com.choisk.sfs.context.ConfigurableApplicationContext;

public final class AnnotationConfigUtils {
    private AnnotationConfigUtils() {}

    public static void registerAnnotationConfigProcessors(ConfigurableApplicationContext ctx) {
        if (!hasBfpp(ctx, ConfigurationClassPostProcessor.class)) {
            ctx.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
        }
        if (!hasBpp(ctx, AutowiredAnnotationBeanPostProcessor.class)) {
            ctx.getBeanFactory().addBeanPostProcessor(
                new AutowiredAnnotationBeanPostProcessor(ctx.getBeanFactory()));
        }
        if (!hasBpp(ctx, CommonAnnotationBeanPostProcessor.class)) {
            ctx.getBeanFactory().addBeanPostProcessor(
                new CommonAnnotationBeanPostProcessor(ctx.getBeanFactory()));
        }
    }

    private static boolean hasBfpp(ConfigurableApplicationContext ctx, Class<?> type) {
        return ctx.getBeanFactoryPostProcessors().stream().anyMatch(type::isInstance);
    }

    private static boolean hasBpp(ConfigurableApplicationContext ctx, Class<?> type) {
        return ctx.getBeanFactory().getBeanPostProcessors().stream().anyMatch(type::isInstance);
    }
}
```

```java
// AnnotationConfigApplicationContext 생성자에 한 줄 추가
public AnnotationConfigApplicationContext() {
    this.reader = new AnnotatedBeanDefinitionReader(this);
    this.scanner = new ClassPathBeanDefinitionScanner(this);
    AnnotationConfigUtils.registerAnnotationConfigProcessors(this);  // ← 추가
}
```

> **simplify B3 통합:** `getBeanFactory()` 직접 전달 vs `registerBeanDefinition` 위임 경로를 본 헬퍼가 단일화. reader/scanner는 `this` (BeanDefinitionRegistry 구현)를 받음.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test
```

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java \
        sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigApplicationContext.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotationConfigUtilsTest.java
git commit -m "feat(sfs-context): AnnotationConfigUtils — 처리기 3종 자동 등록 헬퍼 (simplify 이월 B3 통합)"
```

> **실행 기록 (2026-04-25):** 커밋 `e9a1bdd` — PASS 2/2 (AnnotationConfigUtilsTest). 회귀 전체 38 PASS (36→38).
>
> **편차 기록:**
> - `getBeanFactoryPostProcessorCount()` 메서드 미존재 → `ConfigurableApplicationContext`에 `getBeanFactoryPostProcessors(): List<BeanFactoryPostProcessor>` 공개 노출, `AbstractApplicationContext.getBeanFactoryPostProcessors()` protected → `@Override public`로 변경. 테스트에서 `.size()`로 카운트 접근.
> - `ConfigurableBeanFactory` 인터페이스에 `getBeanPostProcessors(): List<BeanPostProcessor>` 추가 — `hasBpp` 헬퍼가 인터페이스 경로로 BPP 목록 접근 가능하도록 (sfs-beans 수정 포함).
> - `ctx.getBeanFactory()` 반환 타입이 `ConfigurableListableBeanFactory` (인터페이스) → `AutowiredAnnotationBeanPostProcessor`/`CommonAnnotationBeanPostProcessor` 생성자에 `DefaultListableBeanFactory` 전달 필요. `registerAnnotationConfigProcessors` 내부에서 `instanceof DefaultListableBeanFactory dlbf` 패턴 매칭으로 다운캐스팅 후 전달.
> - `AnnotationConfigApplicationContext` reader/scanner 초기화가 `getBeanFactory()` 인자로 수행 (plan 본문의 `this` 대신). 자동 등록 호출 라인은 그대로 추가.

---

### Task H2: 통합 시연 — `Phase1IntegrationTest` (Configuration + Autowired + 라이프사이클 + inter-bean 동작 차이)

> **TDD 적용 여부:** 적용 (통합) — Phase 1 마감의 시연 시나리오. 모든 처리기 3종이 동시에 동작하며, **inter-bean reference의 두 형태 차이**(컨테이너 라우팅 vs 직접 호출)를 명시적으로 검증.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/Phase1IntegrationTest.java`

- [x] **Step 1: 통합 시나리오 작성 — 4가지 동작 검증**

```java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.annotation.*;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Phase1IntegrationTest {

    static class Repo {
        String greet() { return "data"; }
    }

    static class Service {
        final Repo repo;
        Service(Repo repo) { this.repo = repo; }
    }

    @Configuration
    static class AppConfigArgInjection {
        @Bean public Repo repo() { return new Repo(); }
        // ✅ 매개변수로 의존성 받음 — 컨테이너가 resolveDependency로 라우팅
        @Bean public Service service(Repo repo) { return new Service(repo); }
    }

    @Configuration
    static class AppConfigDirectCall {
        @Bean public Repo repo() { return new Repo(); }
        // ❌ 본문에서 직접 호출 — enhance 없으므로 매번 새 Repo 인스턴스
        @Bean public Service service() { return new Service(repo()); }
    }

    @Component
    static class Worker {
        @Autowired Service service;
        boolean initCalled = false;
        boolean destroyCalled = false;

        @PostConstruct void init() { initCalled = true; }
        @PreDestroy void cleanup() { destroyCalled = true; }
    }

    // 1. 매개변수 형태 inter-bean reference: 컨테이너가 동일 싱글톤 라우팅 ✅
    @Test
    void argFormBeanReferenceUsesContainerRouting() {
        AnnotationConfigApplicationContext ctx =
            new AnnotationConfigApplicationContext(AppConfigArgInjection.class);

        Service service = ctx.getBean(Service.class);
        Repo repo = ctx.getBean(Repo.class);

        assertThat(service.repo)
            .as("매개변수로 받은 Repo는 컨테이너의 동일 싱글톤이어야 함")
            .isSameAs(repo);
        ctx.close();
    }

    // 2. 직접 호출 형태 inter-bean reference: enhance 부재로 매번 새 인스턴스 ❌
    @Test
    void directCallFormCreatesDistinctInstanceWithoutEnhance() {
        AnnotationConfigApplicationContext ctx =
            new AnnotationConfigApplicationContext(AppConfigDirectCall.class);

        Service service = ctx.getBean(Service.class);
        Repo repo = ctx.getBean(Repo.class);

        assertThat(service.repo)
            .as("enhance가 없으면 service() 본문의 repo() 호출은 컨테이너를 우회하여 새 Repo를 만든다")
            .isNotSameAs(repo);
        ctx.close();
    }

    // 3. Phase 1 풀 시나리오: Configuration + 매개변수 주입 + Autowired + 라이프사이클
    @Test
    void phase1FullScenario() {
        AnnotationConfigApplicationContext ctx =
            new AnnotationConfigApplicationContext(AppConfigArgInjection.class, Worker.class);

        Worker w = ctx.getBean(Worker.class);
        assertThat(w.service).isNotNull();
        assertThat(w.service.repo).isSameAs(ctx.getBean(Repo.class));
        assertThat(w.service.repo.greet()).isEqualTo("data");
        assertThat(w.initCalled).as("@PostConstruct must fire").isTrue();
        assertThat(w.destroyCalled).as("@PreDestroy not called yet").isFalse();

        ctx.close();
        assertThat(w.destroyCalled).as("@PreDestroy fires on close()").isTrue();
    }
}
```

- [x] **Step 2: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.Phase1IntegrationTest"
```
예상: PASS 3건 — 매개변수 라우팅 / 직접 호출 차이 / 풀 시나리오 모두 동작.

> **학습 메모:** 두 번째 테스트(`directCallFormCreatesDistinctInstanceWithoutEnhance`)가 *왜 Spring이 byte-buddy를 쓰는지*를 직접 보여준다. 본 plan에서는 `isNotSameAs`로 *깨지는 사실*을 박제하고, 보류 항목(plan 끝 "보류 — 추후 학습" 섹션)에서 enhance 도입 시 `isSameAs`로 바뀐다는 점을 학습 가치로 명시.

- [x] **Step 3: 커밋**

```bash
git add sfs-context/src/test/java/com/choisk/sfs/context/integration/Phase1IntegrationTest.java
git commit -m "test(sfs-context): Phase1IntegrationTest — Configuration + Autowired + 라이프사이클 + inter-bean 두 형태 시연"
```

> **실행 기록 (2026-04-25):** 커밋 `49c8c6b` — PASS 3/3 (Phase1IntegrationTest). 회귀 38 → 41 전체 PASS.
>
> **편차 기록:**
> - `AnnotationConfigApplicationContext` 가변 인자 생성자 실재 확인 — `public AnnotationConfigApplicationContext(Class<?>... componentClasses)` 형태로 존재. plan 시나리오 그대로 사용 가능.
> - `directCallFormCreatesDistinctInstanceWithoutEnhance`가 `isNotSameAs` PASS 확인 — 컨테이너에 의도하지 않은 캐싱 없음. Phase 1 학습 메시지 유지.
> - plan 코드 블록의 와일드카드 import(`import com.choisk.sfs.context.annotation.*`) 대신 명시 import 6개로 CLAUDE.md 규약 준수.
> - 3개 테스트 모두 기존 처리기 구현이 완료된 상태에서 작성했으므로 RED 없이 바로 GREEN — 통합 시연의 특성상 plan이 허용한 패턴("한 번에 3 테스트 작성 후 PASS 확인").

---

### Task H3: 마감 — 회귀 + README + DoD 갱신

> **TDD 적용 여부:** 제외 — 마감 + 문서.

**Files:**
- Modify: `sfs-context/README.md`
- Modify: `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` (DoD 9항목 모두 `[x]` + 실행 기록 블록)

- [x] **Step 1: 전체 회귀 + 빌드 검증**

```bash
./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test
./gradlew build
```
예상:
- sfs-core: 25 PASS (변동 없음)
- sfs-beans: 48 → ~53 PASS (B1 +1, B2 +4, B3 +1, C1 +2 — 단 A1은 이미 +2 반영됨)
- sfs-context: 31 → ~40 PASS (D1 컴파일만, E1 +2, F1 +1, G1 +1, G2 +1, H1 +2, H2 +3)
- 총합: ~118~122 PASS / BUILD SUCCESSFUL

- [x] **Step 2: README 업데이트**

`sfs-context/README.md`에 "1B-β 시점 동작 (학습용 최소 스코프)" 섹션 추가:

````markdown
## 1B-β 시점 동작 (학습용 최소 스코프, Phase 1 종료)

`@Component` + `@Configuration`/`@Bean` (매개변수 자동 주입 포함) + `@Autowired` (필드 주입만) + `@PostConstruct`/`@PreDestroy`가 동작하는 작은 IoC 컨테이너.

```java
@Configuration
class AppConfig {
    @Bean Repo repo() { return new Repo(); }
    @Bean Service service(Repo repo) { return new Service(repo); }  // 매개변수 자동 주입 ✅
}

var ctx = new AnnotationConfigApplicationContext(AppConfig.class, Worker.class);
Worker w = ctx.getBean(Worker.class);
// w의 @Autowired 필드 주입됨, @PostConstruct 호출됨
ctx.close();
// @PreDestroy 호출됨 (등록 역순)
```

**enhance 부재의 한계 (학습 시연):** `@Bean` 본문에서 다른 `@Bean` 메서드를 *직접 호출*하면 매번 새 인스턴스가 만들어진다. 매개변수 형태(컨테이너 라우팅)는 동일 싱글톤이 보장된다. `Phase1IntegrationTest`의 `directCallFormCreatesDistinctInstanceWithoutEnhance`에서 검증.

**보류 항목 (deep version `75842e5` 참조):**
- byte-buddy enhance (직접 호출 형태도 동일 싱글톤 보장)
- 세터/생성자 주입
- `List<T>`/`Map<String, T>` 컬렉션 주입
- `@Primary`/`@Qualifier` 다수 후보 폴백
- ASM 사전 필터
````

- [x] **Step 3: 본 plan DoD 9항목 모두 `[x]` + 실행 기록 블록 추가**

`docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` 하단 DoD 섹션의 9항목을 모두 `[x]`로 갱신. 그리고 마지막에:

```markdown
> **실행 기록 (YYYY-MM-DD):**
>
> - **회귀:** sfs-core 25 + sfs-beans ~53 + sfs-context ~40 = 총 ~118 PASS / 0 FAIL
> - **빌드:** ./gradlew build → BUILD SUCCESSFUL
> - **추가 커밋:** A1~G2 + H1 + H2 + H3 = N개 커밋
> - **Phase 1 종료** ✅
```

- [x] **Step 4: 최종 커밋**

```bash
git add sfs-context/README.md docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md
git commit -m "docs: Plan 1B-β 마감 — README 학습용 시나리오 + DoD 9항목 [x] + Phase 1 종료"
```

---

## 🎯 Plan 1B-β Definition of Done — 최종 체크리스트 (학습용 최소 스코프 9항목)

**기능적 DoD:**

- [x] 1. `@Configuration` + `@Bean` 클래스의 빈이 등록되며, 메서드명이 빈 이름이 된다 (Task E1)
- [x] 2. `@Bean("custom")` 명시 시 그 이름이 우선한다 (Task E1)
- [x] 3. **`@Bean` 메서드 매개변수에 컨테이너가 의존성을 자동 주입한다** (Task C1) — 인자 형태 inter-bean reference 동작
- [x] 4. `@Autowired` 필드 주입이 동작한다 (Task F1)
- [x] 5. `@Autowired(required=false)`는 매칭 빈이 없을 때 null을 주입한다 (Task B2, F1)
- [x] 6. `@PostConstruct` 메서드는 BPP:before 시점에 호출된다 (Task G1)
- [x] 7. `@PreDestroy` 메서드는 `close()` 시 등록 역순으로 호출된다 (Task G2)

**품질 DoD:**

- [x] 8. simplify 이월 B3(`getBeanFactory()` 경로 통일, Task H1) + B4(`registerSingleton` atomic, Task B3) 모두 본 plan 내 반영
- [x] 9. `./gradlew build` 전체 PASS + 누적 ~118~122 테스트 PASS (Task H3)

> **실행 기록 (2026-04-25):**
>
> - **회귀:** sfs-core 25 + sfs-beans 58 + sfs-context 41 = **총 124 PASS / 0 FAIL** (예상 ~118~122 살짝 상회 — H1 인터페이스 확장으로 +2~3건)
> - **빌드:** `./gradlew build` → BUILD SUCCESSFUL
> - **추가 커밋:** A1(1) + B1~B3(4) + C1(1) + D1(2) + E1(2) + F1(1) + G1(1) + G2(1) + H1(1) + H2(2) + 박제 블록 갱신(2) + H3(1) ≈ 19 커밋
> - **누적 편차:** 박제 블록에 7건 기록 (`NoSuchBeanDefinitionException` 위치, `DefaultListableBeanFactory`/`DefaultSingletonBeanRegistry` 패키지 차이, `getBeansOfType` 단독 사용 한계, `instantiateViaConstructor`→`instantiateBean`, `@Bean#value()`→`name() String[]`, `getBeanDefinitionNames()` 반환 타입, `postProcessProperties` PropertyValues 시그니처)
> - **인터페이스 확장:** H1에서 `ConfigurableApplicationContext.getBeanFactoryPostProcessors()` + `ConfigurableBeanFactory.getBeanPostProcessors()`를 인터페이스에 노출 (멱등성 검사 헬퍼 요구)
> - **학습 가치 박제:** `Phase1IntegrationTest#directCallFormCreatesDistinctInstanceWithoutEnhance`가 `isNotSameAs`로 PASS — enhance 부재의 한계가 회귀 안전망으로 박제됨. 미래 byte-buddy 도입 시 `isSameAs`로 변경되는 것이 마일스톤 증거.
> - **Phase 1 종료**

---

## ▶ Plan 1B-β 완료 후 다음 단계

1. **CLAUDE.md "완료 후 품질 게이트" 3단계** — 다관점 코드리뷰 → 리팩토링 → `/simplify` 패스. 결과를 plan 문서 하단에 `> **품질 게이트 기록 (YYYY-MM-DD):**` 블록으로 기록 (1B-α 패턴).
2. **main 머지** — `feat/phase1b-processors` → main `--no-ff` 또는 GitHub PR.
3. **Phase 1 종료 선언** — 학습용 최소 스코프 IoC 컨테이너 완성. `sfs-samples` 모듈에 데모 application 만들기 (선택).
4. **이후 방향 결정** — Phase 2 (AOP)로 진행 / 다른 학습 영역으로 점프 / 보류 항목 점진 추가 (deep version 참조). 별도 brainstorming 시점에 결정.

---

## 품질 게이트 발견 — Phase 2 진입 전 / 다음 plan 처리 항목

다관점 코드리뷰(2026-04-25, `feature-dev:code-reviewer` + `code-quality-reporter` 교차 검증)에서 발견된 이슈 중 *현재 학습 시나리오 영향이 0인 항목*은 본 plan에서 처리하지 않고 박제. 영향이 즉시 있는 항목 1건(상속 reflection)은 품질 게이트 2단계 리팩토링에서 처리됨 (`ReflectionUtils.doWithFields/doWithMethods`로 F1/G1/G2 일원화).

| 우선순위 | 이슈 | 위치 | 처리 방향 |
|---|---|---|---|
| 🔵 Phase 2 직전 | `(DefaultListableBeanFactory) this` 다운캐스팅 | `AbstractAutowireCapableBeanFactory#resolveFactoryMethodArguments` (C1) | `resolveDependency`를 상위 인터페이스(`ConfigurableListableBeanFactory` 또는 `AutowireCapableBeanFactory`)로 승격 후 인터페이스 호출로 변환. AOP 서브클래스 등장 시 `ClassCastException` 위험 차단. |
| 🔵 Phase 2 직전 | BPP 처리기의 `DefaultListableBeanFactory` 직접 의존 (위 항목과 묶음) | F1, G1 처리기 생성자 | 위 항목 처리 후 처리기 생성자도 인터페이스 타입으로 변경. 모듈 결합도 감소. |
| 🟣 다음 plan | `DisposableBean` 이중 등록 가능성 (가설 경로) | B3 `registerSingleton` + `doCreateBean#registerDisposableIfNeeded` | `registerDisposableBean`에 이미 등록된 이름 가드 추가 또는 외부 API 코멘트 박제 (`// 외부 직접 등록 전용`). |
| 🟣 다음 plan | `getBeanNamesForType` 반환 타입 매칭 한계 | E1 BD 생성 (`new BeanDefinition(m.getReturnType())`) | 인터페이스/Object 반환 시 과매칭 가능 (현재 테스트 범위 내 영향 0). 컬렉션 주입 추가 시점에 인스턴스 후 실제 타입 추적 도입. |
| 🟣 다음 plan | `getBean(Class)` 경로의 `@Primary` 지원 vs `resolveDependency`의 다수 후보 무조건 예외 — 두 조회 경로 동작 불일치 | `DefaultListableBeanFactory#resolveDependency` 다수 후보 분기 (B2) | `resolveBeansOfType` 결과에 `resolveBeanNameByType`의 `@Primary` 우선 로직 적용. 또는 Javadoc에 "@Primary 미지원" 명시. |
| ⚪ 가벼움 | WHAT 주석 2건 (CLAUDE.md 위반) | F1 라인 39-41, 46-48 | "@Autowired 없는 필드 건너뜀" → 삭제. "required=false + null" → WHY 재작성("선택적 의존성이라 누락이 정상"). 즉시 cleanup 또는 다음 plan 흡수. — **simplify 패스에서 반영 완료 (2026-04-25)** |

> **참고:** 두 리뷰가 *우선순위에서 직접 충돌*한 항목 2건은 *현재 학습 시나리오 영향 유무*를 결정 기준으로 사용. 다운캐스팅(영향 0)은 Phase 2 직전, 상속 reflection(영향 즉시)은 본 plan 내 처리. quality-reporter 종합 등급: **B+ (84/100)** — 학습 가치 95(A) / 가독성 90(A) / 테스트 87(B) / 안전성 85(B) / 유지보수성 80(B) / 아키텍처 78(C). main 머지 적합.

---

## 보류 — 추후 학습 (deep version `75842e5` 참조)

본 plan은 학습 동기 부여를 위해 의도적으로 축소되었다. 아래 항목들은 *Spring을 더 깊이 이해하고 싶을 때* 점진적으로 추가 가능. deep version plan(2026-04-23 시점 1417 LOC, 30 task)에 본문이 보존되어 있음.

| 항목 | 학습 가치 | deep version 위치 |
|---|---|---|
| **byte-buddy enhance + 직접 호출 형태 동일 싱글톤** | proxy 기반 메타프로그래밍 / `@Configuration` 계약의 본질 (H2의 두 번째 테스트가 `isSameAs`로 변하는 시점) | deep Task E1, E2, E3 |
| **세터/생성자 주입** + 단일 ctor 자동 검출 (Spring 4.3+) | 의존성 주입의 3 경로 / 불변성 / 테스트 용이성 | deep Task F2, F3 |
| **`List<T>`/`Map<String, T>` 컬렉션 주입** | 제네릭 추출 / 타입 시스템 / 다중 구현 패턴 | deep Task F5 |
| **`@Primary` → `@Qualifier` → 필드명 폴백** | 다수 후보 해결 정책 / 전략 패턴 | deep Task F6 |
| **ASM 사전 필터** + `ClassUtils.forName` 클래스로더 정책 | 클래스 로딩 비용 최적화 / hot-path 분석 | deep Task E4 (simplify 이월 B1+B2) |
| **`AnnotationConfigContextIntegrationTest` 등 통합 6선** | end-to-end 시나리오 + tracing context 패턴 | deep 섹션 H |

> **언제 deep version으로 돌아갈까?** ① 본 plan의 시연 application을 다 만들고 *동작은 잘 하는데 직접 호출 형태 inter-bean reference에서 막혔을 때* ② Phase 2 (AOP)로 가지 않고 IoC를 더 깊이 보고 싶을 때 ③ 면접/실무에서 *Spring 내부 구현*을 묻는 상황을 대비할 때.

---

## 품질 게이트 기록 (2026-04-25)

CLAUDE.md "완료 후 품질 게이트" 3단계 실행 결과:

**1단계 — 다관점 코드리뷰 (병렬 2 에이전트)**
- `feature-dev:code-reviewer` (actionable 이슈) + `code-quality-reporter` (다차원 점수)
- 발견 6건 / 즉시 1건 / 남겨둘 5건
- 종합 등급 **B+ (84/100)** — 학습 가치 95(A) / 가독성 90(A) / 테스트 87(B) / 안전성 85(B) / 유지보수성 80(B) / 아키텍처 78(C)

**2단계 — 리팩토링 (즉시 고칠 1건)**
- 상속 계층 reflection 미탐색 → `ReflectionUtils.doWithFields/doWithMethods` 일원화로 F1/G1/G2 fix
- 회귀: 124 → 130 PASS (+6: ReflectionUtils 단위 테스트 +3, 상속 회귀 테스트 +3)
- 커밋 3개: `test → feat → fix` (문제 → 도구 → 해결 흐름 박제)

**3단계 — `/simplify` 패스 (병렬 3 에이전트)**
- reuse + quality + efficiency 독립 분석
- 발견 11건 / 반영 11건 / 추가 박제 1건 (`@Primary` 경로 불일치)
- 채택률 ~100% (세 리뷰 교차 검증 + 박제 항목 명시적 표기 효과)
- 커밋 4개: `refactor` ReflectionUtils 일원화 4건 / `chore` narrating+WHAT 주석 6건 / `refactor` `.clone()` 이중 복사 제거 / `docs` 박제 보강

**최종 회귀:** sfs-core 28 + sfs-beans 58 + sfs-context 44 = **130 PASS / 0 FAIL** / `./gradlew build` BUILD SUCCESSFUL.

**보류 박제 5건** (별도 표 위치: 이 문서 "## 품질 게이트 발견" 섹션):
- 🔵 Phase 2 직전 (2건): `(DefaultListableBeanFactory) this` 다운캐스팅 제거 + BPP 처리기의 `DefaultListableBeanFactory` 직접 의존
- 🟣 다음 plan (3건): `DisposableBean` 이중 등록 가능성 / `getBeanNamesForType` 반환 타입 과매칭 / `@Primary` 경로 불일치 (`getBean(Class)` vs `resolveDependency`)

**Phase 1 종료** — main 머지 준비 완료.
