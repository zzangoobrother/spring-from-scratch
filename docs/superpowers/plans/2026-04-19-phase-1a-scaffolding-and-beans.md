# Phase 1A — Scaffolding & Bean Factory 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gradle 멀티모듈 골격을 세우고 `sfs-core`/`sfs-beans` 두 모듈을 TDD로 구현하여, 수동 `BeanDefinition` 등록 방식으로 동작하는 `DefaultListableBeanFactory`를 완성한다.

**Architecture:** 4-모듈 Gradle 프로젝트(`sfs-core`, `sfs-beans`, `sfs-context`, `sfs-samples`) 중 앞 2개만 구현. 공개 API는 Spring과 동일 시그니처, 내부는 Java 25 idioms(record, sealed interface, pattern matching switch). 3-level cache로 세터/필드 순환 참조 해결, `FactoryBean`·`BeanPostProcessor`·`BeanFactoryPostProcessor`·Aware·`InitializingBean`/`DisposableBean` 확장점 완비.

**Tech Stack:** Java 25 LTS, Gradle 8.x (Kotlin DSL), ASM 9.x (애노테이션 메타데이터 스캔), JUnit 5, AssertJ 3.x.

**Selected Spec:** `docs/superpowers/specs/2026-04-19-ioc-container-design.md`

**End state:** 아래 통합 테스트가 통과하는 작동 가능 BeanFactory. 애노테이션 스캔은 Plan 1B에서 추가.

```java
// Plan 1A 종료 시점에 이런 테스트가 통과해야 함
var factory = new DefaultListableBeanFactory();
factory.registerBeanDefinition("userService", new BeanDefinition(UserService.class));
factory.registerBeanDefinition("orderService", new BeanDefinition(OrderService.class));
factory.preInstantiateSingletons();
assertThat(factory.getBean("userService", UserService.class)).isNotNull();
```

---

## 섹션 A: Gradle 멀티모듈 스캐폴딩 (Task 1~3)

### Task 1: 루트 Gradle 설정

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: `settings.gradle.kts` 작성**

```kotlin
rootProject.name = "spring-from-scratch"

include(
    "sfs-core",
    "sfs-beans",
    "sfs-context",
)
// sfs-samples는 Plan 1C에서 추가
```

- [ ] **Step 2: `gradle/libs.versions.toml` 작성 (버전 카탈로그)**

```toml
[versions]
junit = "5.11.3"
assertj = "3.26.3"
asm = "9.7.1"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
asm-commons = { module = "org.ow2.asm:asm-commons", version.ref = "asm" }
```

- [ ] **Step 3: 루트 `build.gradle.kts` 작성**

```kotlin
plugins {
    java
}

allprojects {
    group = "com.choisk.sfs"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "--enable-preview"))
        options.release = 25
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    dependencies {
        val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
        "testImplementation"(platform(libs.junit.bom))
        "testImplementation"(libs.junit.jupiter)
        "testImplementation"(libs.assertj.core)
    }
}
```

- [ ] **Step 4: 검증 커맨드**

```bash
cd ~/IdeaProjects/spring-from-scratch
./gradlew --version
# 예상: Gradle 8.x 확인, Java toolchain 25로 인식
```

(Gradle wrapper 부재 시 `gradle wrapper --gradle-version=8.11` 먼저 실행. Homebrew에 `gradle` 없으면 `brew install gradle`)

- [ ] **Step 5: 커밋**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml gradle/wrapper/ gradlew gradlew.bat
git commit -m "chore: Gradle 멀티모듈 루트 설정 (Java 25 toolchain)"
```

---

### Task 2: `sfs-core` 모듈 스캐폴딩

**Files:**
- Create: `sfs-core/build.gradle.kts`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/package-info.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/PackageSmokeTest.java`

- [ ] **Step 1: `sfs-core/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(libs.asm)
    api(libs.asm.commons)
}
```

> `api`로 노출하는 이유: `sfs-beans`가 `AnnotationMetadata`를 받을 때 ASM 타입이 메서드 시그니처에 나올 수 있어서. 내부 구현만 쓰면 `implementation`으로 내리기.

- [ ] **Step 2: `package-info.java` (모듈 의도 문서화)**

```java
/**
 * Spring From Scratch의 공통 유틸리티 계층.
 *
 * <p>이 패키지의 타입들은 다른 모든 모듈이 의존하지만,
 * 이 패키지 자체는 프로젝트 내 다른 모듈에 의존하지 않는다.
 * (ASM과 같은 외부 라이브러리만 의존)
 *
 * <p>Spring 원본 매핑: {@code spring-core}.
 */
package com.choisk.sfs.core;
```

- [ ] **Step 3: 스모크 테스트 작성 (모듈이 빌드되는지 확인용)**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PackageSmokeTest {
    @Test
    void packageIsLoadable() {
        assertThat(getClass().getPackageName()).isEqualTo("com.choisk.sfs.core");
    }
}
```

- [ ] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-core:test
```
예상: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "chore(sfs-core): 모듈 스캐폴딩 및 ASM 의존성 설정"
```

---

### Task 3: `sfs-beans` 모듈 스캐폴딩

**Files:**
- Create: `sfs-beans/build.gradle.kts`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/package-info.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/PackageSmokeTest.java`

- [ ] **Step 1: `sfs-beans/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-core"))
}
```

- [ ] **Step 2: `package-info.java`**

```java
/**
 * 빈 정의(BeanDefinition)·빈 팩토리(BeanFactory) 계층.
 *
 * <p>Spring 원본 매핑: {@code spring-beans}. 이 모듈은 컨테이너 코어 로직을 담당하며
 * 애노테이션 스캐닝·ApplicationContext 같은 상위 개념은 {@code sfs-context}에 있다.
 */
package com.choisk.sfs.beans;
```

- [ ] **Step 3: 스모크 테스트**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PackageSmokeTest {
    @Test
    void packageIsLoadable() {
        assertThat(getClass().getPackageName()).isEqualTo("com.choisk.sfs.beans");
    }
}
```

- [ ] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-beans:test
```
예상: PASS.

- [ ] **Step 5: 커밋**

```bash
git add sfs-beans/
git commit -m "chore(sfs-beans): 모듈 스캐폴딩 (sfs-core 의존)"
```

---

## 섹션 B: `sfs-core` 유틸리티 (Task 4~8)

### Task 4: `BeansException` sealed 계층

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/BeansException.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/BeansExceptionTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BeansExceptionTest {
    @Test
    void isRuntimeException() {
        var ex = new BeansException("boom") {};
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void preservesCauseChain() {
        var cause = new IllegalStateException("root cause");
        var ex = new BeansException("wrapper", cause) {};
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
```

- [ ] **Step 2: 테스트 실행 (컴파일 실패)**

```bash
./gradlew :sfs-core:test --tests BeansExceptionTest
```
예상: FAIL (BeansException 미존재).

- [ ] **Step 3: `BeansException` 구현**

```java
package com.choisk.sfs.core;

/**
 * Spring From Scratch 컨테이너의 모든 예외 루트.
 * <p>sealed: 허용된 서브타입만 존재할 수 있다. 외부 확장이 필요하면 이 계층에 명시적 추가.
 * <p>Spring 원본: {@code org.springframework.beans.BeansException}.
 */
public abstract sealed class BeansException extends RuntimeException
        permits NoSuchBeanDefinitionException,
                NoUniqueBeanDefinitionException,
                BeanDefinitionStoreException,
                BeanCreationException,
                BeanNotOfRequiredTypeException,
                FactoryBeanNotInitializedException,
                BeanIsNotAFactoryException {

    protected BeansException(String message) {
        super(message);
    }

    protected BeansException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: 모든 permit 서브타입 스텁 생성**

각각 별도 파일:

`NoSuchBeanDefinitionException.java`:
```java
package com.choisk.sfs.core;

public final class NoSuchBeanDefinitionException extends BeansException {
    public NoSuchBeanDefinitionException(String message) { super(message); }
}
```

`NoUniqueBeanDefinitionException.java`:
```java
package com.choisk.sfs.core;

import java.util.List;

public final class NoUniqueBeanDefinitionException extends BeansException {
    private final List<String> candidates;

    public NoUniqueBeanDefinitionException(String message, List<String> candidates) {
        super(message);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> getCandidates() { return candidates; }
}
```

`BeanDefinitionStoreException.java`:
```java
package com.choisk.sfs.core;

public final class BeanDefinitionStoreException extends BeansException {
    public BeanDefinitionStoreException(String message) { super(message); }
    public BeanDefinitionStoreException(String message, Throwable cause) { super(message, cause); }
}
```

`BeanCreationException.java` (non-sealed: Phase 2+에서 서브타입 추가):
```java
package com.choisk.sfs.core;

public non-sealed class BeanCreationException extends BeansException {
    private final String beanName;

    public BeanCreationException(String beanName, String message) {
        super("Error creating bean '%s': %s".formatted(beanName, message));
        this.beanName = beanName;
    }

    public BeanCreationException(String beanName, String message, Throwable cause) {
        super("Error creating bean '%s': %s".formatted(beanName, message), cause);
        this.beanName = beanName;
    }

    public String getBeanName() { return beanName; }
}
```

`BeanNotOfRequiredTypeException.java`:
```java
package com.choisk.sfs.core;

public final class BeanNotOfRequiredTypeException extends BeansException {
    public BeanNotOfRequiredTypeException(String beanName, Class<?> required, Class<?> actual) {
        super("Bean '%s' is of type %s, not %s".formatted(beanName, actual.getName(), required.getName()));
    }
}
```

`FactoryBeanNotInitializedException.java`:
```java
package com.choisk.sfs.core;

public final class FactoryBeanNotInitializedException extends BeansException {
    public FactoryBeanNotInitializedException(String factoryBeanName) {
        super("FactoryBean '%s' returned null from getObject()".formatted(factoryBeanName));
    }
}
```

`BeanIsNotAFactoryException.java`:
```java
package com.choisk.sfs.core;

public final class BeanIsNotAFactoryException extends BeansException {
    public BeanIsNotAFactoryException(String beanName, Class<?> actualType) {
        super("Bean '%s' is not a FactoryBean (actual type: %s)".formatted(beanName, actualType.getName()));
    }
}
```

- [ ] **Step 5: 테스트 패스 확인 & 커밋**

```bash
./gradlew :sfs-core:test --tests BeansExceptionTest
git add sfs-core/
git commit -m "feat(sfs-core): BeansException sealed 예외 계층 추가"
```

---

### Task 5: `Assert` 유틸리티

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/Assert.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AssertTest.java`

- [ ] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssertTest {
    @Test
    void notNull_throwsWhenNull() {
        assertThatThrownBy(() -> Assert.notNull(null, "target"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("target must not be null");
    }

    @Test
    void notNull_returnsValueWhenPresent() {
        var result = Assert.notNull("hello", "target");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void hasText_throwsOnBlank() {
        assertThatThrownBy(() -> Assert.hasText("   ", "name"))
                .hasMessageContaining("must have text");
    }

    @Test
    void isAssignable_validatesSuperType() {
        Assert.isAssignable(Number.class, Integer.class); // no throw
        assertThatThrownBy(() -> Assert.isAssignable(String.class, Integer.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 FAIL 확인**

```bash
./gradlew :sfs-core:test --tests AssertTest
```

- [ ] **Step 3: 구현**

```java
package com.choisk.sfs.core;

/**
 * Spring의 {@code org.springframework.util.Assert}와 동등한 조건 검증 유틸.
 * <p>모든 메서드는 실패 시 {@link IllegalArgumentException}을 던진다.
 */
public final class Assert {

    private Assert() {}

    public static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    public static String hasText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must have text");
        }
        return value;
    }

    public static void isAssignable(Class<?> superType, Class<?> subType) {
        notNull(superType, "superType");
        notNull(subType, "subType");
        if (!superType.isAssignableFrom(subType)) {
            throw new IllegalArgumentException(
                    subType.getName() + " is not assignable to " + superType.getName());
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
```

- [ ] **Step 4: 테스트 PASS 확인**

```bash
./gradlew :sfs-core:test --tests AssertTest
```

- [ ] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): Assert 검증 유틸리티 추가"
```

---

## 섹션 A/B 마무리 체크포인트

**여기까지 완료 시 확인:**

```bash
./gradlew build
```
예상: BUILD SUCCESSFUL, `sfs-core`·`sfs-beans` 모두 빌드 & 테스트 통과.

**현재 파일 트리:**
```
spring-from-scratch/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── sfs-core/
│   ├── build.gradle.kts
│   └── src/{main,test}/java/com/choisk/sfs/core/
│       ├── Assert.java + AssertTest
│       ├── BeansException.java + 7개 서브타입
│       └── BeansExceptionTest
└── sfs-beans/
    ├── build.gradle.kts
    └── src/{main,test}/java/com/choisk/sfs/beans/
        └── PackageSmokeTest
```

---

## 🚧 플랜 계속 작성 중

이 파일은 Plan 1A의 **섹션 A & B 일부**까지만 작성되어 있습니다. 나머지 섹션은 검토 승인 후 이어서 작성합니다:

- **섹션 B 나머지 (Task 6~8):** `ClassUtils`, `ReflectionUtils`, `ClassPathScanner` + ASM 기반 `AnnotationMetadataReader`
- **섹션 C (Task 9~14):** `sfs-beans` 메타데이터 — `Scope`, `AutowireMode`, `BeanReference`, `PropertyValue`, `BeanDefinition`
- **섹션 D (Task 15~19):** `BeanFactory` 인터페이스 계층 + `FactoryBean` + 확장점 인터페이스
- **섹션 E (Task 20~23):** `DefaultSingletonBeanRegistry` — 3-level cache, 순환 참조 감지, destruction 콜백
- **섹션 F (Task 24~29):** `AbstractBeanFactory` + `AbstractAutowireCapableBeanFactory` + `DefaultListableBeanFactory`
- **섹션 G (Task 30~33):** 통합 테스트 — 3-level cache 순환 참조, FactoryBean, BPP 순서, Plan 1A DoD 검증

**Task 수 총합 예상:** 33개 / **Step 수 예상:** ~170개
