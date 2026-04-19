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

### Task 6: `ClassUtils` + `ReflectionUtils`

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ClassUtils.java`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ReflectionUtils.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ClassUtilsTest.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ReflectionUtilsTest.java`

- [ ] **Step 1: `ClassUtilsTest` 실패 테스트**

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

- [ ] **Step 2: FAIL 확인 후 `ClassUtils` 구현**

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

- [ ] **Step 3: `ReflectionUtilsTest` 실패 테스트**

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

- [ ] **Step 4: FAIL 확인 후 `ReflectionUtils` 구현**

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

- [ ] **Step 5: 테스트 PASS 확인 & 커밋**

```bash
./gradlew :sfs-core:test --tests ClassUtilsTest --tests ReflectionUtilsTest
git add sfs-core/
git commit -m "feat(sfs-core): ClassUtils 및 ReflectionUtils 추가"
```

---

### Task 7: `ClassPathScanner` — 클래스패스 파일 나열

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/ClassPathScanner.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/ClassPathScannerTest.java`

> 이 태스크의 책임은 **클래스 로드 없이** 특정 패키지 하위의 `.class` 파일 이름만 나열하는 것. 애노테이션 판단은 Task 8(ASM)에서.

- [ ] **Step 1: 실패 테스트**

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

- [ ] **Step 2: FAIL 확인 후 구현**

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

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew :sfs-core:test --tests ClassPathScannerTest
```
예상: PASS (현재 패키지에 `Assert`와 `BeansException`이 존재하므로).

- [ ] **Step 4: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): ClassPathScanner 구현 (file URL 기반)"
```

---

### Task 8: `AnnotationMetadata` + ASM 기반 리더

**Files:**
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationMetadata.java`
- Create: `sfs-core/src/main/java/com/choisk/sfs/core/AnnotationMetadataReader.java`
- Create: `sfs-core/src/test/java/com/choisk/sfs/core/AnnotationMetadataReaderTest.java`

- [ ] **Step 1: `AnnotationMetadata` record 정의**

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

- [ ] **Step 2: 실패 테스트 작성 (ASM 리더)**

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

- [ ] **Step 3: FAIL 확인 후 `AnnotationMetadataReader` 구현 (ASM)**

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

- [ ] **Step 4: 테스트 PASS 확인**

```bash
./gradlew :sfs-core:test --tests AnnotationMetadataReaderTest
```

- [ ] **Step 5: 커밋**

```bash
git add sfs-core/
git commit -m "feat(sfs-core): ASM 기반 AnnotationMetadataReader 구현"
```

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

- [ ] **Step 1: 실패 테스트**

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

- [ ] **Step 2: 구현**

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

- [ ] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests ScopeTest
git add sfs-beans/
git commit -m "feat(sfs-beans): Scope sealed interface (Singleton/Prototype)"
```

---

### Task 10: `AutowireMode` enum

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/AutowireMode.java`

- [ ] **Step 1: 구현 (단순 enum이라 테스트는 BeanDefinition에서 간접 검증)**

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

- [ ] **Step 2: 커밋**

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

- [ ] **Step 1: 실패 테스트**

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

- [ ] **Step 2: FAIL 확인 후 구현**

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

- [ ] **Step 3: PASS 확인 & 커밋**

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

- [ ] **Step 1: 실패 테스트**

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

- [ ] **Step 2: FAIL 확인 후 구현**

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

- [ ] **Step 3: PASS 확인 & 커밋**

```bash
./gradlew :sfs-beans:test --tests BeanDefinitionTest
git add sfs-beans/
git commit -m "feat(sfs-beans): BeanDefinition mutable 클래스 구현"
```

---

### Task 13: `CacheLookup` sealed interface (3-level cache 결과 표현)

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/CacheLookup.java`

- [ ] **Step 1: 구현 (별도 테스트 없이 Task 20~21 통합 테스트에서 검증)**

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

- [ ] **Step 2: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/CacheLookup.java
git commit -m "feat(sfs-beans): CacheLookup sealed interface (3-level 캐시 결과)"
```

---

### Task 14: `ObjectFactory` + `BeanCreationContext` + `CreationStage`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ObjectFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanCreationContext.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/CreationStage.java`

- [ ] **Step 1: `ObjectFactory` 구현**

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

- [ ] **Step 2: `CreationStage` sealed**

```java
package com.choisk.sfs.beans;

public sealed interface CreationStage {
    enum Instantiating implements CreationStage { INSTANCE }
    enum Populating    implements CreationStage { INSTANCE }
    enum Initializing  implements CreationStage { INSTANCE }
}
```

- [ ] **Step 3: `BeanCreationContext` record**

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

- [ ] **Step 4: 커밋**

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

## 🚧 이어서 작성 예정 (Task 15~33)

- **섹션 D (Task 15~19):** `BeanFactory` 인터페이스 계층 + `FactoryBean` + 확장점 인터페이스
- **섹션 E (Task 20~23):** `DefaultSingletonBeanRegistry` — 3-level cache + 순환 참조 감지 + destruction 콜백
- **섹션 F (Task 24~29):** `AbstractBeanFactory` → `AbstractAutowireCapableBeanFactory` → `DefaultListableBeanFactory`
- **섹션 G (Task 30~33):** 통합 테스트 (세터/필드 순환, 생성자 순환, FactoryBean, BPP 순서) + Plan 1A DoD 검증
