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

---

## 섹션 A: spec amendment + sfs-context 모듈 스캐폴딩 (Task 1~3)

### Task 1: spec line 47 amendment + byte-buddy 카탈로그 등록

> **TDD 적용 여부:** 제외 — 설정 파일 + 문서 변경.

**Files:**
- Modify: `docs/superpowers/specs/2026-04-19-ioc-container-design.md` (line 47 부근)
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: spec line 47 amendment**

`docs/superpowers/specs/2026-04-19-ioc-container-design.md`의 "ASM 외 외부 의존이 없다" 문장을 다음으로 교체:

```
외부 런타임 의존: ASM(클래스패스 스캔), byte-buddy(`@Configuration` 클래스 enhance).
그 외 의존 추가는 spec 개정을 요구한다.
```

> 정확한 라인은 spec 작성 시점과 다를 수 있으므로 grep으로 "ASM 외 외부 의존" 위치를 먼저 확인 후 수정.

- [ ] **Step 2: `gradle/libs.versions.toml`에 byte-buddy 추가**

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

- [ ] **Step 3: 빌드 확인 (회귀)**

```bash
./gradlew build -x test
```
예상: BUILD SUCCESSFUL (catalog 추가만으로 컴파일 깨지면 안 됨).

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-04-19-ioc-container-design.md gradle/libs.versions.toml
git commit -m "docs+chore: spec 의존성 정책 amendment + byte-buddy 카탈로그 등록"
```

---

### Task 2: `sfs-context` 모듈 스캐폴딩

> **TDD 적용 여부:** 제외 — 설정/스모크 테스트.

**Files:**
- Create: `sfs-context/build.gradle.kts`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/package-info.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/PackageSmokeTest.java`

- [ ] **Step 1: `sfs-context/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-beans"))
    implementation(libs.bytebuddy)  // 1B-α는 미사용, 1B-β의 ConfigurationClassEnhancer가 사용
}
```

- [ ] **Step 2: `package-info.java`**

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

- [ ] **Step 3: 스모크 테스트**

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

- [ ] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-context:test
```
예상: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "chore(sfs-context): 모듈 스캐폴딩 (sfs-beans 의존, byte-buddy 카탈로그 의존)"
```

---

### Task 3: `sfs-context/README.md` 초안 (1B-β까지 진화)

> **TDD 적용 여부:** 제외 — 문서.

**Files:**
- Create: `sfs-context/README.md`

- [ ] **Step 1: README 초안 작성**

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

- [ ] **Step 2: 커밋**

```bash
git add sfs-context/README.md
git commit -m "docs(sfs-context): README 초안 (1B-α 시점 동작 시나리오 + Spring 매핑표)"
```

---

## 섹션 B: `sfs-core` AnnotationUtils + 애노테이션 10종 (Task 4~6)

### Task 4: `AnnotationUtils.isAnnotated` (메타-애노테이션 재귀 인식)

> **TDD 적용 여부:** 적용 — 메타 재귀 + 사이클 방지가 본질.

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationUtils.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AnnotationUtilsTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-core:test --tests AnnotationUtilsTest
```
예상: FAIL (AnnotationUtils 미존재 — 컴파일 에러).

- [ ] **Step 3: 구현**

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

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-core:test --tests AnnotationUtilsTest
```
예상: 5/5 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): AnnotationUtils.isAnnotated — 메타 재귀 + 사이클 방지"
```

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

- [ ] **Step 1: `@Component`**

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

- [ ] **Step 2: `@Service` / `@Repository` / `@Controller` (모두 `@Component` 메타)**

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

- [ ] **Step 3: `@Configuration`** (`proxyBeanMethods` 옵션은 1B-β에서 사용)

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

- [ ] **Step 4: `@Bean`** (1B-α에서는 정의만, 처리는 1B-β)

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

- [ ] **Step 5: `@Scope`**

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

- [ ] **Step 6: `@Lazy` (class-level only, Q9 결정)**

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

- [ ] **Step 7: `@Primary`**

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

- [ ] **Step 8: `@Qualifier`**

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

- [ ] **Step 9: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 10: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/annotation/
git commit -m "feat(sfs-context): 애노테이션 10종 정의 (@Component, @Service, @Repository, @Controller, @Configuration, @Bean, @Scope, @Lazy, @Primary, @Qualifier)"
```

---

### Task 6: 애노테이션 메타-인식 검증 통합 테스트

> **TDD 적용 여부:** 적용 — 메타-인식 동작이 본질.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/annotation/StereotypeMetaTest.java`

- [ ] **Step 1: 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests StereotypeMetaTest
```
예상: 5/5 PASS.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): @Service/@Repository/@Controller/@Configuration 메타 인식 검증"
```

---

## 섹션 C: ApplicationContext 인터페이스 (Task 7~8)

### Task 7: `ApplicationContext` 인터페이스

> **TDD 적용 여부:** 제외 — 시그니처만.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/ApplicationContext.java`

- [ ] **Step 1: 인터페이스 작성**

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

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): ApplicationContext 인터페이스 (BeanFactory 상위)"
```

---

### Task 8: `ConfigurableApplicationContext` 인터페이스

> **TDD 적용 여부:** 제외 — 시그니처만.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/ConfigurableApplicationContext.java`

- [ ] **Step 1: 인터페이스 작성**

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

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): ConfigurableApplicationContext (refresh/close/shutdown hook)"
```

---

## 섹션 D: AbstractApplicationContext refresh 8단계 템플릿 (Task 9~12)

### Task 9: `AbstractApplicationContext` 골격 + 단계 메서드 시그니처

> **TDD 적용 여부:** 제외 — 추상 골격 + 시그니처. Task 10~12에서 동작별 단독 TDD.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`

- [ ] **Step 1: 골격 작성**

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

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): AbstractApplicationContext 골격 + 단계 메서드 시그니처"
```

---

### Task 10: `refresh()` 8단계 정상 흐름 (TDD)

> **TDD 적용 여부:** 적용 — 단계 순서 + 호출 횟수가 본질.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/AbstractApplicationContextTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests AbstractApplicationContextTest
```
예상: FAIL (refresh가 UnsupportedOperationException을 던짐).

- [ ] **Step 3: `refresh()` 본문 구현 — `AbstractApplicationContext.refresh()`**

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

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests AbstractApplicationContextTest
```
예상: 2/2 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): refresh() 8단계 정상 흐름 + single-shot 가드"
```

---

### Task 11: `refresh()` 실패 시 자동 cleanup (TDD)

> **TDD 적용 여부:** 적용 — try-catch + destroyBeans + cancelRefresh가 본질.

**Files:**
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/RefreshFailureCleanupTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

> **선결 조건 확인:** `DefaultSingletonBeanRegistry.destroySingletons()`가 `registerSingleton`으로 등록된 빈도 destroy 콜백을 실행하는지 확인. Plan 1A 종료 시점 동작과 일치해야 함. 만약 등록 시점에 `DisposableBean` 인지 + 콜백 등록이 안 돼있다면 sfs-beans 보강 후 진행 — 별도 커밋으로 분리.

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew :sfs-context:test --tests RefreshFailureCleanupTest
```
예상: PASS (Task 10에서 try-catch + destroyBeans 이미 구현됨). 만약 destroy가 호출 안되면 sfs-beans `registerSingleton` 측 보강 필요.

- [ ] **Step 3: 커밋**

```bash
git add sfs-context/
git commit -m "test(sfs-context): refresh() 실패 시 destroyBeans + cancelRefresh 자동 호출 검증"
```

---

### Task 12: `close()` + JVM shutdown hook (TDD)

> **TDD 적용 여부:** 적용 — idempotent + JVM 진행 중 예외 처리가 본질.

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AbstractApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/integration/CloseAndShutdownHookTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests CloseAndShutdownHookTest
```
예상: FAIL (close()/registerShutdownHook이 UnsupportedOperationException).

- [ ] **Step 3: `close()` + `registerShutdownHook()` 구현 — `AbstractApplicationContext`**

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

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests CloseAndShutdownHookTest
```
예상: 3/3 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): close()/registerShutdownHook() — idempotent + JVM 진행 중 예외 무시"
```

---

## 섹션 E: GenericApplicationContext (Task 13)

### Task 13: `GenericApplicationContext` (single-shot)

> **TDD 적용 여부:** 적용 — single-shot 가드가 본질.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/GenericApplicationContext.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/GenericApplicationContextTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-context:test --tests GenericApplicationContextTest
```
예상: 컴파일 에러.

- [ ] **Step 3: 구현**

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

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests GenericApplicationContextTest
```
예상: 3/3 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): GenericApplicationContext — single-shot refresh + 외부 BF 주입 옵션"
```

---

## 섹션 F: BeanNameGenerator + AnnotatedBeanDefinitionReader (Task 14~15)

### Task 14: `AnnotationBeanNameGenerator` (FQN→camelCase + `@Component("custom")` 우선)

> **TDD 적용 여부:** 적용 — 명시 우선 + camelCase 변환 분기.

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/BeanNameGenerator.java`
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationBeanNameGenerator.java`
- Create: `sfs-context/src/test/java/com/choisk/sfs/context/support/AnnotationBeanNameGeneratorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

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

- [ ] **Step 2: 테스트 실행 (FAIL 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotationBeanNameGeneratorTest
```
예상: 컴파일 에러.

- [ ] **Step 3: 인터페이스 + 구현 작성**

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

- [ ] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests AnnotationBeanNameGeneratorTest
```
예상: 5/5 PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-context/
git commit -m "feat(sfs-context): BeanNameGenerator + AnnotationBeanNameGenerator (명시 value 우선, FQN→camelCase 폴백)"
```

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
        assertThat(bd.isLazy()).isFalse();
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

        assertThat(bf.getBeanDefinition("lazyOne").isLazy()).isTrue();
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

> **선결 조건 확인:** `BeanDefinition.isLazy()` / `setLazy(boolean)` / `isPrimary()` / `setPrimary(boolean)`이 1A에서 노출되어 있어야 한다. 없으면 sfs-beans에 추가하고 별도 커밋(`feat(sfs-beans): BeanDefinition.lazy/primary 필드 노출`)으로 분리.

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
import com.choisk.sfs.context.annotation.Scope;

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
        Scope s = c.getAnnotation(Scope.class);
        if (s != null) bd.setScope(s.value());
    }

    private void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazy(l.value());
    }

    private void applyPrimary(BeanDefinition bd, Class<?> c) {
        if (c.isAnnotationPresent(Primary.class)) bd.setPrimary(true);
    }
}
```

> **선결 조건 확인:** `BeanDefinitionRegistry`와 `BeanDefinition.setScope(String)`이 1A에서 sfs-beans에 노출되어 있어야 한다. 없으면 보강 + 별도 커밋.

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
import com.choisk.sfs.context.annotation.Scope;
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
        Scope s = c.getAnnotation(Scope.class);
        if (s != null) bd.setScope(s.value());
    }

    private void applyLazy(BeanDefinition bd, Class<?> c) {
        Lazy l = c.getAnnotation(Lazy.class);
        if (l != null) bd.setLazy(l.value());
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
        lazyDef.setLazy(true);
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
        if (def.isLazy()) continue;  // ← 추가: lazy 빈은 첫 getBean 시 생성
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

> **주의:** `getBeanFactory()`는 `ConfigurableListableBeanFactory`를 반환하지만 `AnnotatedBeanDefinitionReader`/`ClassPathBeanDefinitionScanner`는 `BeanDefinitionRegistry`를 요구. `ConfigurableListableBeanFactory`가 `BeanDefinitionRegistry`를 상속하지 않는다면(1A 인터페이스 정의 확인), 아래 Step 2의 보조 접근자가 **필수**다.

- [ ] **Step 2: 보조 접근자 추가 — `GenericApplicationContext`**

`GenericApplicationContext`에 다음 메서드 추가:

```java
public DefaultListableBeanFactory getDefaultListableBeanFactory() {
    return beanFactory;
}
```

그리고 위 Step 1의 `AnnotationConfigApplicationContext` 생성자에서 `getBeanFactory()` 대신 `getDefaultListableBeanFactory()`를 reader/scanner에 전달하도록 수정:

```java
public AnnotationConfigApplicationContext() {
    this.reader = new AnnotatedBeanDefinitionReader(getDefaultListableBeanFactory());
    this.scanner = new ClassPathBeanDefinitionScanner(getDefaultListableBeanFactory());
}
```

> **선결 확인:** 1A의 `ConfigurableListableBeanFactory`가 이미 `BeanDefinitionRegistry`를 상속한다면 본 Step은 생략 가능. 그 경우 `getBeanFactory()` 그대로 사용.

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :sfs-context:compileJava :sfs-context:compileTestJava
```
예상: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**

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

## 부록 — sfs-beans 보강 항목 예상 (1B-α 진행 중 발견 시)

본 plan은 1A 완료 시점에 다음이 sfs-beans에 노출되어 있다고 가정한다. **누락 발견 시 별도 커밋으로 분리하여 보강:**

| 누락 후보 | 보강 위치 | 필요한 Task |
|---|---|---|
| `BeanDefinition.isLazy()` / `setLazy(boolean)` | `sfs-beans` BeanDefinition | Task 15, 17 |
| `BeanDefinition.isPrimary()` / `setPrimary(boolean)` | `sfs-beans` BeanDefinition | Task 15 |
| `BeanDefinition.setScope(String)` | `sfs-beans` BeanDefinition | Task 15 |
| `ConfigurableListableBeanFactory.preInstantiateSingletons()` 노출 | `sfs-beans` 인터페이스 | Task 9 |
| `ConfigurableListableBeanFactory.destroySingletons()` 노출 | `sfs-beans` 인터페이스 | Task 9 |
| `BeanDefinitionRegistry` 인터페이스 | `sfs-beans` | Task 15, 16 |

발견 즉시 별도 커밋 (`feat(sfs-beans): <누락 항목> 노출`) → 본 Plan 진행 재개.
