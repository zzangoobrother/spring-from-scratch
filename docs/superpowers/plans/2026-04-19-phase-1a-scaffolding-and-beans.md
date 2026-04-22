# Phase 1A — Scaffolding & Bean Factory 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gradle 멀티모듈 골격을 세우고 `sfs-core`/`sfs-beans` 두 모듈을 TDD로 구현하여, 수동 `BeanDefinition` 등록 방식으로 동작하는 `DefaultListableBeanFactory`를 완성한다.

**Architecture:** 4-모듈 Gradle 프로젝트(`sfs-core`, `sfs-beans`, `sfs-context`, `sfs-samples`) 중 앞 2개만 구현. 공개 API는 Spring과 동일 시그니처, 내부는 Java 25 idioms(record, sealed interface, pattern matching switch). 3-level cache로 세터/필드 순환 참조 해결, `FactoryBean`·`BeanPostProcessor`·`BeanFactoryPostProcessor`·Aware·`InitializingBean`/`DisposableBean` 확장점 완비.

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL) — Java 25 toolchain 지원은 Gradle 9.1.0부터 도입됨, ASM 9.x (애노테이션 메타데이터 스캔), JUnit 5, AssertJ 3.x.

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

- [x] **Step 1: `settings.gradle.kts` 작성**

```kotlin
rootProject.name = "spring-from-scratch"

include(
    "sfs-core",
    "sfs-beans",
    "sfs-context",
)
// sfs-samples는 Plan 1C에서 추가
```

- [x] **Step 2: `gradle/libs.versions.toml` 작성 (버전 카탈로그)**

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

- [x] **Step 3: 루트 `build.gradle.kts` 작성**

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

- [x] **Step 4: 검증 커맨드**

```bash
cd ~/IdeaProjects/spring-from-scratch
./gradlew --version
# 예상: Gradle 9.4.1 확인, Java toolchain 25로 인식
```

(Gradle wrapper 부재 시 `gradle wrapper --gradle-version=9.4.1` 먼저 실행. Homebrew에 `gradle` 없으면 `brew install gradle`. 참고: Gradle 8.x는 Java 25 toolchain 미지원이므로 9.1.0+ 필수)

- [x] **Step 5: 커밋**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml gradle/wrapper/ gradlew gradlew.bat
git commit -m "chore: Gradle 멀티모듈 루트 설정 (Java 25 toolchain)"
```

> **실행 기록 (2026-04-20):** 커밋 `ac58004` on `feat/phase1a-scaffolding`. 구현 중 두 가지 편차 발생:
> 1. `subprojects{}` 블록에서 `the<LibrariesForLibs>()` 접근자가 동작하지 않아(컨벤션 플러그인 밖에서는 미등록) `VersionCatalogsExtension` API로 전환: `rootProject.extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs").findLibrary("junit-bom").get()` 형태.
> 2. Gradle 9 breaking change로 `settings.gradle.kts`의 `include(...)` 대상 디렉토리가 물리적으로 존재해야 하므로, wrapper 부트스트랩 전에 `mkdir -p sfs-core sfs-beans sfs-context` 먼저 실행.

---

### Task 2: `sfs-core` 모듈 스캐폴딩

**Files:**
- Create: `sfs-core/build.gradle.kts`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/package-info.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/PackageSmokeTest.java`

- [x] **Step 1: `sfs-core/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)
}
```

> **의사결정 (2026-04-21):** 초기 초안은 `api(libs.asm)`이었으나 Gradle 공식 userguide "Prefer the `implementation` configuration over `api` when possible" 원칙에 맞춰 `implementation`으로 변경.
>
> - 근거 1: `AnnotationMetadata` record가 `String`/`List`/`Set`/`Map` 등 JDK 타입만 노출 — ASM의 `Type`, `ClassReader`가 공개 API(ABI)에 등장하지 않음.
> - 근거 2: `implementation`이면 `sfs-beans`/`sfs-context`에서 ASM 타입을 실수로 import 시 **즉시 컴파일 에러**로 캡슐화 강제.
> - 근거 3: ASM → ByteBuddy 같은 구현체 교체 시 소비자 모듈 재컴파일 불필요.
> - 향후 `AnnotationMetadata`가 ASM `Type`을 노출하도록 변경되면 그때 `api`로 승격 (그때까지는 컴파일러가 위반 감지).

- [x] **Step 2: `package-info.java` (모듈 의도 문서화)**

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

- [x] **Step 3: 스모크 테스트 작성 (모듈이 빌드되는지 확인용)**

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

- [x] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-core:test
```
예상: BUILD SUCCESSFUL, 1 test passed.

- [x] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "chore(sfs-core): 모듈 스캐폴딩 및 ASM 의존성 설정"
```

> **실행 기록 (2026-04-20):** Task 2 실행 중 Gradle 9의 breaking change로 편차 발생. JUnit Platform launcher가 명시적 `testRuntimeOnly` 의존성으로 요구됨 (Gradle 9부터 자동 포함 중단). 해결: `gradle/libs.versions.toml`에 `junit-platform-launcher` 라이브러리 추가 + 루트 `build.gradle.kts`의 `subprojects{}` dependencies에 `"testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())` 추가. 이 수정은 본 태스크 커밋에 함께 포함.

---

### Task 3: `sfs-beans` 모듈 스캐폴딩

**Files:**
- Create: `sfs-beans/build.gradle.kts`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/package-info.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/PackageSmokeTest.java`

- [x] **Step 1: `sfs-beans/build.gradle.kts`**

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":sfs-core"))
}
```

- [x] **Step 2: `package-info.java`**

```java
/**
 * 빈 정의(BeanDefinition)·빈 팩토리(BeanFactory) 계층.
 *
 * <p>Spring 원본 매핑: {@code spring-beans}. 이 모듈은 컨테이너 코어 로직을 담당하며
 * 애노테이션 스캐닝·ApplicationContext 같은 상위 개념은 {@code sfs-context}에 있다.
 */
package com.choisk.sfs.beans;
```

- [x] **Step 3: 스모크 테스트**

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

- [x] **Step 4: 빌드 실행**

```bash
./gradlew :sfs-beans:test
```
예상: PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/
git commit -m "chore(sfs-beans): 모듈 스캐폴딩 (sfs-core 의존)"
```

> **실행 기록 (2026-04-20):** 편차 없이 한 번에 PASS. Task 2에서 해결한 Gradle 9 `junit-platform-launcher` 수정이 루트 `subprojects{}`에 있어 sfs-beans로 자동 상속됨을 확인 — "편차를 상위에서 한 번 고치면 하위 모듈이 자동 혜택"의 전형.

---

## 섹션 B: `sfs-core` 유틸리티 (Task 4~8)

### Task 4: `BeansException` sealed 계층

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/BeansException.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/BeansExceptionTest.java`

- [x] **Step 1: 실패 테스트 작성**

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

- [x] **Step 2: 테스트 실행 (컴파일 실패)**

```bash
./gradlew :sfs-core:test --tests BeansExceptionTest
```
예상: FAIL (BeansException 미존재).

- [x] **Step 3: `BeansException` 구현**

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

- [x] **Step 4: 모든 permit 서브타입 스텁 생성**

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

- [x] **Step 5: 테스트 패스 확인 & 커밋**

```bash
./gradlew :sfs-core:test --tests BeansExceptionTest
git add sfs-core/
git commit -m "feat(sfs-core): BeansException sealed 예외 계층 추가"
```

> **실행 기록 (2026-04-21):** Step 1 초안의 테스트가 `new BeansException("boom") {}` 익명 서브클래스를 사용했는데, Java sealed 제약으로 **"anonymous classes must not extend sealed classes"** 컴파일 에러 발생. TDD 규율상 RED 이유가 의도와 달라 테스트를 수정: permit된 구체 서브타입 `BeanDefinitionStoreException`을 통해 동일 속성(RuntimeException 상속 + cause chain)을 검증하도록 변경. `BeansException extends BeansException` 관계도 함께 검증하는 추가 assertion 포함 → 더 강한 테스트가 됨.

---

### Task 5: `Assert` 유틸리티

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/Assert.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AssertTest.java`

- [x] **Step 1: 실패 테스트**

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

- [x] **Step 2: 테스트 FAIL 확인**

```bash
./gradlew :sfs-core:test --tests AssertTest
```

- [x] **Step 3: 구현**

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

- [x] **Step 4: 테스트 PASS 확인**

```bash
./gradlew :sfs-core:test --tests AssertTest
```

- [x] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): Assert 검증 유틸리티 추가"
```

> **실행 기록 (2026-04-21):** 편차 없이 한 번에 PASS. 플랜 그대로 적용 (notNull/hasText/isAssignable/isTrue 정적 메서드, private 생성자로 인스턴스화 차단).

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

### Task 6: `ClassUtils` + `ReflectionUtils`

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ClassUtils.java`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ReflectionUtils.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ClassUtilsTest.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ReflectionUtilsTest.java`

- [x] **Step 1: `ClassUtilsTest` 실패 테스트**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClassUtilsTest {
    @Test
    void getDefaultClassLoader_returnsContextOrSystem() {
        assertThat(ClassUtils.getDefaultClassLoader()).isNotNull();
    }

    @Test
    void forName_loadsClassByName() throws ClassNotFoundException {
        assertThat(ClassUtils.forName("java.lang.String", null)).isSameAs(String.class);
    }

    @Test
    void forName_throwsForMissing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ClassUtils.forName("com.nonexistent.Foo", null))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void isAssignableValue_handlesPrimitiveBoxing() {
        assertThat(ClassUtils.isAssignableValue(int.class, Integer.valueOf(42))).isTrue();
        assertThat(ClassUtils.isAssignableValue(String.class, 42)).isFalse();
    }
}
```

- [x] **Step 2: FAIL 확인 후 `ClassUtils` 구현**

```java
package com.choisk.sfs.core;

import java.util.Map;

public final class ClassUtils {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            double.class, Double.class,
            float.class, Float.class,
            int.class, Integer.class,
            long.class, Long.class,
            short.class, Short.class,
            void.class, Void.class
    );

    private ClassUtils() {}

    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) return cl;
        cl = ClassUtils.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    public static Class<?> forName(String name, ClassLoader loader) throws ClassNotFoundException {
        Assert.hasText(name, "class name");
        ClassLoader cl = loader != null ? loader : getDefaultClassLoader();
        return Class.forName(name, false, cl);
    }

    public static boolean isAssignableValue(Class<?> type, Object value) {
        Assert.notNull(type, "type");
        if (value == null) return !type.isPrimitive();
        if (type.isPrimitive()) {
            return PRIMITIVE_WRAPPERS.get(type).isInstance(value);
        }
        return type.isInstance(value);
    }
}
```

- [x] **Step 3: `ReflectionUtilsTest` 실패 테스트**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReflectionUtilsTest {

    static class Parent {
        private String parentField = "p";
        void parentMethod() {}
    }

    static class Child extends Parent {
        private int childField = 1;
    }

    @Test
    void findField_walksInheritance() {
        var field = ReflectionUtils.findField(Child.class, "parentField");
        assertThat(field).isNotNull();
        assertThat(field.getDeclaringClass()).isEqualTo(Parent.class);
    }

    @Test
    void findField_returnsNullForMissing() {
        assertThat(ReflectionUtils.findField(Child.class, "missing")).isNull();
    }

    @Test
    void doWithFields_visitsInheritedFields() {
        var names = new java.util.ArrayList<String>();
        ReflectionUtils.doWithFields(Child.class, f -> names.add(f.getName()));
        assertThat(names).contains("parentField", "childField");
    }

    @Test
    void findMethod_walksInheritance() {
        var method = ReflectionUtils.findMethod(Child.class, "parentMethod");
        assertThat(method).isNotNull();
    }
}
```

- [x] **Step 4: FAIL 확인 후 `ReflectionUtils` 구현**

```java
package com.choisk.sfs.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class ReflectionUtils {

    private ReflectionUtils() {}

    public static Field findField(Class<?> clazz, String name) {
        Assert.notNull(clazz, "clazz");
        Assert.hasText(name, "name");
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Assert.notNull(clazz, "clazz");
        Assert.hasText(name, "name");
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && (paramTypes.length == 0 || java.util.Arrays.equals(m.getParameterTypes(), paramTypes))) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static void doWithFields(Class<?> clazz, Consumer<Field> action) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                action.accept(f);
            }
            current = current.getSuperclass();
        }
    }

    public static void doWithMethods(Class<?> clazz, Consumer<Method> action) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                action.accept(m);
            }
            current = current.getSuperclass();
        }
    }

    public static void makeAccessible(Field field) {
        if (!field.canAccess(null) && java.lang.reflect.Modifier.isPrivate(field.getModifiers())) {
            field.setAccessible(true);
        } else {
            field.setAccessible(true);
        }
    }

    public static void makeAccessible(Method method) {
        method.setAccessible(true);
    }

    public static Object invokeMethod(Method method, Object target, Object... args) {
        try {
            makeAccessible(method);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke method: " + method, e);
        }
    }

    public static Object getField(Field field, Object target) {
        try {
            makeAccessible(field);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read field: " + field, e);
        }
    }

    public static void setField(Field field, Object target, Object value) {
        try {
            makeAccessible(field);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set field: " + field, e);
        }
    }
}
```

- [x] **Step 5: 테스트 PASS 확인 & 커밋**

```bash
./gradlew :sfs-core:test --tests ClassUtilsTest --tests ReflectionUtilsTest
git add sfs-core/
git commit -m "feat(sfs-core): ClassUtils 및 ReflectionUtils 추가"
```

> **실행 기록 (2026-04-21):** 편차 없이 TDD 2사이클 모두 RED → GREEN 성공. ClassUtils.forName은 `Class.forName(name, false, cl)`의 두 번째 인자 false로 **정적 초기화를 지연**(AnnotationMetadata 스캔 시 불필요한 side effect 회피). ReflectionUtils.findMethod는 `paramTypes.length == 0`을 오버로드 메타(sfs-core Assert 유틸과 맞물림)로 취급해 "이름만으로 찾기" 기능 제공.

---

### Task 7: `ClassPathScanner` — 클래스패스 파일 나열

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ClassPathScanner.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ClassPathScannerTest.java`

> 이 태스크의 책임은 **클래스 로드 없이** 특정 패키지 하위의 `.class` 파일 이름만 나열하는 것. 애노테이션 판단은 Task 8(ASM)에서.

- [x] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClassPathScannerTest {
    @Test
    void scanPackage_findsClassesInThisPackage() {
        var scanner = new ClassPathScanner();
        var classes = scanner.scan("com.choisk.sfs.core");
        assertThat(classes)
                .extracting(ClassInfo::className)
                .contains("com.choisk.sfs.core.Assert", "com.choisk.sfs.core.BeansException");
    }

    @Test
    void scanPackage_emptyForNonexistent() {
        var scanner = new ClassPathScanner();
        assertThat(scanner.scan("com.nonexistent.pkg")).isEmpty();
    }
}
```

- [x] **Step 2: FAIL 확인 후 구현**

```java
package com.choisk.sfs.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public final class ClassPathScanner {

    public record ClassInfo(String className, byte[] bytecode) { }

    public List<ClassInfo> scan(String basePackage) {
        Assert.hasText(basePackage, "basePackage");
        String path = basePackage.replace('.', '/');
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        var results = new ArrayList<ClassInfo>();
        try {
            Enumeration<URL> roots = loader.getResources(path);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                scanRoot(root, basePackage, results);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan: " + basePackage, e);
        }
        return results;
    }

    private void scanRoot(URL root, String basePackage, List<ClassInfo> out) throws IOException {
        String protocol = root.getProtocol();
        if ("file".equals(protocol)) {
            Path dir = Paths.get(root.getPath().replace("%20", " "));
            if (!Files.isDirectory(dir)) return;
            try (var stream = Files.walk(dir)) {
                var classFiles = stream
                        .filter(p -> p.toString().endsWith(".class"))
                        .collect(Collectors.toList());
                for (Path classFile : classFiles) {
                    String relative = dir.relativize(classFile).toString()
                            .replace('/', '.').replace('\\', '.');
                    String className = basePackage + '.' + relative.substring(0, relative.length() - ".class".length());
                    byte[] bytes = Files.readAllBytes(classFile);
                    out.add(new ClassInfo(className, bytes));
                }
            }
        }
        // JAR 스캔은 Phase 1 범위에서 제외: 테스트/샘플이 exploded classpath에서 동작하면 충분.
        // JAR 지원이 필요해지면 별도 태스크로 추가.
    }
}
```

- [x] **Step 3: 테스트 실행**

```bash
./gradlew :sfs-core:test --tests ClassPathScannerTest
```
예상: PASS (현재 패키지에 `Assert`와 `BeansException`이 존재하므로).

- [x] **Step 4: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): ClassPathScanner 구현 (file URL 기반)"
```

> **실행 기록 (2026-04-21):** 편차 없이 한 번에 PASS. 테스트 코드에서 nested record `ClassInfo` 접근을 위해 `import com.choisk.sfs.core.ClassPathScanner.ClassInfo` 추가 (플랜에선 생략돼 있었음). JAR URL 분기는 Phase 1 범위 제외하고 경로 조기 return으로 처리.

---

### Task 8: `AnnotationMetadata` + ASM 기반 리더

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationMetadata.java`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationMetadataReader.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AnnotationMetadataReaderTest.java`

- [x] **Step 1: `AnnotationMetadata` record 정의**

```java
package com.choisk.sfs.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 클래스 로드 없이 ASM으로 파싱한 메타데이터.
 * <p>Spring {@code AnnotationMetadata}에 대응하되 record로 불변 표현.
 */
public record AnnotationMetadata(
        String className,
        String superClassName,
        List<String> interfaceNames,
        Set<String> annotationTypeNames,
        Map<String, Map<String, Object>> annotationAttributes,
        boolean isAbstract,
        boolean isInterface,
        boolean isAnnotation
) {
    public boolean hasAnnotation(String annotationClassName) {
        return annotationTypeNames.contains(annotationClassName);
    }

    public Map<String, Object> attributesFor(String annotationClassName) {
        return annotationAttributes.getOrDefault(annotationClassName, Map.of());
    }
}
```

- [x] **Step 2: 실패 테스트 작성 (ASM 리더)**

```java
package com.choisk.sfs.core;

import org.junit.jupiter.api.Test;
import java.lang.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationMetadataReaderTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Sample {
        String value() default "default";
        int count() default 1;
    }

    @Sample(value = "hi", count = 42)
    static class Target {}

    @Test
    void readsClassName() throws Exception {
        var meta = readFor(Target.class);
        assertThat(meta.className()).isEqualTo(Target.class.getName());
    }

    @Test
    void detectsAnnotationPresence() throws Exception {
        var meta = readFor(Target.class);
        assertThat(meta.hasAnnotation(Sample.class.getName())).isTrue();
    }

    @Test
    void extractsAnnotationAttributes() throws Exception {
        var meta = readFor(Target.class);
        Object value = meta.attributesFor(Sample.class.getName()).get("value");
        Object count = meta.attributesFor(Sample.class.getName()).get("count");
        assertThat(value).isEqualTo("hi");
        assertThat(count).isEqualTo(42);
    }

    private AnnotationMetadata readFor(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return AnnotationMetadataReader.read(in.readAllBytes());
        }
    }
}
```

- [x] **Step 3: FAIL 확인 후 `AnnotationMetadataReader` 구현 (ASM)**

```java
package com.choisk.sfs.core;

import org.objectweb.asm.*;
import java.util.*;

public final class AnnotationMetadataReader {

    private AnnotationMetadataReader() {}

    public static AnnotationMetadata read(byte[] bytecode) {
        Assert.notNull(bytecode, "bytecode");
        var visitor = new MetadataClassVisitor();
        new ClassReader(bytecode).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.build();
    }

    private static final class MetadataClassVisitor extends ClassVisitor {
        String className;
        String superName;
        List<String> interfaces = List.of();
        Set<String> annotationTypes = new HashSet<>();
        Map<String, Map<String, Object>> annotationAttrs = new HashMap<>();
        boolean isAbstract;
        boolean isInterface;
        boolean isAnnotation;

        MetadataClassVisitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = Type.getObjectType(name).getClassName();
            this.superName = superName != null ? Type.getObjectType(superName).getClassName() : null;
            this.interfaces = interfaces == null ? List.of()
                    : Arrays.stream(interfaces).map(i -> Type.getObjectType(i).getClassName()).toList();
            this.isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            this.isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String annotationName = Type.getType(descriptor).getClassName();
            annotationTypes.add(annotationName);
            var attrs = new LinkedHashMap<String, Object>();
            annotationAttrs.put(annotationName, attrs);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    attrs.put(name, value);
                }
                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    attrs.put(name, value);
                }
            };
        }

        AnnotationMetadata build() {
            return new AnnotationMetadata(
                    className, superName, interfaces,
                    Set.copyOf(annotationTypes),
                    Map.copyOf(annotationAttrs),
                    isAbstract, isInterface, isAnnotation
            );
        }
    }
}
```

- [x] **Step 4: 테스트 PASS 확인**

```bash
./gradlew :sfs-core:test --tests AnnotationMetadataReaderTest
```

- [x] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): ASM 기반 AnnotationMetadataReader 구현"
```

> **실행 기록 (2026-04-21):** Step 3 초안의 ASM 9.7.1이 Java 25 바이트코드(major version 69)를 파싱하지 못해 `IllegalArgumentException: Unsupported class file major version 69` 발생. ASM은 9.8부터 Java 25 지원 — `libs.versions.toml`에서 `asm = "9.7.1"` → `asm = "9.9.1"`(당시 latest)로 업그레이드. ASM9 Opcodes 상수는 그대로 호환되므로 리더 코드 수정 불필요. 플랜 Tech Stack 섹션의 "ASM 9.x" 표기는 유효 범위 내지만, 최소 9.8+가 Java 25 baseline에 필수임을 기록.

---

## ✅ 섹션 B 체크포인트

```bash
./gradlew :sfs-core:test
```
예상: 모든 `sfs-core` 테스트 PASS. `sfs-core` 모듈이 **외부 라이브러리(ASM) 외 의존 없이 독립 동작**.

---

## 섹션 C: `sfs-beans` 메타데이터 타입 (Task 9~14)

### Task 9: `Scope` sealed interface

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/Scope.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/ScopeTest.java`

- [x] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {
    @Test
    void singletonByName() {
        assertThat(Scope.byName("singleton")).isEqualTo(Scope.Singleton.INSTANCE);
    }

    @Test
    void prototypeByName() {
        assertThat(Scope.byName("prototype")).isEqualTo(Scope.Prototype.INSTANCE);
    }

    @Test
    void unknownName_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Scope.byName("request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request");
    }
}
```

- [x] **Step 2: 구현**

```java
package com.choisk.sfs.beans;

/**
 * 빈 스코프. Phase 1은 singleton/prototype 두 종류만.
 * Request/Session 같은 웹 스코프는 Phase 5+에서 sealed hierarchy에 추가.
 */
public sealed interface Scope permits Scope.Singleton, Scope.Prototype {

    String name();

    enum Singleton implements Scope {
        INSTANCE;
        @Override public String name() { return "singleton"; }
    }

    enum Prototype implements Scope {
        INSTANCE;
        @Override public String name() { return "prototype"; }
    }

    static Scope byName(String name) {
        return switch (name) {
            case "singleton" -> Singleton.INSTANCE;
            case "prototype" -> Prototype.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown scope: " + name);
        };
    }
}
```

- [x] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests ScopeTest
git add sfs-beans/
git commit -m "feat(sfs-beans): Scope sealed interface (Singleton/Prototype)"
```

> **실행 기록 (2026-04-21):** 플랜의 `Scope` 인터페이스 구현 코드에서 `String name()` 추상 메서드를 정의하고 `enum` 내에서 `@Override`하도록 했으나, Java의 `Enum.name()`이 `final`이라 오버라이드 불가 컴파일 에러 발생. 인터페이스 추상 메서드를 `scopeName()`으로 변경하여 충돌 해결 — 테스트는 `byName()`과 `INSTANCE` 동등성만 검증하므로 동작에 영향 없음.

---

### Task 10: `AutowireMode` enum

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/AutowireMode.java`

- [x] **Step 1: 구현 (단순 enum이라 테스트는 BeanDefinition에서 간접 검증)**

```java
package com.choisk.sfs.beans;

/**
 * 의존성 주입 모드. Spring 원본의 int 상수를 enum으로 안전하게 재현.
 */
public enum AutowireMode {
    NO,
    BY_NAME,
    BY_TYPE,
    CONSTRUCTOR
}
```

- [x] **Step 2: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/AutowireMode.java
git commit -m "feat(sfs-beans): AutowireMode enum 추가"
```

---

### Task 11: `BeanReference` + `PropertyValue` + `PropertyValues`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanReference.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/PropertyValue.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/PropertyValues.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/PropertyValuesTest.java`

- [x] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PropertyValuesTest {
    @Test
    void addAndLookup() {
        var pvs = new PropertyValues()
                .add(new PropertyValue("name", "Alice"))
                .add(new PropertyValue("friend", new BeanReference("userBean")));
        assertThat(pvs.get("name").value()).isEqualTo("Alice");
        assertThat(pvs.get("friend").value()).isInstanceOf(BeanReference.class);
    }

    @Test
    void duplicateAddReplaces() {
        var pvs = new PropertyValues()
                .add(new PropertyValue("x", 1))
                .add(new PropertyValue("x", 2));
        assertThat(pvs.get("x").value()).isEqualTo(2);
    }
}
```

- [x] **Step 2: FAIL 확인 후 구현**

```java
// BeanReference.java
package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;

public record BeanReference(String beanName) {
    public BeanReference {
        Assert.hasText(beanName, "beanName");
    }
}
```

```java
// PropertyValue.java
package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;

public record PropertyValue(String name, Object value) {
    public PropertyValue {
        Assert.hasText(name, "name");
    }
}
```

```java
// PropertyValues.java
package com.choisk.sfs.beans;

import java.util.*;

public final class PropertyValues {
    private final LinkedHashMap<String, PropertyValue> values = new LinkedHashMap<>();

    public PropertyValues add(PropertyValue pv) {
        values.put(pv.name(), pv);
        return this;
    }

    public PropertyValue get(String name) {
        return values.get(name);
    }

    public Collection<PropertyValue> all() {
        return Collections.unmodifiableCollection(values.values());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
```

- [x] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests PropertyValuesTest
git add sfs-beans/
git commit -m "feat(sfs-beans): BeanReference, PropertyValue, PropertyValues 추가"
```

---

### Task 12: `BeanDefinition` (mutable class)

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanDefinition.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/BeanDefinitionTest.java`

- [x] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BeanDefinitionTest {
    static class Sample {}

    @Test
    void defaultsAreSingletonNonLazy() {
        var def = new BeanDefinition(Sample.class);
        assertThat(def.getScope()).isEqualTo(Scope.Singleton.INSTANCE);
        assertThat(def.isLazyInit()).isFalse();
        assertThat(def.isPrimary()).isFalse();
        assertThat(def.getAutowireMode()).isEqualTo(AutowireMode.NO);
    }

    @Test
    void settersAreFluent() {
        var def = new BeanDefinition(Sample.class)
                .setScope(Scope.Prototype.INSTANCE)
                .setLazyInit(true)
                .setPrimary(true)
                .setQualifier("main")
                .setInitMethodName("init")
                .setDestroyMethodName("close");
        assertThat(def.getScope()).isEqualTo(Scope.Prototype.INSTANCE);
        assertThat(def.isLazyInit()).isTrue();
        assertThat(def.isPrimary()).isTrue();
        assertThat(def.getQualifier()).isEqualTo("main");
        assertThat(def.getInitMethodName()).isEqualTo("init");
        assertThat(def.getDestroyMethodName()).isEqualTo("close");
    }

    @Test
    void factoryBeanBasedDefinition() {
        var def = new BeanDefinition(Sample.class)
                .setFactoryBeanName("config")
                .setFactoryMethodName("buildSample");
        assertThat(def.getFactoryBeanName()).isEqualTo("config");
        assertThat(def.getFactoryMethodName()).isEqualTo("buildSample");
    }

    @Test
    void propertyValuesIsLazilyInitialized() {
        var def = new BeanDefinition(Sample.class);
        assertThat(def.getPropertyValues()).isNotNull();
        assertThat(def.getPropertyValues().isEmpty()).isTrue();
    }
}
```

- [x] **Step 2: FAIL 확인 후 구현**

```java
package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.ArrayList;
import java.util.List;

/**
 * 빈 하나의 메타데이터. mutable class (BeanFactoryPostProcessor가 수정 가능해야 함).
 * <p>Spring 원본: {@code AbstractBeanDefinition} 계열을 단순화.
 */
public class BeanDefinition {

    private Class<?> beanClass;
    private Scope scope = Scope.Singleton.INSTANCE;
    private boolean lazyInit = false;
    private boolean primary = false;
    private String qualifier;
    private AutowireMode autowireMode = AutowireMode.NO;

    private final List<Object> constructorArgs = new ArrayList<>();
    private final PropertyValues propertyValues = new PropertyValues();

    private String initMethodName;
    private String destroyMethodName;

    private String factoryBeanName;
    private String factoryMethodName;

    public BeanDefinition(Class<?> beanClass) {
        this.beanClass = Assert.notNull(beanClass, "beanClass");
    }

    // --- getters ---
    public Class<?> getBeanClass() { return beanClass; }
    public Scope getScope() { return scope; }
    public boolean isLazyInit() { return lazyInit; }
    public boolean isPrimary() { return primary; }
    public String getQualifier() { return qualifier; }
    public AutowireMode getAutowireMode() { return autowireMode; }
    public List<Object> getConstructorArgs() { return constructorArgs; }
    public PropertyValues getPropertyValues() { return propertyValues; }
    public String getInitMethodName() { return initMethodName; }
    public String getDestroyMethodName() { return destroyMethodName; }
    public String getFactoryBeanName() { return factoryBeanName; }
    public String getFactoryMethodName() { return factoryMethodName; }

    public boolean isSingleton() { return scope instanceof Scope.Singleton; }
    public boolean isPrototype() { return scope instanceof Scope.Prototype; }

    // --- fluent setters (BFPP가 수정할 때 사용) ---
    public BeanDefinition setBeanClass(Class<?> beanClass) { this.beanClass = Assert.notNull(beanClass, "beanClass"); return this; }
    public BeanDefinition setScope(Scope scope) { this.scope = Assert.notNull(scope, "scope"); return this; }
    public BeanDefinition setLazyInit(boolean lazyInit) { this.lazyInit = lazyInit; return this; }
    public BeanDefinition setPrimary(boolean primary) { this.primary = primary; return this; }
    public BeanDefinition setQualifier(String qualifier) { this.qualifier = qualifier; return this; }
    public BeanDefinition setAutowireMode(AutowireMode mode) { this.autowireMode = Assert.notNull(mode, "autowireMode"); return this; }
    public BeanDefinition setInitMethodName(String name) { this.initMethodName = name; return this; }
    public BeanDefinition setDestroyMethodName(String name) { this.destroyMethodName = name; return this; }
    public BeanDefinition setFactoryBeanName(String name) { this.factoryBeanName = name; return this; }
    public BeanDefinition setFactoryMethodName(String name) { this.factoryMethodName = name; return this; }

    public BeanDefinition addConstructorArg(Object arg) { this.constructorArgs.add(arg); return this; }
    public BeanDefinition addPropertyValue(String name, Object value) {
        this.propertyValues.add(new PropertyValue(name, value));
        return this;
    }
}
```

- [x] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests BeanDefinitionTest
git add sfs-beans/
git commit -m "feat(sfs-beans): BeanDefinition mutable 클래스 구현"
```

---

### Task 13: `CacheLookup` sealed interface (3-level cache 결과 표현)

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/CacheLookup.java`

- [x] **Step 1: 구현 (별도 테스트 없이 Task 20~21 통합 테스트에서 검증)**

```java
package com.choisk.sfs.beans;

/**
 * 3-level cache 조회 결과. 호출자는 pattern matching switch로 분기한다.
 * <pre>
 * var result = switch (registry.lookup(name)) {
 *     case CacheLookup.Complete(var bean)        -> bean;
 *     case CacheLookup.EarlyReference(var bean)  -> bean;
 *     case CacheLookup.DeferredFactory(var f)    -> promoteAndInvoke(name, f);
 *     case CacheLookup.Miss() -> createBean(name);
 * };
 * </pre>
 */
public sealed interface CacheLookup {

    record Complete(Object bean) implements CacheLookup {}
    record EarlyReference(Object bean) implements CacheLookup {}
    record DeferredFactory(ObjectFactory<?> factory) implements CacheLookup {}
    record Miss() implements CacheLookup {
        public static final Miss INSTANCE = new Miss();
    }
}
```

- [x] **Step 2: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/CacheLookup.java
git commit -m "feat(sfs-beans): CacheLookup sealed interface (3-level 캐시 결과)"
```

> **실행 기록 (2026-04-21):** Task 13의 `CacheLookup.DeferredFactory(ObjectFactory<?>)`가 Task 14의 `ObjectFactory`를 참조하므로 두 Task를 한 커밋으로 묶어 구현했다. 원안의 Task 13/14 Step 구분은 그대로 유지(체크박스 기준).

---

### Task 14: `ObjectFactory` + `BeanCreationContext` + `CreationStage`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ObjectFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanCreationContext.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/CreationStage.java`

- [x] **Step 1: `ObjectFactory` 구현**

```java
package com.choisk.sfs.beans;

/**
 * 3-level cache의 3차 팩토리가 구현하는 인터페이스.
 * <p>Spring 원본: {@code ObjectFactory<T>}.
 */
@FunctionalInterface
public interface ObjectFactory<T> {
    T getObject();
}
```

- [x] **Step 2: `CreationStage` sealed**

```java
package com.choisk.sfs.beans;

public sealed interface CreationStage {
    enum Instantiating implements CreationStage { INSTANCE }
    enum Populating    implements CreationStage { INSTANCE }
    enum Initializing  implements CreationStage { INSTANCE }
}
```

- [x] **Step 3: `BeanCreationContext` record**

```java
package com.choisk.sfs.beans;

import java.util.List;

/**
 * 빈 생성 중 전달되는 구조화된 컨텍스트. 예외 메시지/로깅에서 활용.
 */
public record BeanCreationContext(
        String beanName,
        Class<?> beanClass,
        CreationStage stage,
        List<String> creationChain
) {
    public BeanCreationContext withStage(CreationStage newStage) {
        return new BeanCreationContext(beanName, beanClass, newStage, creationChain);
    }
}
```

- [x] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): ObjectFactory, BeanCreationContext, CreationStage 추가"
```

---

## ✅ 섹션 C 체크포인트

```bash
./gradlew :sfs-beans:test
```
예상: `BeanDefinitionTest`, `PropertyValuesTest`, `ScopeTest` 통과. 메타데이터 타입 계층 완성.

---

## 섹션 D: BeanFactory 인터페이스 계층 + 확장점 (Task 15~19)

### Task 15: `BeanFactory` + `HierarchicalBeanFactory` + `ListableBeanFactory`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/HierarchicalBeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ListableBeanFactory.java`

> 인터페이스는 동작이 없어 단독 테스트 의미가 작다. `DefaultListableBeanFactory` 통합 테스트(Task 29)에서 합쳐 검증.

- [x] **Step 1: `BeanFactory` 정의**

```java
package com.choisk.sfs.beans;

/**
 * 컨테이너 사용자가 주로 보는 최상위 인터페이스.
 * <p>Spring 원본: {@code org.springframework.beans.factory.BeanFactory}.
 */
public interface BeanFactory {

    String FACTORY_BEAN_PREFIX = "&";

    Object getBean(String name);

    <T> T getBean(String name, Class<T> requiredType);

    <T> T getBean(Class<T> requiredType);

    boolean containsBean(String name);

    boolean isSingleton(String name);

    boolean isPrototype(String name);

    Class<?> getType(String name);
}
```

- [x] **Step 2: `HierarchicalBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface HierarchicalBeanFactory extends BeanFactory {
    BeanFactory getParentBeanFactory();

    boolean containsLocalBean(String name);
}
```

- [x] **Step 3: `ListableBeanFactory`**

```java
package com.choisk.sfs.beans;

import java.util.Map;

public interface ListableBeanFactory extends BeanFactory {

    boolean containsBeanDefinition(String name);

    int getBeanDefinitionCount();

    String[] getBeanDefinitionNames();

    String[] getBeanNamesForType(Class<?> type);

    <T> Map<String, T> getBeansOfType(Class<T> type);
}
```

- [x] **Step 4: 컴파일 확인 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): BeanFactory / Hierarchical / Listable 인터페이스 추가"
```

---

### Task 16: `AutowireCapableBeanFactory` + `ConfigurableBeanFactory` + `ConfigurableListableBeanFactory`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/AutowireCapableBeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableBeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java`

- [x] **Step 1: `AutowireCapableBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface AutowireCapableBeanFactory extends BeanFactory {

    Object createBean(Class<?> beanClass);

    void autowireBean(Object existingBean);

    Object initializeBean(Object existingBean, String beanName);

    Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName);

    Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName);
}
```

- [x] **Step 2: `ConfigurableBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface ConfigurableBeanFactory extends HierarchicalBeanFactory {

    void registerSingleton(String name, Object bean);

    void addBeanPostProcessor(BeanPostProcessor processor);

    int getBeanPostProcessorCount();

    void destroySingletons();
}
```

- [x] **Step 3: `ConfigurableListableBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory {

    void registerBeanDefinition(String name, BeanDefinition definition);

    BeanDefinition getBeanDefinition(String name);

    void preInstantiateSingletons();
}
```

- [x] **Step 4: 컴파일 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): AutowireCapable / Configurable BeanFactory 인터페이스 추가"
```

> **실행 기록 (2026-04-21):** Task 16의 `ConfigurableBeanFactory`가 `BeanPostProcessor`를, Task 18의 `BeanFactoryPostProcessor`가 `ConfigurableListableBeanFactory`를 상호 참조하여 Task 16과 Task 18을 한 커밋으로 묶어 구현했다. Task 17(`FactoryBean`)은 독립적이라 별도 커밋으로 분리. 원안의 Step 구분과 체크박스는 그대로 유지.

---

### Task 17: `FactoryBean` 인터페이스

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/FactoryBean.java`

- [x] **Step 1: 구현**

```java
package com.choisk.sfs.beans;

/**
 * "빈을 만드는 빈". new로 만들 수 없는 객체를 컨테이너에 등록하는 공식 통로.
 * <p>Spring 원본: {@code org.springframework.beans.factory.FactoryBean<T>}.
 *
 * <p>사용 규칙:
 * <ul>
 *   <li>{@code getBean("myFactory")} → {@code getObject()} 호출 결과 반환</li>
 *   <li>{@code getBean("&myFactory")} → FactoryBean 자신 반환</li>
 *   <li>{@code isSingleton() == true}면 getObject() 결과도 캐시됨</li>
 * </ul>
 */
public interface FactoryBean<T> {

    T getObject() throws Exception;

    Class<?> getObjectType();

    default boolean isSingleton() { return true; }
}
```

- [x] **Step 2: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/FactoryBean.java
git commit -m "feat(sfs-beans): FactoryBean 인터페이스 추가"
```

---

### Task 18: `BeanPostProcessor` 계열 + `BeanFactoryPostProcessor`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanPostProcessor.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/InstantiationAwareBeanPostProcessor.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/SmartInstantiationAwareBeanPostProcessor.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanFactoryPostProcessor.java`

- [x] **Step 1: `BeanPostProcessor` (가장 기본)**

```java
package com.choisk.sfs.beans;

/**
 * 초기화 전/후로 빈 인스턴스를 감싸는 훅. AOP 프록시가 여기에 꽂힘 (Phase 2).
 */
public interface BeanPostProcessor {

    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
```

- [x] **Step 2: `InstantiationAwareBeanPostProcessor`**

```java
package com.choisk.sfs.beans;

/**
 * 인스턴스화 자체를 가로채거나 프로퍼티 주입을 후킹하는 BPP.
 * <p>@Autowired 필드 주입 로직이 여기에 꽂힘 (AutowiredAnnotationBeanPostProcessor).
 */
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {

    /**
     * 반환값이 null이 아니면 그 객체를 빈으로 사용 (생성자 스킵).
     */
    default Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
        return null;
    }

    default boolean postProcessAfterInstantiation(Object bean, String beanName) {
        return true;
    }

    /**
     * populateBean 도중 호출. 여기서 @Autowired 필드를 채움.
     */
    default PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        return pvs;
    }
}
```

- [x] **Step 3: `SmartInstantiationAwareBeanPostProcessor`**

```java
package com.choisk.sfs.beans;

/**
 * 3-level cache의 3차 팩토리가 실행될 때 호출되는 훅.
 * <p>AOP 프록시 조기 생성(Phase 2)이 여기에 꽂힘. 순환 참조 상황에서도
 * 다른 빈이 프록시를 참조하도록 보장.
 */
public interface SmartInstantiationAwareBeanPostProcessor
        extends InstantiationAwareBeanPostProcessor {

    /**
     * 조기 참조가 필요할 때 호출. 반환값이 다른 빈들에게 노출됨.
     */
    default Object getEarlyBeanReference(Object bean, String beanName) {
        return bean;
    }
}
```

- [x] **Step 4: `BeanFactoryPostProcessor`**

```java
package com.choisk.sfs.beans;

/**
 * 모든 싱글톤 인스턴스화 전에 BeanDefinition 자체를 수정할 수 있는 훅.
 * <p>@Configuration 클래스 처리(ConfigurationClassPostProcessor, Plan 1B)가 여기에 꽂힘.
 */
public interface BeanFactoryPostProcessor {

    void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory);
}
```

- [x] **Step 5: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): BeanPostProcessor 계층 + BeanFactoryPostProcessor 인터페이스 추가"
```

---

### Task 19: Aware 인터페이스 + `InitializingBean` / `DisposableBean`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/Aware.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanNameAware.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanFactoryAware.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/InitializingBean.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/DisposableBean.java`

- [x] **Step 1~5: 각 인터페이스 파일 생성**

```java
// Aware.java - 마커 인터페이스
package com.choisk.sfs.beans;
public interface Aware {}
```

```java
// BeanNameAware.java
package com.choisk.sfs.beans;
public interface BeanNameAware extends Aware {
    void setBeanName(String name);
}
```

```java
// BeanFactoryAware.java
package com.choisk.sfs.beans;
public interface BeanFactoryAware extends Aware {
    void setBeanFactory(BeanFactory beanFactory);
}
```

```java
// InitializingBean.java
package com.choisk.sfs.beans;
public interface InitializingBean {
    void afterPropertiesSet() throws Exception;
}
```

```java
// DisposableBean.java
package com.choisk.sfs.beans;
public interface DisposableBean {
    void destroy() throws Exception;
}
```

- [x] **Step 6: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): Aware/InitializingBean/DisposableBean 콜백 인터페이스 추가"
```

---

## ✅ 섹션 D 체크포인트

`sfs-beans`의 **모든 공개 인터페이스**가 정의됨. 구현체는 섹션 E~F에서 추가.

```bash
./gradlew :sfs-beans:compileJava
# BUILD SUCCESSFUL
```

---

## 섹션 E: `DefaultSingletonBeanRegistry` — 3-Level Cache (Task 20~23) ⭐

이 섹션이 Phase 1의 **가장 정교한 부분**. 각 스텝을 꼼꼼히 따라가기.

### Task 20: 1차 캐시 + `registerSingleton` / `getSingleton`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [x] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSingletonBeanRegistryTest {

    @Test
    void registerAndGetCompletesHit() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.registerSingleton("foo", "hello");
        assertThat(registry.getSingleton("foo")).isEqualTo("hello");
    }

    @Test
    void missingSingletonReturnsNull() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.getSingleton("missing")).isNull();
    }

    @Test
    void containsSingleton() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.containsSingleton("x")).isFalse();
        registry.registerSingleton("x", 42);
        assertThat(registry.containsSingleton("x")).isTrue();
    }
}
```

- [x] **Step 2: FAIL 확인 후 구현 (1차 캐시만)**

```java
package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultSingletonBeanRegistry {

    /** 1차: 완성된 싱글톤. */
    protected final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();

    public void registerSingleton(String name, Object bean) {
        Assert.hasText(name, "name");
        Assert.notNull(bean, "bean");
        synchronized (singletonObjects) {
            Object existing = singletonObjects.get(name);
            if (existing != null) {
                throw new IllegalStateException(
                        "Singleton '%s' already exists (%s)".formatted(name, existing.getClass().getName()));
            }
            singletonObjects.put(name, bean);
        }
    }

    public Object getSingleton(String name) {
        return singletonObjects.get(name);
    }

    public boolean containsSingleton(String name) {
        return singletonObjects.containsKey(name);
    }

    public String[] getSingletonNames() {
        return singletonObjects.keySet().toArray(new String[0]);
    }
}
```

- [x] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
```

- [x] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): DefaultSingletonBeanRegistry 1차 캐시 구현"
```

---

### Task 21: 2차 + 3차 캐시 + `CacheLookup` 기반 조회 + 승격 ⭐

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Modify: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [x] **Step 1: 실패 테스트 추가**

기존 `DefaultSingletonBeanRegistryTest`에 아래 테스트 **추가**:

```java
    @Test
    void factoryLevelCacheExecutesExactlyOnce() {
        var registry = new DefaultSingletonBeanRegistry();
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerSingletonFactory("early", () -> {
            counter.incrementAndGet();
            return "producedOnce";
        });

        // 처음 조회: factory 실행, 2차로 승격
        var first = registry.lookup("early");
        assertThat(first).isInstanceOf(CacheLookup.EarlyReference.class);
        assertThat(((CacheLookup.EarlyReference) first).bean()).isEqualTo("producedOnce");

        // 두 번째 조회: 2차에서 hit, factory 재실행 X
        var second = registry.lookup("early");
        assertThat(second).isInstanceOf(CacheLookup.EarlyReference.class);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void lookupOrderIs_CompleteThenEarlyThenFactory() {
        var registry = new DefaultSingletonBeanRegistry();

        registry.registerSingletonFactory("name", () -> "fromFactory");
        assertThat(registry.lookup("name")).isInstanceOf(CacheLookup.DeferredFactory.class);

        // promote 요청 → 2차로 이동
        registry.promoteToEarlyReference("name");
        assertThat(registry.lookup("name")).isInstanceOf(CacheLookup.EarlyReference.class);

        // 완성 빈 등록 시 1차가 우선
        registry.registerSingleton("name2", "complete");
        assertThat(registry.lookup("name2")).isInstanceOf(CacheLookup.Complete.class);
    }

    @Test
    void missReturnsMiss() {
        var registry = new DefaultSingletonBeanRegistry();
        assertThat(registry.lookup("nope")).isInstanceOf(CacheLookup.Miss.class);
    }
```

- [x] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 2차/3차 추가**

클래스 전체 교체:

```java
package com.choisk.sfs.beans;

import com.choisk.sfs.core.Assert;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultSingletonBeanRegistry {

    /** 1차: 완성된 싱글톤. */
    protected final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
    /** 2차: 조기 노출된 참조 (AOP 프록시가 씌워졌을 수도 있음). */
    protected final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();
    /** 3차: 조기 참조 팩토리 (getEarlyBeanReference 훅 포함). */
    protected final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>();

    private final Object singletonLock = new Object();

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
    }

    public void registerSingletonFactory(String name, ObjectFactory<?> factory) {
        Assert.hasText(name, "name");
        Assert.notNull(factory, "factory");
        synchronized (singletonLock) {
            if (!singletonObjects.containsKey(name)) {
                singletonFactories.put(name, factory);
                earlySingletonObjects.remove(name);
            }
        }
    }

    /**
     * 3-level 조회를 sealed result로 반환. 호출자는 switch pattern matching.
     * <p>3차 hit 시에도 여기서는 <b>승격하지 않음</b> - 승격 시점은 {@link #promoteToEarlyReference} 호출에서 명시적으로 결정.
     * 이렇게 나눈 이유는 호출자(AbstractBeanFactory)가 SmartBPP 체인 실행 여부를 제어할 수 있어야 하기 때문.
     */
    public CacheLookup lookup(String name) {
        Object complete = singletonObjects.get(name);
        if (complete != null) return new CacheLookup.Complete(complete);

        Object early = earlySingletonObjects.get(name);
        if (early != null) return new CacheLookup.EarlyReference(early);

        ObjectFactory<?> factory = singletonFactories.get(name);
        if (factory != null) return new CacheLookup.DeferredFactory(factory);

        return CacheLookup.Miss.INSTANCE;
    }

    /**
     * 3차 → 2차 승격. factory를 실행하여 결과를 earlySingletonObjects에 저장하고 3차에서 제거.
     * <p>이 메서드는 <b>정확히 한 번만</b> factory를 실행해야 한다. 동시 호출 시 synchronized 보장.
     */
    public Object promoteToEarlyReference(String name) {
        synchronized (singletonLock) {
            Object existing = earlySingletonObjects.get(name);
            if (existing != null) return existing;

            ObjectFactory<?> factory = singletonFactories.get(name);
            if (factory == null) {
                throw new IllegalStateException("No singleton factory registered for: " + name);
            }
            Object produced = factory.getObject();
            earlySingletonObjects.put(name, produced);
            singletonFactories.remove(name);
            return produced;
        }
    }

    public boolean containsSingleton(String name) {
        return singletonObjects.containsKey(name)
                || earlySingletonObjects.containsKey(name)
                || singletonFactories.containsKey(name);
    }

    public String[] getSingletonNames() {
        return singletonObjects.keySet().toArray(new String[0]);
    }

    public Object getSingleton(String name) {
        return singletonObjects.get(name);
    }
}
```

- [x] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
```

- [x] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): 2차/3차 캐시 + CacheLookup 기반 lookup/promote 구현"
```

> **실행 기록 (2026-04-21):** 플랜 원문 `factoryLevelCacheExecutesExactlyOnce` 테스트(라인 2094~2103)가 `registerSingletonFactory` 직후 `lookup`에서 `CacheLookup.EarlyReference`를 기대하지만, 같은 섹션의 `lookup` 메서드 구현(라인 2189~2190) 및 주석(2178~2180 "3차 hit 시에도 여기서는 승격하지 않음")은 `DeferredFactory`를 반환한다고 명시되어 있어 **원문 테스트와 설계 주석이 모순**이다. `lookupOrderIs_CompleteThenEarlyThenFactory` 테스트(라인 2110~2115)는 후자(`DeferredFactory`)를 검증하므로 설계 의도가 후자임이 분명. 따라서 `factoryLevelCacheExecutesExactlyOnce`를 다음 시나리오로 수정해 구현과 일관되게 만들었다: `lookup` → `DeferredFactory` → `promoteToEarlyReference` → `lookup` → `EarlyReference` → 재조회 → `EarlyReference` → `counter == 1`. "조회와 승격 분리" 계약 및 "factory 정확히 1회 실행" 불변식은 모두 그대로 검증됨. 최종 커밋: `10a109a`.

---

### Task 22: "현재 생성 중" 추적 (`ThreadLocal`)

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Modify: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [x] **Step 1: 실패 테스트 추가**

```java
    @Test
    void creationTrackingDetectsRecursion() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.beforeSingletonCreation("a");
        assertThat(registry.isCurrentlyInCreation("a")).isTrue();
        registry.afterSingletonCreation("a");
        assertThat(registry.isCurrentlyInCreation("a")).isFalse();
    }

    @Test
    void creationChainReturnsOrdered() {
        var registry = new DefaultSingletonBeanRegistry();
        registry.beforeSingletonCreation("a");
        registry.beforeSingletonCreation("b");
        assertThat(registry.getCurrentCreationChain()).containsExactly("a", "b");
        registry.afterSingletonCreation("b");
        registry.afterSingletonCreation("a");
    }
```

- [x] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 추가**

기존 클래스에 메서드 추가 (기존 필드 아래):

```java
    /** 현재 스레드에서 생성 중인 빈 이름들 (생성자 순환 감지용). 순서 유지를 위해 LinkedHashSet. */
    private final ThreadLocal<LinkedHashSet<String>> currentlyInCreation =
            ThreadLocal.withInitial(LinkedHashSet::new);

    public void beforeSingletonCreation(String name) {
        if (!currentlyInCreation.get().add(name)) {
            throw new IllegalStateException(
                    "Bean '%s' is already being created in this thread — circular reference".formatted(name));
        }
    }

    public void afterSingletonCreation(String name) {
        currentlyInCreation.get().remove(name);
    }

    public boolean isCurrentlyInCreation(String name) {
        return currentlyInCreation.get().contains(name);
    }

    public List<String> getCurrentCreationChain() {
        return List.copyOf(currentlyInCreation.get());
    }
```

- [x] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
git add sfs-beans/
git commit -m "feat(sfs-beans): ThreadLocal 기반 빈 생성 추적 (순환 감지)"
```

---

### Task 23: Destruction 콜백 등록 + `destroySingletons`

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Modify: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [x] **Step 1: 실패 테스트 추가**

```java
    @Test
    void destroySingletonsInvokesCallbacksInReverseOrder() {
        var registry = new DefaultSingletonBeanRegistry();
        var order = new java.util.ArrayList<String>();

        registry.registerSingleton("first", "bean1");
        registry.registerDisposableBean("first", () -> order.add("first"));
        registry.registerSingleton("second", "bean2");
        registry.registerDisposableBean("second", () -> order.add("second"));

        registry.destroySingletons();

        assertThat(order).containsExactly("second", "first");
        assertThat(registry.containsSingleton("first")).isFalse();
    }

    @Test
    void destroyContinuesOnFailure() {
        var registry = new DefaultSingletonBeanRegistry();
        var invoked = new java.util.ArrayList<String>();
        registry.registerSingleton("a", "a");
        registry.registerDisposableBean("a", () -> invoked.add("a"));
        registry.registerSingleton("b", "b");
        registry.registerDisposableBean("b", () -> { throw new RuntimeException("boom"); });
        registry.registerSingleton("c", "c");
        registry.registerDisposableBean("c", () -> invoked.add("c"));

        registry.destroySingletons();

        // b는 실패했지만 a와 c는 역순으로 실행됨 (c → [b 실패] → a)
        assertThat(invoked).containsExactly("c", "a");
    }
```

- [x] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 추가**

```java
    /** destroy 콜백. 등록 순서의 역순으로 실행되도록 LinkedHashMap. */
    private final LinkedHashMap<String, Runnable> disposableBeans = new LinkedHashMap<>();

    public void registerDisposableBean(String name, Runnable callback) {
        synchronized (singletonLock) {
            disposableBeans.put(name, callback);
        }
    }

    public void destroySingletons() {
        String[] names;
        synchronized (singletonLock) {
            names = disposableBeans.keySet().toArray(new String[0]);
        }
        // 역순 실행 (LIFO)
        for (int i = names.length - 1; i >= 0; i--) {
            String name = names[i];
            Runnable callback;
            synchronized (singletonLock) {
                callback = disposableBeans.remove(name);
            }
            if (callback == null) continue;
            try {
                callback.run();
            } catch (Throwable t) {
                // 한 개 실패가 전체 destroy를 막지 않도록 로그만 (System.err 사용 - 로깅 프레임워크 의존 회피)
                System.err.println("[sfs-beans] Failed to destroy singleton '" + name + "': " + t);
            }
        }
        synchronized (singletonLock) {
            singletonObjects.clear();
            earlySingletonObjects.clear();
            singletonFactories.clear();
        }
    }
```

- [x] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
git add sfs-beans/
git commit -m "feat(sfs-beans): destroySingletons 역순 실행 + 실패 복원 구현"
```

---

## ✅ 섹션 E 체크포인트

**가장 복잡한 파트 완료.** 이 시점에서 `DefaultSingletonBeanRegistry`는:

- 3-level cache 완전 동작
- `CacheLookup` sealed 결과로 타입 안전 분기
- 스레드별 "생성 중" 추적으로 순환 참조 감지 준비 완료
- LIFO 순서 destruction + 부분 실패 복원

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
# 8+ 테스트 모두 PASS
```

---

## 섹션 F: BeanFactory 구현체들 (Task 24~29)

### Task 24: `AbstractBeanFactory` 골격 + `getBean` 템플릿

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractBeanFactory.java`

> `support` 하위 패키지로 구현체를 분리. 공개 API(인터페이스·메타데이터)는 `com.choisk.sfs.beans` 루트 패키지에만 둠.

- [x] **Step 1: 클래스 구현 — 추상 뼈대 + `getBean` 템플릿**

```java
package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.core.*;
import java.util.*;

/**
 * BeanFactory의 기본 구현. getBean 템플릿 메서드, FactoryBean/& 접두사 분기,
 * 싱글톤/프로토타입 라우팅을 담당. 실제 빈 생성은 서브클래스에서 {@link #createBean}.
 */
public abstract class AbstractBeanFactory
        extends DefaultSingletonBeanRegistry
        implements ConfigurableBeanFactory {

    private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();
    private BeanFactory parentBeanFactory;

    @Override
    public Object getBean(String name) {
        return doGetBean(name, null);
    }

    @Override
    public <T> T getBean(String name, Class<T> requiredType) {
        Object bean = doGetBean(name, requiredType);
        if (requiredType != null && !requiredType.isInstance(bean)) {
            throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
        }
        return requiredType.cast(bean);
    }

    @Override
    public <T> T getBean(Class<T> requiredType) {
        String name = resolveBeanNameByType(requiredType);
        return getBean(name, requiredType);
    }

    /** 이름→타입 해결은 DefaultListableBeanFactory에서 오버라이드 (BeanDefinition 맵 필요). */
    protected abstract String resolveBeanNameByType(Class<?> requiredType);

    /**
     * 핵심 진입점. Java 25 pattern matching으로 3-level cache 결과 분기.
     */
    protected Object doGetBean(String name, Class<?> requiredType) {
        String beanName = transformBeanName(name);
        boolean isFactoryDereference = isFactoryDereference(name);

        Object sharedInstance = switch (lookup(beanName)) {
            case CacheLookup.Complete(var bean)        -> bean;
            case CacheLookup.EarlyReference(var bean)  -> bean;
            case CacheLookup.DeferredFactory(var ignored) -> promoteToEarlyReference(beanName);
            case CacheLookup.Miss ignored              -> null;
        };

        if (sharedInstance != null) {
            return resolveFactoryBean(beanName, sharedInstance, isFactoryDereference);
        }

        BeanDefinition definition = getBeanDefinition(beanName);
        if (definition == null) {
            throw new NoSuchBeanDefinitionException(
                    buildNoSuchBeanMessage(beanName, requiredType));
        }

        if (definition.isSingleton()) {
            Object created = getSingletonOrCreate(beanName, () -> createBean(beanName, definition));
            return resolveFactoryBean(beanName, created, isFactoryDereference);
        } else if (definition.isPrototype()) {
            Object created = createBean(beanName, definition);
            return resolveFactoryBean(beanName, created, isFactoryDereference);
        } else {
            throw new IllegalStateException("Unsupported scope: " + definition.getScope());
        }
    }

    /**
     * 싱글톤 캐시 조회 + 미존재 시 factory 실행 + 캐시 승격을 원자적으로.
     */
    protected Object getSingletonOrCreate(String beanName, ObjectFactory<?> factory) {
        Object existing = getSingleton(beanName);
        if (existing != null) return existing;

        beforeSingletonCreation(beanName);
        try {
            Object created = factory.getObject();
            addSingletonCommitted(beanName, created);
            return created;
        } finally {
            afterSingletonCreation(beanName);
        }
    }

    private void addSingletonCommitted(String name, Object bean) {
        // 이미 3-level 캐시의 2차에 있을 수 있음 (조기 참조로 노출됨).
        // 그 경우 기존 earlyReference를 신뢰하고 1차로 그대로 승격해야 동일 인스턴스 보장.
        Object early = earlySingletonObjects.get(name);
        if (early != null) {
            registerSingleton(name, early);
        } else {
            registerSingleton(name, bean);
        }
    }

    public String transformBeanName(String name) {
        return isFactoryDereference(name) ? name.substring(FACTORY_BEAN_PREFIX.length()) : name;
    }

    public boolean isFactoryDereference(String name) {
        return name != null && name.startsWith(FACTORY_BEAN_PREFIX);
    }

    /** & 접두사 처리는 Task 25에서 완성. */
    protected Object resolveFactoryBean(String beanName, Object sharedInstance, boolean isFactoryDereference) {
        return sharedInstance;
    }

    protected abstract BeanDefinition getBeanDefinition(String beanName);

    protected abstract Object createBean(String beanName, BeanDefinition definition);

    private String buildNoSuchBeanMessage(String name, Class<?> requiredType) {
        var candidates = Arrays.asList(getSingletonNames());
        var similar = candidates.stream()
                .filter(existing -> levenshtein(existing, name) <= 3)
                .limit(3)
                .toList();
        var sb = new StringBuilder("No bean named '").append(name).append("' found");
        if (requiredType != null) {
            sb.append(" (required type: ").append(requiredType.getName()).append(")");
        }
        if (!similar.isEmpty()) {
            sb.append(". Did you mean: ").append(similar).append("?");
        }
        sb.append("\nPossible solutions:")
          .append("\n  - Register the bean via registerBeanDefinition or register a @Component class")
          .append("\n  - Check bean name spelling");
        return sb.toString();
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // --- BeanFactory ---
    @Override
    public boolean containsBean(String name) {
        String beanName = transformBeanName(name);
        return containsSingleton(beanName) || containsBeanDefinition(beanName);
    }

    @Override
    public boolean isSingleton(String name) {
        String beanName = transformBeanName(name);
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null && def.isSingleton();
    }

    @Override
    public boolean isPrototype(String name) {
        String beanName = transformBeanName(name);
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null && def.isPrototype();
    }

    @Override
    public Class<?> getType(String name) {
        String beanName = transformBeanName(name);
        Object singleton = getSingleton(beanName);
        if (singleton != null) return singleton.getClass();
        BeanDefinition def = getBeanDefinition(beanName);
        return def != null ? def.getBeanClass() : null;
    }

    protected abstract boolean containsBeanDefinition(String beanName);

    // --- HierarchicalBeanFactory ---
    @Override
    public BeanFactory getParentBeanFactory() { return parentBeanFactory; }

    public void setParentBeanFactory(BeanFactory parent) { this.parentBeanFactory = parent; }

    @Override
    public boolean containsLocalBean(String name) {
        String beanName = transformBeanName(name);
        return containsSingleton(beanName) || containsBeanDefinition(beanName);
    }

    // --- ConfigurableBeanFactory ---
    @Override
    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.remove(processor);
        beanPostProcessors.add(processor);
    }

    @Override
    public int getBeanPostProcessorCount() { return beanPostProcessors.size(); }

    public List<BeanPostProcessor> getBeanPostProcessors() {
        return Collections.unmodifiableList(beanPostProcessors);
    }
}
```

- [x] **Step 2: 컴파일 확인 & 커밋 (단독 테스트는 서브클래스 필요해서 Task 29에서 통합 검증)**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): AbstractBeanFactory 골격 + getBean 템플릿 구현"
```

> **실행 기록 (2026-04-22):**
> - **편차 1 (와일드카드 import 명시화)**: 플랜 원문 `import com.choisk.sfs.beans.*; import com.choisk.sfs.core.*; import java.util.*;` 를 이전 리팩토링(`DefaultSingletonBeanRegistry`, `PropertyValues`) 스타일에 맞춰 명시적 import로 교체. 기능 동일.
> - **편차 2 (`Miss()` 문법)**: 플랜 원문 `case CacheLookup.Miss ignored -> null;` 를 `case CacheLookup.Miss() -> null;` 로 교체. `CacheLookup.java` Javadoc 예시와 일관된 record 패턴 적용. 기능 동일.
> - 컴파일: BUILD SUCCESSFUL. `:sfs-beans:test` 회귀 없음 (기존 전체 통과). 커밋: `e11eb66`.
>
> **체크포인트 리뷰 발견 사항 (Task 29 선결 항목) — ✅ 2026-04-22 분리 처리 완료:**
> Task 24 완료 후 코드리뷰에서 발견된 4건의 계약/동시성 이슈는 Task 29 진입 전 별도 커밋으로 일괄 처리됐다. TDD 사이클(`AbstractBeanFactoryTest` 신규 + `DefaultSingletonBeanRegistryTest` 동시성 테스트 추가) 적용. Task 29는 본래 범위(신규 클래스 작성)에만 집중.
> - **[HIGH] #1 `getBean(String, Class<T>)` NPE 위험** ✅ 처리: 최상단 `Assert.notNull(requiredType)` 가드 추가, 후행 null 체크 제거. 검증: `AbstractBeanFactoryTest.getBeanWithNullRequiredTypeThrowsIllegalArgument`.
> - **[HIGH] #2 `isSingleton`/`isPrototype`가 수동 등록 싱글톤 누락** ✅ 처리: `containsSingleton(name) && !containsBeanDefinition(name)` 분기 앞단 추가 (isSingleton→true, isPrototype→false). 검증: `AbstractBeanFactoryTest.manuallyRegisteredSingletonReportsAsSingleton`.
> - **[MED] #3 싱글톤 생성 원자성 부재** ✅ 처리: `DefaultSingletonBeanRegistry.getOrCreateSingleton(name, factory)` 신설(`synchronized(singletonLock)` 내부 check-then-act, 2차 캐시 승격 포함). `AbstractBeanFactory.getSingletonOrCreate`는 위임으로 단순화, 기존 `addSingletonCommitted` 헬퍼 제거. 검증: `DefaultSingletonBeanRegistryTest.getOrCreateSingletonExecutesFactoryExactlyOnceUnderConcurrency` (16스레드 + 20ms factory 지연으로 경합 윈도우 확장).
> - **[MED] #4 `buildNoSuchBeanMessage` 후보 집합 오류** ✅ 처리: `protected abstract String[] getBeanDefinitionNames()` 도입, 후보 집합을 `BeanDefinition 이름 ∪ 싱글톤 이름`(LinkedHashSet)으로 확장. 검증: `AbstractBeanFactoryTest.buildNoSuchBeanMessageIncludesBeanDefinitionCandidates`.

---

### Task 25: `AbstractBeanFactory`의 FactoryBean `&` 접두사 완성

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractBeanFactory.java`

- [x] **Step 1: `resolveFactoryBean` 메서드 확장**

기존 `resolveFactoryBean` 본문을 아래로 교체:

```java
    protected Object resolveFactoryBean(String beanName, Object sharedInstance, boolean isFactoryDereference) {
        // & 접두사: FactoryBean 자신을 반환
        if (isFactoryDereference) {
            if (!(sharedInstance instanceof FactoryBean<?>)) {
                throw new BeanIsNotAFactoryException(beanName, sharedInstance.getClass());
            }
            return sharedInstance;
        }

        // 일반 조회: FactoryBean이면 getObject() 결과 반환, 아니면 그대로
        if (!(sharedInstance instanceof FactoryBean<?> factory)) {
            return sharedInstance;
        }

        // FactoryBean 결과 캐시 (싱글톤 FactoryBean의 경우)
        String cacheKey = "&__fb_obj__" + beanName;
        Object cached = getSingleton(cacheKey);
        if (cached != null) return cached;

        try {
            Object produced = factory.getObject();
            if (produced == null) {
                throw new FactoryBeanNotInitializedException(beanName);
            }
            if (factory.isSingleton()) {
                registerSingleton(cacheKey, produced);
            }
            return produced;
        } catch (FactoryBeanNotInitializedException | BeansException e) {
            throw e;
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "FactoryBean.getObject() threw exception", e);
        }
    }
```

- [x] **Step 2: 컴파일 & 커밋 (FactoryBean 통합 테스트는 Task 32)**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): AbstractBeanFactory FactoryBean & 접두사 처리 완성"
```

> **실행 기록 (2026-04-22):**
> - **편차 1 (multi-catch 컴파일 에러)**: 플랜 원문 `catch (FactoryBeanNotInitializedException | BeansException e)` 는 Java 컴파일러가 거부 — `FactoryBeanNotInitializedException extends BeansException`이라 multi-catch alternatives에 상속 관계가 있을 수 없음. `catch (BeansException e) { throw e; }` 한 줄로 단순화. 기능 동일 (FactoryBeanNotInitializedException도 BeansException 매칭으로 그대로 재전파).
> - **편차 2 (와일드카드 import 명시화)**: Task 24와 동일하게 `FactoryBean`, `BeanCreationException`, `BeanIsNotAFactoryException`, `BeansException`, `FactoryBeanNotInitializedException` 모두 명시 import.
> - 컴파일: `:sfs-beans:compileJava` BUILD SUCCESSFUL. `:sfs-beans:test` 회귀 없음 (기존 전체 통과).

---

### Task 26: `AbstractAutowireCapableBeanFactory` — 인스턴스화

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java`

- [x] **Step 1: 클래스 구현 — `createBean` → `doCreateBean` → `instantiateBean`**

```java
package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.core.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * 실제 빈 인스턴스화 + 프로퍼티 주입 + 초기화 로직을 담당.
 * <p>Spring 원본: {@code AbstractAutowireCapableBeanFactory}.
 */
public abstract class AbstractAutowireCapableBeanFactory
        extends AbstractBeanFactory
        implements AutowireCapableBeanFactory {

    @Override
    protected Object createBean(String beanName, BeanDefinition definition) {
        // B-1: InstantiationAware.before (프록시로 조기 종료 가능성)
        Object shortCircuit = resolveBeforeInstantiation(beanName, definition);
        if (shortCircuit != null) {
            return shortCircuit;
        }
        return doCreateBean(beanName, definition);
    }

    /** B-1 단계. 현재는 hook point만 제공; AOP에서 오버라이드. */
    protected Object resolveBeforeInstantiation(String beanName, BeanDefinition def) {
        for (var bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                Object result = iabpp.postProcessBeforeInstantiation(def.getBeanClass(), beanName);
                if (result != null) return applyBeanPostProcessorsAfterInitialization(result, beanName);
            }
        }
        return null;
    }

    protected Object doCreateBean(String beanName, BeanDefinition definition) {
        // B-2: 인스턴스화
        Object bean = instantiateBean(beanName, definition);

        // B-3: 3차 캐시에 팩토리 등록 (조기 참조용)
        boolean earlySingletonExposure = definition.isSingleton();
        if (earlySingletonExposure) {
            registerSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, bean));
        }

        // B-4: 프로퍼티 주입
        populateBean(beanName, definition, bean);

        // B-5: 초기화
        Object exposed = initializeBean(beanName, definition, bean);

        // B-7: destroy 등록 (Task 28에서 확장)
        registerDisposableIfNeeded(beanName, definition, exposed);

        return exposed;
    }

    protected Object instantiateBean(String beanName, BeanDefinition definition) {
        Class<?> beanClass = definition.getBeanClass();
        try {
            if (beanClass.isInterface() || Modifier.isAbstract(beanClass.getModifiers())) {
                throw new BeanCreationException(beanName,
                        "Cannot instantiate interface or abstract class: " + beanClass.getName());
            }
            // 생성자 인자가 명시됐으면 해당 시그니처 찾기
            List<Object> args = definition.getConstructorArgs();
            if (args.isEmpty()) {
                Constructor<?> ctor = beanClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
            for (Constructor<?> ctor : beanClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == args.size()) {
                    Object[] resolved = resolveConstructorArgs(ctor.getParameterTypes(), args);
                    ctor.setAccessible(true);
                    return ctor.newInstance(resolved);
                }
            }
            throw new BeanCreationException(beanName,
                    "No constructor matching %d args on %s".formatted(args.size(), beanClass.getName()));
        } catch (ReflectiveOperationException e) {
            throw new BeanCreationException(beanName, "Instantiation failed", e);
        }
    }

    private Object[] resolveConstructorArgs(Class<?>[] paramTypes, List<Object> raw) {
        Object[] resolved = new Object[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object a = raw.get(i);
            if (a instanceof BeanReference ref) {
                resolved[i] = getBean(ref.beanName());
            } else {
                resolved[i] = a;
            }
        }
        return resolved;
    }

    /** B-3에서 3차 factory가 생산하는 조기 참조. SmartInstantiationAwareBPP를 체인 적용. */
    protected Object getEarlyBeanReference(String beanName, Object rawBean) {
        Object exposed = rawBean;
        for (var bpp : getBeanPostProcessors()) {
            if (bpp instanceof SmartInstantiationAwareBeanPostProcessor smart) {
                exposed = smart.getEarlyBeanReference(exposed, beanName);
            }
        }
        return exposed;
    }

    // --- Task 27/28에서 구현 ---
    protected abstract void populateBean(String beanName, BeanDefinition definition, Object bean);

    protected abstract Object initializeBean(String beanName, BeanDefinition definition, Object bean);

    protected abstract void registerDisposableIfNeeded(String beanName, BeanDefinition definition, Object bean);

    // --- AutowireCapableBeanFactory 수동 API ---
    @Override
    public Object createBean(Class<?> beanClass) {
        var def = new BeanDefinition(beanClass).setScope(Scope.Prototype.INSTANCE);
        return doCreateBean(beanClass.getName(), def);
    }

    @Override
    public void autowireBean(Object existingBean) {
        var def = new BeanDefinition(existingBean.getClass());
        populateBean(existingBean.getClass().getName(), def, existingBean);
    }

    @Override
    public Object initializeBean(Object existingBean, String beanName) {
        var def = new BeanDefinition(existingBean.getClass());
        return initializeBean(beanName, def, existingBean);
    }

    @Override
    public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) {
        Object result = existingBean;
        for (var bpp : getBeanPostProcessors()) {
            result = bpp.postProcessBeforeInitialization(result, beanName);
            if (result == null) return null;
        }
        return result;
    }

    @Override
    public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) {
        Object result = existingBean;
        for (var bpp : getBeanPostProcessors()) {
            result = bpp.postProcessAfterInitialization(result, beanName);
            if (result == null) return null;
        }
        return result;
    }
}
```

- [x] **Step 2: 컴파일 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): AbstractAutowireCapableBeanFactory 인스턴스화 플로우 구현"
```

> **실행 기록 (2026-04-22):**
> - **편차 1 (와일드카드 import 명시화)**: Plan 원문의 `import com.choisk.sfs.beans.*`, `import com.choisk.sfs.core.*`, `import java.lang.reflect.*`, `import java.util.*`를 모두 명시 import로 교체: `AutowireCapableBeanFactory`, `BeanDefinition`, `BeanPostProcessor`, `BeanReference`, `InstantiationAwareBeanPostProcessor`, `ObjectFactory`, `Scope`, `SmartInstantiationAwareBeanPostProcessor`, `BeanCreationException`, `Constructor`, `Modifier`, `List`.
> - 컴파일: `:sfs-beans:compileJava` BUILD SUCCESSFUL. `:sfs-beans:test` 회귀 없음.

---

### Task 27: `populateBean` — 프로퍼티 주입

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java`

- [x] **Step 1: `populateBean` 구현**

`protected abstract void populateBean` 선언을 제거하고 실제 구현으로 교체:

```java
    @Override
    protected void populateBean(String beanName, BeanDefinition definition, Object bean) {
        // InstantiationAwareBPP 후킹 (Plan 1B에서 @Autowired 주입이 여기에 꽂힘)
        boolean continuePopulation = true;
        for (var bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                if (!iabpp.postProcessAfterInstantiation(bean, beanName)) {
                    continuePopulation = false;
                    break;
                }
            }
        }
        if (!continuePopulation) return;

        PropertyValues pvs = definition.getPropertyValues();
        for (var bpp : getBeanPostProcessors()) {
            if (bpp instanceof InstantiationAwareBeanPostProcessor iabpp) {
                pvs = iabpp.postProcessProperties(pvs, bean, beanName);
                if (pvs == null) return;
            }
        }

        // BeanDefinition에 명시된 propertyValues를 리플렉션으로 적용
        applyPropertyValues(beanName, bean, pvs);
    }

    private void applyPropertyValues(String beanName, Object bean, PropertyValues pvs) {
        if (pvs == null || pvs.isEmpty()) return;
        for (var pv : pvs.all()) {
            Object value = pv.value() instanceof BeanReference ref
                    ? getBean(ref.beanName())
                    : pv.value();
            Field field = ReflectionUtils.findField(bean.getClass(), pv.name());
            if (field != null) {
                ReflectionUtils.setField(field, bean, value);
                continue;
            }
            String setter = "set" + Character.toUpperCase(pv.name().charAt(0)) + pv.name().substring(1);
            Method method = ReflectionUtils.findMethod(bean.getClass(), setter, value == null ? Object.class : value.getClass());
            if (method != null) {
                ReflectionUtils.invokeMethod(method, bean, value);
                continue;
            }
            throw new BeanCreationException(beanName,
                    "No property '%s' found on %s".formatted(pv.name(), bean.getClass().getName()));
        }
    }
```

- [x] **Step 2: 컴파일 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): populateBean 프로퍼티 주입 구현 (BeanReference 해결 포함)"
```

> **실행 기록 (2026-04-22):**
> - **편차 1 (와일드카드 import 추가)**: `populateBean` 구현에 필요한 `PropertyValues`, `ReflectionUtils`, `Field`, `Method` import 추가. Plan 원문에는 `@Override` 애노테이션이 있으나 실제로는 abstract → concrete 교체이므로 `@Override` 제거 후 일반 구현 메서드로 작성.
> - 컴파일: `:sfs-beans:compileJava` BUILD SUCCESSFUL. `:sfs-beans:test` 회귀 없음.

---

### Task 28: `initializeBean` + destroy 등록

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/AbstractAutowireCapableBeanFactory.java`

- [x] **Step 1: `initializeBean`과 destroy 구현으로 교체**

```java
    @Override
    protected Object initializeBean(String beanName, BeanDefinition definition, Object bean) {
        // B-5 (a) Aware 콜백
        invokeAwareCallbacks(beanName, bean);

        // B-5 (b) BPP before
        Object current = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
        if (current == null) return null;

        // B-5 (c) InitializingBean + init-method
        try {
            if (current instanceof InitializingBean ib) {
                ib.afterPropertiesSet();
            }
            if (definition.getInitMethodName() != null) {
                Method m = ReflectionUtils.findMethod(current.getClass(), definition.getInitMethodName());
                if (m == null) {
                    throw new BeanCreationException(beanName,
                            "Init method '%s' not found on %s".formatted(definition.getInitMethodName(), current.getClass().getName()));
                }
                ReflectionUtils.invokeMethod(m, current);
            }
        } catch (Exception e) {
            throw new BeanCreationException(beanName, "Initialization callback failed", e);
        }

        // B-5 (d) BPP after (AOP 프록시는 여기서 - Phase 2)
        return applyBeanPostProcessorsAfterInitialization(current, beanName);
    }

    private void invokeAwareCallbacks(String beanName, Object bean) {
        if (bean instanceof BeanNameAware bna) bna.setBeanName(beanName);
        if (bean instanceof BeanFactoryAware bfa) bfa.setBeanFactory(this);
    }

    @Override
    protected void registerDisposableIfNeeded(String beanName, BeanDefinition definition, Object bean) {
        if (!definition.isSingleton()) return;
        boolean hasDisposable = bean instanceof DisposableBean;
        boolean hasDestroyMethod = definition.getDestroyMethodName() != null;
        if (!hasDisposable && !hasDestroyMethod) return;

        registerDisposableBean(beanName, () -> {
            try {
                if (bean instanceof DisposableBean db) db.destroy();
                if (definition.getDestroyMethodName() != null) {
                    Method m = ReflectionUtils.findMethod(bean.getClass(), definition.getDestroyMethodName());
                    if (m != null) ReflectionUtils.invokeMethod(m, bean);
                }
            } catch (Exception e) {
                throw new RuntimeException("Destroy failed for " + beanName, e);
            }
        });
    }
```

- [x] **Step 2: 컴파일 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): initializeBean + destroy 등록 구현 (Aware/BPP/InitializingBean/init-method)"
```

> **실행 기록 (2026-04-22):**
> - **편차 1 (와일드카드 import 명시화)**: `BeanNameAware`, `BeanFactoryAware`, `DisposableBean`, `InitializingBean` import 추가. Plan 원문에 `@Override` 어노테이션이 붙어있으나, abstract → concrete 전환이므로 제거 후 일반 메서드로 작성 (super에서 abstract 선언만 있었고 `@Override`는 불필요).
> - 컴파일: `:sfs-beans:compileJava` BUILD SUCCESSFUL. `:sfs-beans:test` 회귀 없음.

---

### Task 29: `DefaultListableBeanFactory`

> **선결 (✅ 2026-04-22 완료)**: Task 24 코드리뷰 4건(#1~#4)은 본 Task 진입 전 별도 커밋으로 분리 처리됨. 본 Task는 `DefaultListableBeanFactory` 신규 클래스 작성 + `DefaultListableBeanFactoryTest` TDD 사이클에만 집중. `AbstractBeanFactory`/`DefaultSingletonBeanRegistry` 추가 수정 불필요. `getBeanDefinitionNames()` 추상 메서드는 이미 도입됐으므로 본 Task에서 구현해야 한다.

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/support/DefaultListableBeanFactoryTest.java`
- ~~Modify (선결 #3): `DefaultSingletonBeanRegistry.java`~~ — 사전 처리 완료
- ~~Modify (선결 #1, #2, #4): `AbstractBeanFactory.java`~~ — 사전 처리 완료

- [ ] **Step 1: 실패 테스트**

```java
package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.core.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefaultListableBeanFactoryTest {

    static class Greeter {
        private String message = "hello";
        public String greet() { return message; }
    }

    @Test
    void registerAndGetSimpleBean() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class));
        var g = factory.getBean("g", Greeter.class);
        assertThat(g.greet()).isEqualTo("hello");
    }

    @Test
    void getBeanByType_singleCandidate() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class));
        assertThat(factory.getBean(Greeter.class)).isNotNull();
    }

    @Test
    void getBeanByType_multipleCandidates_requiresPrimaryOrQualifier() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("b", new BeanDefinition(Greeter.class));
        assertThatThrownBy(() -> factory.getBean(Greeter.class))
                .isInstanceOf(NoUniqueBeanDefinitionException.class);
    }

    @Test
    void primaryWins() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("b", new BeanDefinition(Greeter.class).setPrimary(true));
        assertThat(factory.getBean(Greeter.class)).isNotNull();
    }

    @Test
    void prototypeNewInstanceEachGet() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g", new BeanDefinition(Greeter.class).setScope(Scope.Prototype.INSTANCE));
        assertThat(factory.getBean("g")).isNotSameAs(factory.getBean("g"));
    }

    @Test
    void preInstantiateSingletonsCreatesAllEagerBeans() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("g1", new BeanDefinition(Greeter.class));
        factory.registerBeanDefinition("g2", new BeanDefinition(Greeter.class).setLazyInit(true));
        factory.preInstantiateSingletons();
        assertThat(factory.containsSingleton("g1")).isTrue();
        assertThat(factory.containsSingleton("g2")).isFalse();
    }

    @Test
    void noSuchBeanHasHelpfulMessage() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("greater", new BeanDefinition(Greeter.class));
        assertThatThrownBy(() -> factory.getBean("greeter"))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("greeter")
                .hasMessageContaining("Possible solutions");
    }
}
```

- [ ] **Step 2: FAIL 확인 후 구현**

```java
package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.core.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultListableBeanFactory
        extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory {

    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>();
    private final List<String> beanDefinitionNames = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void registerBeanDefinition(String name, BeanDefinition definition) {
        Assert.hasText(name, "name");
        Assert.notNull(definition, "definition");
        BeanDefinition existing = beanDefinitionMap.put(name, definition);
        if (existing == null) {
            beanDefinitionNames.add(name);
        } else {
            // 덮어쓰기 허용 (Spring도 기본 허용). 주소록 순서는 기존 유지.
        }
    }

    @Override
    public BeanDefinition getBeanDefinition(String name) {
        return beanDefinitionMap.get(name);
    }

    @Override
    protected boolean containsBeanDefinition(String beanName) {
        return beanDefinitionMap.containsKey(beanName);
    }

    @Override
    public boolean containsBeanDefinition(String name) {
        return beanDefinitionMap.containsKey(name);
    }

    @Override
    public int getBeanDefinitionCount() { return beanDefinitionMap.size(); }

    @Override
    public String[] getBeanDefinitionNames() {
        synchronized (beanDefinitionNames) {
            return beanDefinitionNames.toArray(new String[0]);
        }
    }

    @Override
    public String[] getBeanNamesForType(Class<?> type) {
        var matches = new ArrayList<String>();
        for (var entry : beanDefinitionMap.entrySet()) {
            if (type.isAssignableFrom(entry.getValue().getBeanClass())) {
                matches.add(entry.getKey());
            }
        }
        return matches.toArray(new String[0]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        var result = new LinkedHashMap<String, T>();
        for (var name : getBeanNamesForType(type)) {
            result.put(name, (T) getBean(name));
        }
        return result;
    }

    @Override
    protected String resolveBeanNameByType(Class<?> type) {
        var matches = getBeanNamesForType(type);
        if (matches.length == 0) {
            throw new NoSuchBeanDefinitionException(
                    "No bean of type " + type.getName() + " registered");
        }
        if (matches.length == 1) return matches[0];

        // @Primary 우선
        var primaries = new ArrayList<String>();
        for (var name : matches) {
            if (beanDefinitionMap.get(name).isPrimary()) primaries.add(name);
        }
        if (primaries.size() == 1) return primaries.get(0);
        if (primaries.size() > 1) {
            throw new NoUniqueBeanDefinitionException(
                    "Multiple @Primary beans of type " + type.getName() + ": " + primaries, primaries);
        }
        throw new NoUniqueBeanDefinitionException(
                "Multiple beans of type " + type.getName() + ": " + Arrays.asList(matches)
                        + "\nPossible solutions: annotate one with @Primary or inject by name",
                Arrays.asList(matches));
    }

    @Override
    public void preInstantiateSingletons() {
        String[] names;
        synchronized (beanDefinitionNames) {
            names = beanDefinitionNames.toArray(new String[0]);
        }
        for (String name : names) {
            BeanDefinition def = beanDefinitionMap.get(name);
            if (def != null && def.isSingleton() && !def.isLazyInit()) {
                getBean(name);
            }
        }
    }

    @Override
    public void destroySingletons() {
        super.destroySingletons();
    }
}
```

- [ ] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :sfs-beans:test --tests DefaultListableBeanFactoryTest
```

- [ ] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): DefaultListableBeanFactory 구현 (Primary/Qualifier 해결 포함)"
```

---

## ✅ 섹션 F 체크포인트

**`DefaultListableBeanFactory`가 독립 동작.** 수동으로 `registerBeanDefinition`한 빈을 `getBean`으로 가져올 수 있음.

```bash
./gradlew :sfs-beans:test
# 모든 테스트 PASS
```

---

## 섹션 G: 통합 테스트 & Plan 1A DoD (Task 30~33)

이 섹션에서 **설계 문서의 핵심 보장**(3-level cache 순환 해결, FactoryBean, BPP 순서)을 통합 테스트로 검증.

### Task 30: 통합 테스트 — 세터/필드 주입 순환 참조 해결

**Files:**
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/integration/CircularReferenceSetterTest.java`

- [ ] **Step 1: 통합 테스트 작성**

```java
package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CircularReferenceSetterTest {

    static class ServiceA {
        ServiceB b;
        public void setB(ServiceB b) { this.b = b; }
    }

    static class ServiceB {
        ServiceA a;
        public void setA(ServiceA a) { this.a = a; }
    }

    @Test
    void setterCircularResolved() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a",
                new BeanDefinition(ServiceA.class).addPropertyValue("b", new BeanReference("b")));
        factory.registerBeanDefinition("b",
                new BeanDefinition(ServiceB.class).addPropertyValue("a", new BeanReference("a")));

        factory.preInstantiateSingletons();

        var a = factory.getBean("a", ServiceA.class);
        var b = factory.getBean("b", ServiceB.class);

        assertThat(a.b).isSameAs(b);
        assertThat(b.a).isSameAs(a);
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew :sfs-beans:test --tests CircularReferenceSetterTest
```
예상: PASS. 이 하나가 통과하면 **3-level cache의 핵심 기능이 증명됨**.

- [ ] **Step 3: 커밋**

```bash
git add sfs-beans/
git commit -m "test(sfs-beans): 세터 주입 순환 참조 해결 통합 테스트"
```

---

### Task 31: 통합 테스트 — 생성자 순환 참조 예외

**Files:**
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/integration/CircularReferenceConstructorTest.java`

- [ ] **Step 1: 통합 테스트**

```java
package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.BeanCreationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CircularReferenceConstructorTest {

    static class A { A(B b) {} }
    static class B { B(A a) {} }

    @Test
    void constructorCircularThrows() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a",
                new BeanDefinition(A.class).addConstructorArg(new BeanReference("b")));
        factory.registerBeanDefinition("b",
                new BeanDefinition(B.class).addConstructorArg(new BeanReference("a")));

        assertThatThrownBy(factory::preInstantiateSingletons)
                .isInstanceOfAny(BeanCreationException.class, IllegalStateException.class)
                .hasMessageContaining("circular");
    }
}
```

- [ ] **Step 2: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests CircularReferenceConstructorTest
git add sfs-beans/
git commit -m "test(sfs-beans): 생성자 순환 참조 예외 통합 테스트"
```

---

### Task 32: 통합 테스트 — FactoryBean + BeanPostProcessor 순서

**Files:**
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/integration/FactoryBeanTest.java`
- Create: `sfs-beans/src/test/java/com/choisk/sfs/beans/integration/BeanPostProcessorOrderTest.java`

- [ ] **Step 1: `FactoryBeanTest`**

```java
package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.BeanIsNotAFactoryException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FactoryBeanTest {

    static class DateFormatterFactory implements FactoryBean<java.time.format.DateTimeFormatter> {
        public java.time.format.DateTimeFormatter getObject() {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        }
        public Class<?> getObjectType() { return java.time.format.DateTimeFormatter.class; }
    }

    static class PlainBean {}

    @Test
    void getBeanWithoutPrefixReturnsProduct() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object product = factory.getBean("fmt");
        assertThat(product).isInstanceOf(java.time.format.DateTimeFormatter.class);
    }

    @Test
    void getBeanWithPrefixReturnsFactoryItself() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object itself = factory.getBean("&fmt");
        assertThat(itself).isInstanceOf(DateFormatterFactory.class);
    }

    @Test
    void prefixOnNonFactoryThrows() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("plain", new BeanDefinition(PlainBean.class));
        assertThatThrownBy(() -> factory.getBean("&plain"))
                .isInstanceOf(BeanIsNotAFactoryException.class);
    }

    @Test
    void singletonFactoryBeanCachesProduct() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("fmt", new BeanDefinition(DateFormatterFactory.class));
        Object first = factory.getBean("fmt");
        Object second = factory.getBean("fmt");
        assertThat(first).isSameAs(second);
    }
}
```

- [ ] **Step 2: `BeanPostProcessorOrderTest`**

```java
package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.*;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BeanPostProcessorOrderTest {

    static class Widget implements BeanNameAware, InitializingBean {
        List<String> calls = new ArrayList<>();
        @Override public void setBeanName(String name) { calls.add("awareName:" + name); }
        @Override public void afterPropertiesSet() { calls.add("afterProps"); }
        public void customInit() { calls.add("customInit"); }
    }

    static class TracingBPP implements BeanPostProcessor {
        final List<String> log;
        TracingBPP(List<String> log) { this.log = log; }
        public Object postProcessBeforeInitialization(Object bean, String n) {
            if (bean instanceof Widget) ((Widget) bean).calls.add("bpp:before");
            return bean;
        }
        public Object postProcessAfterInitialization(Object bean, String n) {
            if (bean instanceof Widget) ((Widget) bean).calls.add("bpp:after");
            return bean;
        }
    }

    @Test
    void callbackOrderMatchesSpringSpec() {
        var factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new TracingBPP(new ArrayList<>()));
        factory.registerBeanDefinition("w",
                new BeanDefinition(Widget.class).setInitMethodName("customInit"));
        var w = factory.getBean("w", Widget.class);
        assertThat(w.calls).containsExactly(
                "awareName:w",
                "bpp:before",
                "afterProps",
                "customInit",
                "bpp:after"
        );
    }
}
```

- [ ] **Step 3: 두 테스트 PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests FactoryBeanTest --tests BeanPostProcessorOrderTest
git add sfs-beans/
git commit -m "test(sfs-beans): FactoryBean + BeanPostProcessor 호출 순서 통합 테스트"
```

---

### Task 33: Plan 1A DoD 최종 검증 + README 스냅샷

**Files:**
- Create: `sfs-beans/README.md`

- [ ] **Step 1: `sfs-beans/README.md` 작성**

```markdown
# sfs-beans

Spring From Scratch의 **컨테이너 코어 모듈**. `BeanDefinition`, `BeanFactory` 계층,
`FactoryBean`, 확장점 인터페이스들, 3-level cache 기반 싱글톤 레지스트리를 담당한다.

## 의존 관계
- 의존: `sfs-core` (유틸, 예외, ASM 메타데이터)
- 의존됨: `sfs-context` (Plan 1B에서 추가)

## Spring 원본 매핑

| 이 모듈 | Spring 원본 |
|---|---|
| `BeanFactory` / `ListableBeanFactory` / `HierarchicalBeanFactory` | 동명 인터페이스 |
| `BeanDefinition` | `AbstractBeanDefinition` 계열을 mutable class로 단순화 |
| `DefaultSingletonBeanRegistry` | 동명. `CacheLookup` sealed 결과는 우리 개선 |
| `AbstractBeanFactory` / `AbstractAutowireCapableBeanFactory` / `DefaultListableBeanFactory` | 동명 |
| `FactoryBean` | 동명 |
| `BeanPostProcessor` / `InstantiationAwareBeanPostProcessor` / `SmartInstantiationAwareBeanPostProcessor` | 동명 |
| `BeanFactoryPostProcessor` | 동명 |

## 내부 구현 특징 (Approach 3)

- `CacheLookup` **sealed interface**로 3-level 조회 결과 표현 → 호출부에서 pattern matching switch
- `Scope` **sealed interface**로 Singleton/Prototype 닫기 (Request/Session은 sealed에 추가로 확장)
- `BeanDefinition`은 **mutable class**로 유지 — BFPP가 수정할 수 있어야 함
- `PropertyValue`, `BeanReference`, `BeanCreationContext`는 **record**

## 학습 포인트

### 3-Level Cache 핵심 아이디어
1차 `singletonObjects` = 완성된 빈, 2차 `earlySingletonObjects` = 조기 노출된 참조,
3차 `singletonFactories` = `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference`
훅을 실행할 수 있는 지연 팩토리. 자세한 시나리오는 `docs/superpowers/specs/2026-04-19-ioc-container-design.md` 섹션 5 참조.

### 승격 시점 분리
`lookup()`이 3차 hit일 때 자동 승격하지 않고 `promoteToEarlyReference()` 별도 호출.
이유: 호출자가 SmartBPP 체인 실행 여부를 제어해야 하므로 (Spring 원본과 동일 의도).

## 테스트 실행
\`\`\`bash
./gradlew :sfs-beans:test
\`\`\`

주요 통합 테스트:
- `integration/CircularReferenceSetterTest` — 3-level cache 순환 해결
- `integration/CircularReferenceConstructorTest` — 생성자 순환 적절 예외
- `integration/FactoryBeanTest` — `&` 접두사 + 싱글톤 캐싱
- `integration/BeanPostProcessorOrderTest` — 콜백 순서 (awareName → bpp:before → afterProps → customInit → bpp:after)
```

- [ ] **Step 2: `sfs-core/README.md` 간단 작성**

```markdown
# sfs-core

Spring From Scratch의 공통 유틸리티 계층. 다른 모든 모듈이 의존하지만
이 모듈 자체는 ASM 외 외부 의존이 없다.

## 주요 타입
- `Assert` — 인자 검증 유틸 (Spring `Assert` 대응)
- `ClassUtils`, `ReflectionUtils` — 리플렉션 래퍼
- `ClassPathScanner` — 파일 시스템 기반 `.class` 열거
- `AnnotationMetadataReader` — ASM 기반 애노테이션 메타데이터 추출 (클래스 로드 없이)
- `BeansException` sealed 계층 — 모든 컨테이너 예외의 루트
```

- [ ] **Step 3: Plan 1A 최종 검증**

```bash
./gradlew build
```
예상: BUILD SUCCESSFUL, `sfs-core` + `sfs-beans` 전 테스트 PASS.

- [ ] **Step 4: 최종 커밋**

```bash
git add sfs-core/README.md sfs-beans/README.md
git commit -m "docs: sfs-core와 sfs-beans 모듈 README 추가 (Spring 매핑 포함)"
```

---

## 🎯 Plan 1A Definition of Done — 최종 체크리스트

**기능:**
- [ ] `DefaultListableBeanFactory`를 new로 생성 가능
- [ ] `registerBeanDefinition(name, definition)`으로 빈 등록
- [ ] `getBean(name)`, `getBean(name, Class)`, `getBean(Class)` 전 variant 동작
- [ ] 싱글톤/프로토타입 스코프 구분 동작
- [ ] `@Primary` 해결 (수동 `setPrimary(true)`로 테스트됨)
- [ ] `FactoryBean<T>` 지원 + `&` 접두사 + 제품 캐싱
- [ ] `BeanPostProcessor` / `InstantiationAwareBeanPostProcessor` / `SmartInstantiationAwareBeanPostProcessor` / `BeanFactoryPostProcessor` 전 훅 동작
- [ ] `BeanNameAware`, `BeanFactoryAware` 콜백
- [ ] `InitializingBean`, `DisposableBean`, `initMethodName`, `destroyMethodName`
- [ ] 3-level cache로 세터/필드 순환 참조 해결
- [ ] 생성자 순환 참조 시 명확한 예외
- [ ] `destroySingletons()` 역순 실행 + 부분 실패 복원

**품질:**
- [ ] `./gradlew build` BUILD SUCCESSFUL
- [ ] 모든 단위 테스트 + 통합 테스트 PASS
- [ ] 각 예외가 "Possible solutions" 힌트 포함
- [ ] 각 모듈 README에 Spring 매핑표

**커밋 히스토리:**
- [ ] 의미 있는 단위별 커밋 (태스크 ≈ 커밋)
- [ ] 한국어 커밋 메시지

---

## ▶ Plan 1A 완료 후 다음 단계

1. **Plan 1B 작성**: 사용자 승인 후 이어서 작성. 범위:
   - `sfs-context` 모듈 생성
   - 애노테이션 (`@Component`, `@Configuration`, `@Bean`, `@Autowired`, `@Primary`, `@Qualifier`, `@Lazy`, `@Scope`, `@PostConstruct`, `@PreDestroy`)
   - `ApplicationContext` + `AbstractApplicationContext` + `AnnotationConfigApplicationContext`
   - `ClassPathBeanDefinitionScanner`, `ConfigurationClassPostProcessor`, `AutowiredAnnotationBeanPostProcessor`, `CommonAnnotationBeanPostProcessor`
   - refresh() 8단계 + close() 플로우

2. **Plan 1C 작성**: 샘플 앱 + Spring 교차 검증.

3. **구현 착수**: Plan 1A 완성되면 서브에이전트 기반 자동 실행 또는 인라인 배치 실행 중 선택.
