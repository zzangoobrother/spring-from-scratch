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

## 섹션 D: BeanFactory 인터페이스 계층 + 확장점 (Task 15~19)

### Task 15: `BeanFactory` + `HierarchicalBeanFactory` + `ListableBeanFactory`

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/BeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/HierarchicalBeanFactory.java`
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/ListableBeanFactory.java`

> 인터페이스는 동작이 없어 단독 테스트 의미가 작다. `DefaultListableBeanFactory` 통합 테스트(Task 29)에서 합쳐 검증.

- [ ] **Step 1: `BeanFactory` 정의**

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

- [ ] **Step 2: `HierarchicalBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface HierarchicalBeanFactory extends BeanFactory {
    BeanFactory getParentBeanFactory();

    boolean containsLocalBean(String name);
}
```

- [ ] **Step 3: `ListableBeanFactory`**

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

- [ ] **Step 4: 컴파일 확인 & 커밋**

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

- [ ] **Step 1: `AutowireCapableBeanFactory`**

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

- [ ] **Step 2: `ConfigurableBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface ConfigurableBeanFactory extends HierarchicalBeanFactory {

    void registerSingleton(String name, Object bean);

    void addBeanPostProcessor(BeanPostProcessor processor);

    int getBeanPostProcessorCount();

    void destroySingletons();
}
```

- [ ] **Step 3: `ConfigurableListableBeanFactory`**

```java
package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory {

    void registerBeanDefinition(String name, BeanDefinition definition);

    BeanDefinition getBeanDefinition(String name);

    void preInstantiateSingletons();
}
```

- [ ] **Step 4: 컴파일 & 커밋**

```bash
./gradlew :sfs-beans:compileJava
git add sfs-beans/
git commit -m "feat(sfs-beans): AutowireCapable / Configurable BeanFactory 인터페이스 추가"
```

---

### Task 17: `FactoryBean` 인터페이스

**Files:**
- Create: `sfs-beans/src/main/java/com/choisk/sfs/beans/FactoryBean.java`

- [ ] **Step 1: 구현**

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

- [ ] **Step 2: 커밋**

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

- [ ] **Step 1: `BeanPostProcessor` (가장 기본)**

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

- [ ] **Step 2: `InstantiationAwareBeanPostProcessor`**

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

- [ ] **Step 3: `SmartInstantiationAwareBeanPostProcessor`**

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

- [ ] **Step 4: `BeanFactoryPostProcessor`**

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

- [ ] **Step 5: 커밋**

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

- [ ] **Step 1~5: 각 인터페이스 파일 생성**

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

- [ ] **Step 6: 커밋**

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

- [ ] **Step 1: 실패 테스트**

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

- [ ] **Step 2: FAIL 확인 후 구현 (1차 캐시만)**

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

- [ ] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
```

- [ ] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): DefaultSingletonBeanRegistry 1차 캐시 구현"
```

---

### Task 21: 2차 + 3차 캐시 + `CacheLookup` 기반 조회 + 승격 ⭐

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Modify: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [ ] **Step 1: 실패 테스트 추가**

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

- [ ] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 2차/3차 추가**

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

- [ ] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :sfs-beans:test --tests DefaultSingletonBeanRegistryTest
```

- [ ] **Step 4: 커밋**

```bash
git add sfs-beans/
git commit -m "feat(sfs-beans): 2차/3차 캐시 + CacheLookup 기반 lookup/promote 구현"
```

---

### Task 22: "현재 생성 중" 추적 (`ThreadLocal`)

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistry.java`
- Modify: `sfs-beans/src/test/java/com/choisk/sfs/beans/DefaultSingletonBeanRegistryTest.java`

- [ ] **Step 1: 실패 테스트 추가**

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

- [ ] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 추가**

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

- [ ] **Step 3: PASS 확인 & 커밋**

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

- [ ] **Step 1: 실패 테스트 추가**

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

- [ ] **Step 2: FAIL 확인 후 `DefaultSingletonBeanRegistry`에 추가**

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

- [ ] **Step 3: PASS 확인 & 커밋**

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

## 🚧 이어서 작성 예정 (Task 24~33)

- **섹션 F (Task 24~29):** `AbstractBeanFactory` → `AbstractAutowireCapableBeanFactory` → `DefaultListableBeanFactory`
- **섹션 G (Task 30~33):** 통합 테스트 (세터/필드 순환, 생성자 순환, FactoryBean, BPP 순서) + Plan 1A DoD 검증
