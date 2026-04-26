# Phase 2A — byte-buddy 도입 + `@Configuration` enhance + `@ComponentScan` + IoC 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 1의 두 거짓말(`proxyBeanMethods=true`가 동작 안 함 / `@ComponentScan` 애노테이션 부재)을 byte-buddy 기반 enhance와 표준 Spring 패턴 도입으로 해소하고, 1B-β/1C에서 박제된 정리 항목 3종을 함께 처리한다.

**Architecture:** `sfs-context`에 `ConfigurationClassEnhancer` + `BeanMethodInterceptor` 신설 (byte-buddy `MethodDelegation` + `MethodInterceptor` 패턴). `ConfigurationClassPostProcessor`를 확장하여 (1) `@ComponentScan` 처리, (2) `@Bean` 메서드 → factoryMethod BD 등록 (기존), (3) enhance 적용 3단계로 동작. 다운캐스팅 정리는 `ConfigurableBeanFactory`/`ConfigurableListableBeanFactory` 인터페이스에 메서드를 노출하여 BPP 처리기들이 인터페이스 타입으로 의존하도록 변경.

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL), JUnit 5 + AssertJ, byte-buddy 1.14.19 (이미 `sfs-context/build.gradle.kts`에 의존 추가됨).

**Selected Spec:** `docs/superpowers/specs/2026-04-26-phase2a-byte-buddy-cleanup-design.md`

**선행 조건:** Phase 1C 완료 + main 머지 (`33f653e`, 138 PASS). 브랜치: `feat/phase2a-byte-buddy` (이미 생성됨, main 베이스).

**확인된 인프라 (plan 작성 시점 grep):**
- byte-buddy 카탈로그 등록 (`gradle/libs.versions.toml`) + `sfs-context/build.gradle.kts:7` `implementation(libs.bytebuddy)` — 직접 사용 0건 상태
- `@Configuration.proxyBeanMethods` 플래그 메타데이터만 존재 (동작 미구현)
- `ClassPathBeanDefinitionScanner` + `AnnotationConfigApplicationContext(String...)` 생성자 동작 (`ComponentScanIntegrationTest` PASS)
- `ConfigurationClassPostProcessor` — `@Bean` 메서드 처리만, enhance 없음
- `DefaultListableBeanFactory.resolveDependency` (line 158) public 메서드 (인터페이스 미노출)
- `DefaultSingletonBeanRegistry.registerDisposableBean` / `containsSingleton` public 메서드 (인터페이스 미노출)
- `Phase1IntegrationTest.directCallFormCreatesDistinctInstanceWithoutEnhance` (line 117) — 어서션 `isNotSameAs`, *마일스톤 박제 대상*
- `EnhanceAbsenceDemo` + `EnhanceAbsenceDemoTest` 한 쌍 (sfs-samples) — rename 대상

**Selected dispatch policy (Q5=A'):** byte-buddy 직접 닿는 task만 풀 사이클(impl + spec reviewer + quality reviewer), 정리/rename은 spec only. 표시는 각 task 헤더의 `> **Dispatch:** ...` 라인 참조.

**End state:** 다음이 모두 동작:

```bash
./gradlew build                              # BUILD SUCCESSFUL
./gradlew :sfs-context:test :sfs-samples:test
# 회귀 ~141~142 PASS / 0 FAIL (138 → +3~4)
```

```java
// EnhanceAbsenceDemo의 마일스톤이 살아 움직임 — 두 줄 모두 → true
// (rename 후: ConfigurationEnhanceDemo)
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 적용): account.user == ctx.user → true
```

```java
// TodoDemoApplication.main()이 한 줄 진입점으로 축약
new AnnotationConfigApplicationContext(AppConfig.class)
// AppConfig에 @ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
```

---

## 섹션 구조 (Task 한 줄 요약)

| 섹션 | 범위 | Task | TDD | Dispatch |
|---|---|---|---|---|
| **A** | 다운캐스팅 정리 (인터페이스 승격 + BPP 처리기 + AnnotationConfigUtils) | A1 | 제외 | spec only |
| **B** | byte-buddy 인프라 — `BeanMethodInterceptor` + `ConfigurationClassEnhancer` | B1, B2 | 적용 | **풀 사이클** |
| **C** | `ConfigurationClassPostProcessor` 확장 — enhance 적용 + `@ComponentScan` | C1, C2 | 적용 | C1=풀 사이클 / C2=spec only |
| **D** | `sfs-samples` 갱신 — IdGenerator 통일 + rename + ComponentScan 적용 | D1, D2, D3 | 제외 | spec only |
| **E** | 마감 (README + DoD + 마감 게이트) | E1 | 제외 | spec only |

총 **9 Task**. 누적 테스트 카운트 예상: 138 → **~141~142** (B1 +3, B2 +2, C1 +0 어서션 갱신, C2 +1, 그 외 0).

**의존 흐름:** `A1` (인프라 정리, 독립) → `B1` (Interceptor) → `B2` (Enhancer, B1 의존) → `C1` (CCPostProcessor enhance, B2 의존 + Phase1IntegrationTest 갱신) → `C2` (@ComponentScan 애노테이션 + 처리) → `D1` (UserService 정리, 독립) → `D2` (EnhanceAbsenceDemo rename, C1 의존) → `D3` (TodoDemoApplication 축약, C2 + D1 의존) → `E1` (마감).

> **메모:** A1과 D1은 다른 task와 의존 없음 — 병렬 가능. 단 plan 실행은 순차로 작성.

---

## 섹션 A: 다운캐스팅 정리 (Task A1)

### Task A1: `ConfigurableBeanFactory`/`ConfigurableListableBeanFactory` 메서드 승격 + BPP 처리기 인터페이스화 + `AnnotationConfigUtils` 다운캐스팅 제거

> **TDD 적용 여부:** 제외 — 시그니처/타입 변경, 동작 동일. 130 회귀 안전망으로 검증.
> **Dispatch:** spec only

**근거 (Phase 1B-β plan 보류 박제 🔵 Phase 2 직전):**
- `(DefaultListableBeanFactory) this` 다운캐스팅 위험 — AOP 서브클래스 등장 시 `ClassCastException`
- `AutowiredAnnotationBeanPostProcessor` / `CommonAnnotationBeanPostProcessor` 생성자가 `DefaultListableBeanFactory` 구현 클래스 직접 의존

**해결 전략:**
1. `ConfigurableBeanFactory`에 `registerDisposableBean` + `containsSingleton` 메서드 시그니처 추가 (이미 `DefaultSingletonBeanRegistry`에 public 구현 존재 → 자동 만족)
2. `ConfigurableListableBeanFactory`에 `resolveDependency` 메서드 시그니처 추가 (이미 `DefaultListableBeanFactory`에 public 구현 존재 → 자동 만족)
3. BPP 처리기 생성자 파라미터 타입을 인터페이스로 변경
4. `AnnotationConfigUtils.registerAnnotationConfigProcessors` 다운캐스팅 제거

**Files:**
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableBeanFactory.java`
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java`
- Modify: `sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java` (`@Override` 추가만)
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java`
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java`
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java`

- [x] **Step 1: `ConfigurableBeanFactory`에 `registerDisposableBean` + `containsSingleton` 시그니처 추가**

```java
// sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableBeanFactory.java
package com.choisk.sfs.beans;

import java.util.List;

public interface ConfigurableBeanFactory extends HierarchicalBeanFactory {

    void registerSingleton(String name, Object bean);

    void addBeanPostProcessor(BeanPostProcessor processor);

    int getBeanPostProcessorCount();

    /** 등록된 BPP 목록 조회 (AnnotationConfigUtils 멱등성 검사 등에서 사용). */
    List<BeanPostProcessor> getBeanPostProcessors();

    void destroySingletons();

    /**
     * 소멸 콜백 등록. {@code @PreDestroy} 메서드를 close() 시점에 호출하기 위해
     * BPP 처리기({@link com.choisk.sfs.context.support.CommonAnnotationBeanPostProcessor})가 사용.
     */
    void registerDisposableBean(String name, Runnable callback);

    /**
     * 완성된 싱글톤이 캐시에 있는지 확인. {@code BeanMethodInterceptor}가
     * {@code @Bean} 메서드 직접 호출 시 컨테이너 라우팅 가능 여부 판단에 사용.
     */
    boolean containsSingleton(String name);
}
```

> **시연 요점:** 두 메서드는 *이미 `DefaultSingletonBeanRegistry`에 public method로 존재*. 인터페이스에 시그니처를 추가만 해도 `AbstractBeanFactory extends DefaultSingletonBeanRegistry implements ConfigurableBeanFactory` 관계로 자동 만족됨. 별도 구현 추가 불필요.

- [x] **Step 2: `ConfigurableListableBeanFactory`에 `resolveDependency` 시그니처 추가**

```java
// sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java
package com.choisk.sfs.beans;

public interface ConfigurableListableBeanFactory
        extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory, BeanDefinitionRegistry {

    void preInstantiateSingletons();

    /**
     * 의존성 해석. {@link AutowiredAnnotationBeanPostProcessor}가
     * {@code @Autowired} 필드 주입에 사용.
     *
     * @param desc 의존성 메타 (타입 + required + name)
     * @param requestingBeanName 요청 빈 이름 (순환 참조 감지용)
     * @return 해석된 빈 또는 null (required=false + 미매칭)
     */
    Object resolveDependency(DependencyDescriptor desc, String requestingBeanName);
}
```

- [x] **Step 3: `DefaultListableBeanFactory.resolveDependency`에 `@Override` 추가**

`DefaultListableBeanFactory.java` 158번째 줄 근처의 `resolveDependency` 메서드 선언 위에 `@Override` 추가:

```java
    @Override
    public Object resolveDependency(DependencyDescriptor desc, String requestingBeanName) {
        // 본문은 그대로 유지
        ...
    }
```

> **메모:** `DefaultSingletonBeanRegistry.registerDisposableBean` / `containsSingleton`은 *부모 클래스* 메서드라서 `@Override` 추가 위치가 다른 이슈가 있을 수 있음. 인터페이스 메서드 만족은 *상속 체인 어디서든* 가능하므로 별도 작업 불필요. 컴파일러가 만족 여부를 검증함.

- [x] **Step 4: `AutowiredAnnotationBeanPostProcessor` 생성자 파라미터 타입 변경**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.DependencyDescriptor;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.PropertyValues;
import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.core.ReflectionUtils;

public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    /** 의존성 해석에 사용할 빈 팩토리 — 인터페이스 의존 (AOP 서브클래스 등장 시 ClassCastException 사전 차단) */
    private final ConfigurableListableBeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            Autowired autowired = field.getAnnotation(Autowired.class);
            if (autowired == null) {
                return;
            }
            DependencyDescriptor desc = new DependencyDescriptor(
                    field.getType(), autowired.required(), field.getName());
            Object dep = beanFactory.resolveDependency(desc, beanName);
            if (dep == null) {
                return;
            }
            ReflectionUtils.setField(field, bean, dep);
        });
        return pvs;
    }
}
```

> **변경 요지:** import의 `DefaultListableBeanFactory` → `ConfigurableListableBeanFactory`, 필드 + 생성자 파라미터 타입 동일 변경. 본문 변경 없음.

- [x] **Step 5: `CommonAnnotationBeanPostProcessor` 생성자 파라미터 타입 변경**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.PreDestroy;
import com.choisk.sfs.core.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class CommonAnnotationBeanPostProcessor implements BeanPostProcessor {

    /** @PreDestroy 등록 시 beanFactory.registerDisposableBean 호출에 활용 — 인터페이스 의존 */
    private final ConfigurableBeanFactory beanFactory;

    public CommonAnnotationBeanPostProcessor(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        ReflectionUtils.doWithMethods(bean.getClass(), m -> {
            if (!m.isAnnotationPresent(PostConstruct.class)) {
                return;
            }
            ReflectionUtils.invokeMethod(m, bean);
        });
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        List<Method> preDestroyMethods = new ArrayList<>();
        ReflectionUtils.doWithMethods(bean.getClass(), m -> {
            if (m.isAnnotationPresent(PreDestroy.class)) {
                m.setAccessible(true);
                preDestroyMethods.add(m);
            }
        });
        if (!preDestroyMethods.isEmpty()) {
            beanFactory.registerDisposableBean(beanName, () -> {
                for (Method m : preDestroyMethods) {
                    try {
                        m.invoke(bean);
                    } catch (ReflectiveOperationException e) {
                        System.err.println("@PreDestroy 호출 실패 — beanName=" + beanName
                                + ", method=" + m.getName() + ": " + e);
                    }
                }
            });
        }
        return bean;
    }
}
```

> **변경 요지:** `DefaultListableBeanFactory` → `ConfigurableBeanFactory` (resolveDependency 안 쓰므로 ListableBeanFactory보다 약한 타입으로 충분). 본문 변경 없음.

- [x] **Step 6: `AnnotationConfigUtils` 다운캐스팅 제거**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.ConfigurableApplicationContext;

public final class AnnotationConfigUtils {

    private AnnotationConfigUtils() {}

    public static void registerAnnotationConfigProcessors(ConfigurableApplicationContext ctx) {
        ConfigurableListableBeanFactory bf = ctx.getBeanFactory();

        // 1. ConfigurationClassPostProcessor (BFPP) — @Bean 메서드 → BD 변환
        if (!hasBfpp(ctx, ConfigurationClassPostProcessor.class)) {
            ctx.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
        }

        // 2. AutowiredAnnotationBeanPostProcessor (BPP) — @Autowired 필드 주입
        if (!hasBpp(ctx, AutowiredAnnotationBeanPostProcessor.class)) {
            bf.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(bf));
        }

        // 3. CommonAnnotationBeanPostProcessor (BPP) — @PostConstruct/@PreDestroy
        if (!hasBpp(ctx, CommonAnnotationBeanPostProcessor.class)) {
            bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        }
    }

    private static boolean hasBfpp(ConfigurableApplicationContext ctx, Class<? extends BeanFactoryPostProcessor> type) {
        return ctx.getBeanFactoryPostProcessors().stream().anyMatch(type::isInstance);
    }

    private static boolean hasBpp(ConfigurableApplicationContext ctx, Class<? extends BeanPostProcessor> type) {
        return ctx.getBeanFactory().getBeanPostProcessors().stream().anyMatch(type::isInstance);
    }
}
```

> **변경 요지:** 다운캐스팅 블록(`if (!(ctx.getBeanFactory() instanceof DefaultListableBeanFactory dlbf)) { throw ... }`) 통째로 제거. import의 `DefaultListableBeanFactory` 제거 → `ConfigurableListableBeanFactory`만 import.

- [x] **Step 7: 컴파일 + 회귀 검증**

```bash
./gradlew :sfs-beans:compileJava :sfs-context:compileJava :sfs-samples:compileJava
./gradlew :sfs-beans:test :sfs-context:test :sfs-samples:test
./gradlew build
```

예상: BUILD SUCCESSFUL. 회귀 138 PASS / 0 FAIL 유지 (sfs-core 28 + sfs-beans 58 + sfs-context 44 + sfs-samples 8).

- [x] **Step 8: 커밋**

```bash
git add sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableBeanFactory.java \
        sfs-beans/src/main/java/com/choisk/sfs/beans/ConfigurableListableBeanFactory.java \
        sfs-beans/src/main/java/com/choisk/sfs/beans/support/DefaultListableBeanFactory.java \
        sfs-context/src/main/java/com/choisk/sfs/context/support/AutowiredAnnotationBeanPostProcessor.java \
        sfs-context/src/main/java/com/choisk/sfs/context/support/CommonAnnotationBeanPostProcessor.java \
        sfs-context/src/main/java/com/choisk/sfs/context/support/AnnotationConfigUtils.java
git commit -m "refactor(sfs-beans,sfs-context): BPP 처리기 인터페이스 의존 — DefaultListableBeanFactory 다운캐스팅 제거 (1B-β 박제 🔵 회수)"
```

---

## 섹션 B: byte-buddy 인프라 (Task B1, B2)

### Task B1: `BeanMethodInterceptor` + 단위 테스트

> **TDD 적용 여부:** 적용 — 인터셉터의 *분기 동작*(cache hit vs miss + name resolution)이 본질.
> **Dispatch:** **풀 사이클** (impl + spec reviewer + quality reviewer)

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/BeanMethodInterceptor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/BeanMethodInterceptorTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-context/src/test/java/com/choisk/sfs/context/support/BeanMethodInterceptorTest.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class BeanMethodInterceptorTest {

    static class TestConfig {
        @Bean
        public Object user() { return new Object(); }

        @Bean(name = "customAccount")
        public Object account() { return new Object(); }
    }

    @Test
    void interceptReturnsCachedBeanWhenContainerHasIt() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        Object cachedBean = new Object();
        factory.registerSingleton("user", cachedBean);

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("user");
        Callable<Object> superCall = () -> {
            throw new AssertionError("superCall은 캐시 hit 시 호출되면 안 됨");
        };

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result).isSameAs(cachedBean);
    }

    @Test
    void interceptCallsSuperWhenBeanNotCached() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("user");
        Object freshBean = new Object();
        Callable<Object> superCall = () -> freshBean;

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result)
                .as("캐시 miss 시 superCall 결과를 그대로 반환")
                .isSameAs(freshBean);
    }

    @Test
    void interceptUsesBeanAnnotationNameOverride() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        Object cachedBean = new Object();
        factory.registerSingleton("customAccount", cachedBean);

        BeanMethodInterceptor interceptor = new BeanMethodInterceptor(factory);
        Method method = TestConfig.class.getDeclaredMethod("account");
        Callable<Object> superCall = () -> {
            throw new AssertionError("@Bean(name=\"customAccount\")로 등록된 빈을 찾아야 함");
        };

        Object result = interceptor.intercept(superCall, method, new Object[0]);
        assertThat(result)
                .as("@Bean(name=\"customAccount\") 처리 — 메서드명이 아닌 name 어노테이션 값 우선")
                .isSameAs(cachedBean);
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.BeanMethodInterceptorTest"
```

예상: 컴파일 에러 (BeanMethodInterceptor 클래스 없음).

- [x] **Step 3: `BeanMethodInterceptor.java` 구현**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/BeanMethodInterceptor.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * {@code @Configuration} 클래스의 enhance된 서브클래스에서 {@code @Bean} 메서드 호출을 가로채
 * 컨테이너 라우팅을 적용한다.
 *
 * <p>호출 시점에 {@code containsSingleton}으로 완성된 빈이 있는지 확인하고:
 * <ul>
 *   <li>있으면 → 캐시된 빈 반환 (직접 호출 → 컨테이너 라우팅 변형)</li>
 *   <li>없으면 → {@code superCall} 위임 (원본 메서드 본문 실행)</li>
 * </ul>
 *
 * <p>이 비대칭이 enhance의 본질 — 컨테이너 자체의 *최초* 호출은 통과시키고
 * (cache miss → superCall로 신규 인스턴스 생성), 사용자 코드의 직접 호출은 라우팅됨.
 */
public class BeanMethodInterceptor {

    private final ConfigurableBeanFactory beanFactory;

    public BeanMethodInterceptor(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @RuntimeType
    public Object intercept(
            @SuperCall Callable<Object> superCall,
            @Origin Method method,
            @AllArguments Object[] args
    ) throws Exception {
        String beanName = resolveBeanName(method);
        if (beanFactory.containsSingleton(beanName)) {
            return beanFactory.getBean(beanName);
        }
        return superCall.call();
    }

    private String resolveBeanName(Method method) {
        Bean ann = method.getAnnotation(Bean.class);
        if (ann != null && ann.name().length > 0 && !ann.name()[0].isEmpty()) {
            return ann.name()[0];
        }
        return method.getName();
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.BeanMethodInterceptorTest"
```

예상: 3 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/BeanMethodInterceptor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/BeanMethodInterceptorTest.java
git commit -m "feat(sfs-context): BeanMethodInterceptor — @Bean 메서드 호출 컨테이너 라우팅 (cache hit→getBean / miss→superCall)"
```

---

### Task B2: `ConfigurationClassEnhancer` + 단위 테스트

> **TDD 적용 여부:** 적용 — enhance 결과의 *클래스 정체성*(서브클래스 + 인터셉터 적용)이 본질.
> **Dispatch:** **풀 사이클**

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassEnhancer.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassEnhancerTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassEnhancerTest.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationClassEnhancerTest {

    @Configuration
    public static class SampleConfig {
        @Bean
        public String greeting() { return "hello"; }

        @Bean
        public Integer counter() { return 42; }
    }

    @Test
    void enhanceProducesSubclassOfOriginal() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);

        Class<?> enhanced = enhancer.enhance(SampleConfig.class);

        assertThat(SampleConfig.class.isAssignableFrom(enhanced))
                .as("enhance 결과는 원본의 서브클래스여야 함")
                .isTrue();
        assertThat(enhanced)
                .as("enhance 결과는 원본 클래스 자체와 다른 클래스여야 함")
                .isNotEqualTo(SampleConfig.class);
    }

    @Test
    void enhancedClassRoutesBeanMethodCallsThroughContainer() throws Exception {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        String cachedGreeting = "cached!";
        factory.registerSingleton("greeting", cachedGreeting);

        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(factory);
        Class<?> enhanced = enhancer.enhance(SampleConfig.class);
        SampleConfig instance = (SampleConfig) enhanced.getDeclaredConstructor().newInstance();

        // greeting()은 인터셉터 경유 → 캐시된 "cached!" 반환
        assertThat(instance.greeting())
                .as("@Bean 메서드 직접 호출이 인터셉터 경유로 컨테이너 빈을 반환해야 함")
                .isEqualTo(cachedGreeting);

        // counter()는 캐시에 없으므로 superCall(원본 메서드 본문) 실행 → 42
        assertThat(instance.counter())
                .as("캐시 miss 시 원본 메서드 본문이 실행되어야 함")
                .isEqualTo(42);
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.ConfigurationClassEnhancerTest"
```

- [x] **Step 3: `ConfigurationClassEnhancer.java` 구현**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassEnhancer.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * {@code @Configuration} 클래스의 byte-buddy 서브클래스를 생성하여
 * 모든 {@code @Bean} 메서드를 {@link BeanMethodInterceptor}로 가로채도록 한다.
 *
 * <p>Spring 본가의 CGLIB 기반 enhance와 동일한 메커니즘을 byte-buddy로 구현.
 * 인터페이스가 아닌 *클래스 자체*의 서브클래스이므로 {@code final} 클래스/메서드는
 * enhance 불가 — 학습 범위 축소판은 이 제약을 수용 (검증 task는 후속 phase로 보류).
 */
public class ConfigurationClassEnhancer {

    private final ConfigurableBeanFactory beanFactory;

    public ConfigurationClassEnhancer(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Class<?> enhance(Class<?> configClass) {
        return new ByteBuddy()
                .subclass(configClass)
                .method(ElementMatchers.isAnnotatedWith(Bean.class))
                .intercept(MethodDelegation.to(new BeanMethodInterceptor(beanFactory)))
                .make()
                .load(configClass.getClassLoader())
                .getLoaded();
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.support.ConfigurationClassEnhancerTest"
./gradlew :sfs-context:test
```

예상: BeanMethodInterceptorTest 3 + ConfigurationClassEnhancerTest 2 = 5 PASS 신규. 회귀 변동 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassEnhancer.java \
        sfs-context/src/test/java/com/choisk/sfs/context/support/ConfigurationClassEnhancerTest.java
git commit -m "feat(sfs-context): ConfigurationClassEnhancer — byte-buddy 서브클래스 + @Bean 메서드 인터셉터 적용"
```

---

## 섹션 C: `ConfigurationClassPostProcessor` 확장 (Task C1, C2)

### Task C1: `ConfigurationClassPostProcessor.enhanceConfigurationClasses()` + `Phase1IntegrationTest` 어서션 갱신

> **TDD 적용 여부:** 적용 (통합) — Phase1IntegrationTest의 박제 어서션이 *isNotSameAs → isSameAs*로 변하는 것 자체가 enhance 동작 검증.
> **Dispatch:** **풀 사이클**

**Files:**
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java`
- Modify: `sfs-context/src/test/java/com/choisk/sfs/context/integration/Phase1IntegrationTest.java`

- [x] **Step 1: 통합 테스트 어서션 + 메서드명 갱신 (실패 상태로 만들기)**

`Phase1IntegrationTest.directCallFormCreatesDistinctInstanceWithoutEnhance`를 다음과 같이 변경 (메서드명 + 어서션 + Javadoc):

```java
    /**
     * 테스트 2: 직접 호출 형태 inter-bean reference — enhance 적용으로 컨테이너 라우팅 ✅
     *
     * <p>Phase 2A의 {@code ConfigurationClassEnhancer}가 {@code @Configuration} 클래스의
     * {@code @Bean} 메서드 호출을 {@code BeanMethodInterceptor}로 가로채 컨테이너 빈을 반환한다.
     * 따라서 {@code service()} 본문에서 {@code repo()}를 직접 호출해도 컨테이너의
     * 동일 Repo 싱글톤이 반환됨 — Phase 1C에서 박제한 {@code → false}가 {@code → true}로 변형.
     */
    @Test
    void directCallFormRoutesToContainerWithEnhance() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(AppConfigDirectCall.class);

        Service service = ctx.getBean(Service.class);
        Repo repo = ctx.getBean(Repo.class);

        assertThat(service.repo)
                .as("enhance 적용 시 service() 본문의 repo() 직접 호출이 컨테이너 라우팅됨")
                .isSameAs(repo);

        ctx.close();
    }
```

> **변경 요지:** 메서드명 `directCallFormCreatesDistinctInstanceWithoutEnhance` → `directCallFormRoutesToContainerWithEnhance`. 어서션 `isNotSameAs(repo)` → `isSameAs(repo)`. Javadoc 전면 갱신.

- [x] **Step 2: 테스트 실행 (FAIL — enhance 미구현, 여전히 isNotSameAs 결과가 나옴)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.Phase1IntegrationTest.directCallFormRoutesToContainerWithEnhance"
```

예상: FAIL — `Expecting actual to be the same as: <Repo@xxx> but was: <Repo@yyy>`.

- [x] **Step 3: `ConfigurationClassPostProcessor`에 `enhanceConfigurationClasses` 추가**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java
package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;

import java.lang.reflect.Method;

/**
 * @Configuration 클래스의 @Bean 메서드를 스캔해 factoryMethod BeanDefinition으로 등록하고,
 * proxyBeanMethods=true인 @Configuration 클래스는 byte-buddy로 enhance한다.
 *
 * <p>Phase 2A에서 enhance 동작 추가 — Phase 1B-β의 단순판은 enhance 없이 매개변수 라우팅만 지원했음.
 *
 * <p>등록 규칙 (변경 없음):
 * <ul>
 *   <li>@Bean(name=...) 값이 있으면 첫 번째 값을 빈 이름으로 사용</li>
 *   <li>name이 비어있으면 메서드명을 빈 이름으로 사용</li>
 * </ul>
 */
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // ① @Bean 메서드 → factoryMethod BD 등록 (기존 로직)
        registerBeanMethodsForAllConfigurations(bf);

        // ② enhance 적용 — proxyBeanMethods=true인 @Configuration 클래스
        enhanceConfigurationClasses(bf);
    }

    private void registerBeanMethodsForAllConfigurations(ConfigurableListableBeanFactory bf) {
        String[] definitionNames = bf.getBeanDefinitionNames();
        for (String configName : definitionNames) {
            BeanDefinition bd = bf.getBeanDefinition(configName);
            if (bd.getBeanClass() == null) continue;
            if (!bd.getBeanClass().isAnnotationPresent(Configuration.class)) continue;

            for (Method m : bd.getBeanClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) continue;

                Bean beanAnno = m.getAnnotation(Bean.class);
                String[] names = beanAnno.name();
                String beanName = (names.length > 0 && !names[0].isEmpty()) ? names[0] : m.getName();

                BeanDefinition beanBd = new BeanDefinition(m.getReturnType());
                beanBd.setFactoryBeanName(configName);
                beanBd.setFactoryMethodName(m.getName());

                bf.registerBeanDefinition(beanName, beanBd);
            }
        }
    }

    private void enhanceConfigurationClasses(ConfigurableListableBeanFactory bf) {
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(bf);
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            Class<?> beanClass = bd.getBeanClass();
            if (beanClass == null) continue;

            Configuration cfg = beanClass.getAnnotation(Configuration.class);
            if (cfg == null) continue;
            if (!cfg.proxyBeanMethods()) continue;

            Class<?> enhanced = enhancer.enhance(beanClass);
            bd.setBeanClass(enhanced);
        }
    }
}
```

> **시연 요점:**
> - 두 단계 분리 — 메서드 단위로 SRP 명확.
> - enhance 적용 시 `bd.setBeanClass(enhanced)` — 컨테이너가 인스턴스화 시점에 *enhance 클래스*를 `newInstance()`함.
> - `proxyBeanMethods=false` 설정 시 enhance 건너뜀 (학습자가 *진짜로* 끌 수 있음).

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.Phase1IntegrationTest"
./gradlew :sfs-context:test
```

예상: `Phase1IntegrationTest` 3 PASS 모두 통과 (`directCallFormRoutesToContainerWithEnhance` 포함). sfs-context 전체 PASS.

- [x] **Step 5: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/integration/Phase1IntegrationTest.java
git commit -m "feat(sfs-context): ConfigurationClassPostProcessor.enhanceConfigurationClasses — proxyBeanMethods=true 시 byte-buddy enhance 적용 (Phase1IntegrationTest 박제 false→true 회수)"
```

> **실행 기록 (2026-04-26):**
> - Plan Step 5의 커밋 대상에 `ConfigurationClassEnhancer.java`를 추가 포함함.
>   Java 25 모듈 시스템에서 byte-buddy 기본 `WRAPPER` 전략은 inner static class 서브클래싱 시
>   `IllegalAccessError` 발생 — `ClassLoadingStrategy.UsingLookup(privateLookupIn(...))` 전략으로
>   교체해야 `Phase1IntegrationTest`가 통과됨. B2 완료 시 테스트 inner class를 타깃으로 삼지 않았기에
>   발견되지 않았던 문제이며, C1에서 처음 실전 테스트함.
> - `./gradlew build` 전체에서 `sfs-samples:EnhanceAbsenceDemoTest`가 FAIL (enhance 활성화로
>   직접 호출도 싱글톤 반환 → `→ false` 박제가 깨짐). 이는 D2 Task의 예정 작업이므로 C1 범위 내에서
>   수정하지 않음. `:sfs-context:test` 전체 50 PASS로 C1 DoD 충족.
> - **C1 풀 사이클 reviewer 후속 (2026-04-26):** spec/quality reviewer 이슈 4건 반영 — 3 커밋 분리(refactor + feat + test). 회귀 sfs-context 50 → 53 PASS.

---

### Task C2: `@ComponentScan` 애노테이션 + `processComponentScans()` + 통합 테스트

> **TDD 적용 여부:** 적용 (통합) — `@ComponentScan` 발견 → 패키지 스캔 → BD 등록 흐름 검증.
> **Dispatch:** spec only (인프라가 부분적으로 깔려있어 변경 폭 작음)

**Files:**
- Create: `sfs-context/src/main/java/com/choisk/sfs/context/annotation/ComponentScan.java`
- Modify: `sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java`
- Test: `sfs-context/src/test/java/com/choisk/sfs/context/integration/ComponentScanFromConfigurationTest.java`

- [x] **Step 1: 실패 테스트 작성 — 새 통합 테스트**

```java
// sfs-context/src/test/java/com/choisk/sfs/context/integration/ComponentScanFromConfigurationTest.java
package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.samples.basic.MetaTaggedService;
import com.choisk.sfs.context.samples.basic.SimpleService;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanFromConfigurationTest {

    @Configuration
    @ComponentScan(basePackages = "com.choisk.sfs.context.samples.basic")
    static class ScanFromConfig {}

    @Test
    void componentScanInsideConfigurationDiscoversBeans() {
        try (var ctx = new AnnotationConfigApplicationContext(ScanFromConfig.class)) {
            assertThat(ctx.containsBean("simpleService"))
                    .as("@ComponentScan이 패키지의 @Component 빈을 발견해야 함")
                    .isTrue();
            assertThat(ctx.containsBean("metaTaggedService")).isTrue();
            assertThat(ctx.getBean("simpleService")).isInstanceOf(SimpleService.class);
            assertThat(ctx.getBean(MetaTaggedService.class)).isNotNull();
        }
    }

    @Test
    void componentScanValueAliasIsHonored() {
        @Configuration
        @ComponentScan("com.choisk.sfs.context.samples.basic")  // value() 사용
        class ValueAliasConfig {}

        try (var ctx = new AnnotationConfigApplicationContext(ValueAliasConfig.class)) {
            assertThat(ctx.containsBean("simpleService"))
                    .as("value()는 basePackages()의 alias")
                    .isTrue();
        }
    }
}
```

> **테스트 의도:** 두 형식(`basePackages` + `value`)이 같은 결과를 내는지 검증. `value()`/`basePackages()`가 동의어임을 박제.

- [x] **Step 2: 테스트 실행 (FAIL — `@ComponentScan` 클래스 없음)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.ComponentScanFromConfigurationTest"
```

예상: 컴파일 에러 (ComponentScan import 실패).

- [x] **Step 3: `@ComponentScan` 애노테이션 신설**

```java
// sfs-context/src/main/java/com/choisk/sfs/context/annotation/ComponentScan.java
package com.choisk.sfs.context.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 패키지 스캔 + @Component 빈 자동 등록을 선언하는 메타애노테이션.
 *
 * <p>{@code @Configuration} 클래스에 부착하면 {@link com.choisk.sfs.context.support.ConfigurationClassPostProcessor}가
 * BFPP 시점에 {@link com.choisk.sfs.context.support.ClassPathBeanDefinitionScanner}를 호출해
 * 지정 패키지의 {@code @Component} 보유 클래스를 BD로 등록한다.
 *
 * <p>{@code value()}와 {@code basePackages()}는 동의어 (Spring 본가 패턴).
 * 둘 다 비어있으면 *애노테이션 달린 클래스의 패키지*를 기본 스캔 — 본 phase는 *명시 지정만* 지원.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentScan {
    String[] value() default {};
    String[] basePackages() default {};
}
```

- [x] **Step 4: `ConfigurationClassPostProcessor`에 `processComponentScans` 추가**

`postProcessBeanFactory` 메서드를 다음으로 갱신 (① 위치에 ComponentScan 처리 추가):

```java
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // ① @ComponentScan 처리 — 신규 BD 추가 가능
        processComponentScans(bf);

        // ② @Bean 메서드 → factoryMethod BD 등록 (기존)
        registerBeanMethodsForAllConfigurations(bf);

        // ③ enhance 적용 (Task C1에서 추가)
        enhanceConfigurationClasses(bf);
    }
```

`processComponentScans` 메서드 추가:

```java
    private void processComponentScans(ConfigurableListableBeanFactory bf) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(bf);
        // 스냅샷 — 스캔이 BD를 추가할 수 있으므로 동시 변경 방지
        String[] definitionNames = bf.getBeanDefinitionNames();

        for (String configName : definitionNames) {
            BeanDefinition bd = bf.getBeanDefinition(configName);
            if (bd.getBeanClass() == null) continue;

            ComponentScan scan = bd.getBeanClass().getAnnotation(ComponentScan.class);
            if (scan == null) continue;

            String[] basePackages = mergePackages(scan.basePackages(), scan.value());
            if (basePackages.length == 0) {
                // 학습 범위: 명시 지정만 지원 — 빈 배열은 no-op
                continue;
            }
            scanner.scan(basePackages);
        }
    }

    private String[] mergePackages(String[] basePackages, String[] value) {
        // 두 배열을 합쳐 빈 문자열 제거
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String p : basePackages) if (p != null && !p.isEmpty()) result.add(p);
        for (String p : value) if (p != null && !p.isEmpty()) result.add(p);
        return result.toArray(new String[0]);
    }
```

import 추가:

```java
import com.choisk.sfs.context.annotation.ComponentScan;
```

> **시연 요점:**
> - `processComponentScans`가 *최우선* — 스캔이 BD를 추가하면 후속 단계(`registerBeanMethods`/`enhance`)가 그 BD도 처리.
> - `value` + `basePackages` 합집합 처리 (둘 다 사용 시 누락 방지).
> - 빈 배열일 때 *기본 패키지 자동 추론*은 본 phase 범위 외 — 명시적 no-op.

- [x] **Step 5: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-context:test --tests "com.choisk.sfs.context.integration.ComponentScanFromConfigurationTest"
./gradlew :sfs-context:test
```

예상: `ComponentScanFromConfigurationTest` 2 PASS 신규. sfs-context 전체 PASS.

- [x] **Step 6: 커밋**

```bash
git add sfs-context/src/main/java/com/choisk/sfs/context/annotation/ComponentScan.java \
        sfs-context/src/main/java/com/choisk/sfs/context/support/ConfigurationClassPostProcessor.java \
        sfs-context/src/test/java/com/choisk/sfs/context/integration/ComponentScanFromConfigurationTest.java
git commit -m "feat(sfs-context): @ComponentScan 애노테이션 + ConfigurationClassPostProcessor.processComponentScans (value/basePackages 동의어 + 합집합 처리)"
```

> **실행 기록 (2026-04-26):**
> - `componentScanValueAliasIsHonored` 테스트에서 Plan 원안의 메서드 내 로컬 클래스(`class ValueAliasConfig {}`)를 사용하면 byte-buddy가 서브클래스 기본 생성자를 찾지 못해 `NoSuchMethodException` 발생. 로컬 non-static 클래스는 외부 클래스 인스턴스 참조 생성자만 보유하기 때문.
> - 해결: `componentScanValueAliasIsHonored` 테스트의 `ValueAliasConfig`를 테스트 클래스 최상단의 `static class`로 선언하는 방식으로 변경. 의도(value() alias 검증)는 동일하게 유지.
> - sfs-context 최종 테스트: 53 → 55 PASS (+2 신규), FAIL 0.

---

## 섹션 D: `sfs-samples` 갱신 (Task D1, D2, D3)

### Task D1: `UserService` IdGenerator/Clock 통일 (Phase 1C 박제 회수)

> **TDD 적용 여부:** 제외 — 회귀 안전망 (`UserServiceTest`의 시드 동작이 그대로 PASS해야 함). `IdGenerator.nowInstant()`는 이미 단위 테스트 존재 (`IdGeneratorTest.nowInstantDelegatesToInjectedClock`).
> **Dispatch:** spec only

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java`

- [x] **Step 1: `UserService.java` 갱신**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java
package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.util.Optional;

@Service
public class UserService {
    @Autowired
    UserRepository userRepo;

    @Autowired
    IdGenerator idGen;

    @PostConstruct
    void seedDefaultUser() {
        userRepo.save("기본 사용자", idGen.nowInstant());
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) {
        return userRepo.save(name, idGen.nowInstant());
    }

    public Optional<User> find(Long id) {
        return userRepo.findById(id);
    }

    public int total() {
        return userRepo.count();
    }
}
```

> **변경 요지:**
> - import: `java.time.Clock` 제거, `IdGenerator` 추가
> - 필드: `@Autowired Clock clock` 제거, `@Autowired IdGenerator idGen` 추가
> - `clock.instant()` → `idGen.nowInstant()` (2곳)
> - `AppConfig`의 `@Bean Clock systemClock()`은 그대로 유지 — IdGenerator의 매개변수 자동 주입을 위한 *유일한 시연 빈* (Phase 1B-β C1 학습 가치 보존)

- [x] **Step 2: 컴파일 + 회귀 검증**

```bash
./gradlew :sfs-samples:compileJava :sfs-samples:test
```

예상: `UserServiceTest.postConstructSeedsDefaultUser` 그대로 PASS — 시드된 사용자의 `name`만 검증하므로 시간 출처가 IdGenerator로 바뀌어도 통과. `IdGeneratorTest.nowInstantDelegatesToInjectedClock` 그대로 PASS — `nowInstant()` 동작 자체는 변경 없음.

- [x] **Step 3: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java
git commit -m "refactor(sfs-samples): UserService IdGenerator/Clock 통일 — @Autowired Clock 제거, idGen.nowInstant() 사용 (Phase 1C 박제 dead API 회수)"
```

---

### Task D2: `EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo` rename + 어서션 갱신 (마일스톤 박제 갱신)

> **TDD 적용 여부:** 제외 — rename + 어서션 갱신. C1의 enhance 동작이 *데모 application*에서도 살아 움직이는지를 통합 테스트로 박제.
> **Dispatch:** spec only

**Files:**
- Rename: `sfs-samples/.../todo/EnhanceAbsenceDemo.java` → `ConfigurationEnhanceDemo.java`
- Rename: `sfs-samples/.../todo/EnhanceAbsenceDemoTest.java` → `ConfigurationEnhanceDemoTest.java`

- [x] **Step 1: `EnhanceAbsenceDemo.java` rename + 본문 출력문구 갱신**

기존 파일 삭제 + 새 파일 생성:

```bash
git mv sfs-samples/src/main/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemo.java \
       sfs-samples/src/main/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemo.java
```

새 파일 내용 (클래스명 + 출력문구 갱신):

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemo.java
package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;

public class ConfigurationEnhanceDemo {

    public static class User {
        public final long id;
        public User(long id) { this.id = id; }
    }

    public static class Account {
        public final User user;
        public Account(User user) { this.user = user; }
    }

    @Configuration
    public static class ArgFormConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account(User user) { return new Account(user); }
    }

    @Configuration
    public static class DirectCallConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account() { return new Account(user()); }
    }

    public static void main(String[] args) {
        try (var argCtx = new AnnotationConfigApplicationContext(ArgFormConfig.class)) {
            boolean same = argCtx.getBean(Account.class).user == argCtx.getBean(User.class);
            System.out.println("Arg form (매개변수 라우팅): account.user == ctx.user → " + same);
        }
        try (var directCtx = new AnnotationConfigApplicationContext(DirectCallConfig.class)) {
            boolean same = directCtx.getBean(Account.class).user == directCtx.getBean(User.class);
            System.out.println("Direct call (본문 호출, enhance 적용): account.user == ctx.user → " + same);
        }
    }
}
```

> **변경 요지:**
> - 클래스명: `EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo`
> - 두 번째 출력문구: `"본문 호출, enhance 부재"` → `"본문 호출, enhance 적용"`
> - 코드 본문은 *동일* — enhance 메커니즘이 자동으로 두 번째 줄 결과를 `false → true`로 변형 (Phase 2A C1의 효과)

- [x] **Step 2: `EnhanceAbsenceDemoTest.java` rename + 어서션 갱신**

```bash
git mv sfs-samples/src/test/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemoTest.java \
       sfs-samples/src/test/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemoTest.java
```

새 테스트 내용:

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemoTest.java
package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationEnhanceDemoTest {

    @Test
    void configurationEnhanceMakesBothFormsConsistent() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            ConfigurationEnhanceDemo.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .as("매개변수 라우팅 형태는 컨테이너 싱글톤이 라우팅되어 동일")
                .contains("Arg form (매개변수 라우팅): account.user == ctx.user → true");
        assertThat(output)
                .as("Phase 2A enhance 적용으로 직접 호출 형태도 컨테이너 라우팅됨 (Phase 1C 박제 false → true 회수)")
                .contains("Direct call (본문 호출, enhance 적용): account.user == ctx.user → true");
    }
}
```

> **변경 요지:**
> - 테스트 이름: `enhanceAbsenceShowsBothBranches` → `configurationEnhanceMakesBothFormsConsistent`
> - 두 번째 어서션의 기대 문자열 `→ false` → `→ true`
> - Javadoc 메시지 갱신

- [x] **Step 3: 컴파일 + 회귀 검증**

```bash
./gradlew :sfs-samples:compileJava :sfs-samples:test
```

예상: `ConfigurationEnhanceDemoTest` 1 PASS. sfs-samples 전체 PASS (8건 유지 — rename으로 카운트 변동 없음).

- [x] **Step 4: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemo.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/ConfigurationEnhanceDemoTest.java
git commit -m "refactor(sfs-samples): EnhanceAbsenceDemo → ConfigurationEnhanceDemo rename + 어서션 갱신 — Phase 1C 박제 마일스톤(false→true)이 살아 움직였음"
```

---

### Task D3: `TodoDemoApplication` `@ComponentScan` 축약 + `AppConfig` `@ComponentScan` 추가

> **TDD 적용 여부:** 제외 — 통합 테스트(`TodoDemoApplicationTest`)의 8 라인 출력 시퀀스가 그대로 PASS해야 함 (스캔 결과 동일).
> **Dispatch:** spec only

**Files:**
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java`
- Modify: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java`

- [ ] **Step 1: `AppConfig.java`에 `@ComponentScan` 추가**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java
package com.choisk.sfs.samples.todo.config;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.time.Clock;

@Configuration
@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")
public class AppConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new IdGenerator(clock);
    }
}
```

> **시연 요점:** `@ComponentScan`의 패키지가 `AppConfig`의 패키지 *상위*. ClassPathBeanDefinitionScanner가 sub-package(`controller`/`service`/`repository`/`support`/`domain`)까지 모두 탐색.

- [ ] **Step 2: `TodoDemoApplication.java` 축약**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java
package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.controller.TodoController;
import com.choisk.sfs.samples.todo.controller.UserController;
import com.choisk.sfs.samples.todo.domain.User;

public class TodoDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {
            UserController userController = ctx.getBean(UserController.class);
            TodoController todoController = ctx.getBean(TodoController.class);

            User alice = userController.create("Alice");
            todoController.create(alice.id, "장보기");
            todoController.create(alice.id, "운동");
            todoController.list(alice.id);
            todoController.complete(1L);
            todoController.list(alice.id);
        }
    }
}
```

> **변경 요지:**
> - import: `UserRepository`/`TodoRepository`/`UserService`/`TodoService` 제거 (스캔으로 등록되므로 명시 import 불필요)
> - `AnnotationConfigApplicationContext(...)` 가변 인자: 7개 → 1개 (`AppConfig.class`)
> - 시연 가치: 학습자가 `@SpringBootApplication` 패턴(= `@Configuration + @ComponentScan`)에 익숙해지는 다리 역할

- [ ] **Step 3: 통합 테스트 회귀 검증**

```bash
./gradlew :sfs-samples:test
```

예상: `TodoDemoApplicationTest.demoSequenceProducesExpectedOutput` 그대로 PASS — 출력 시퀀스 8 라인은 *컨테이너 동작*에 의존하므로 등록 방식이 명시 → ComponentScan으로 바뀌어도 동일. sfs-samples 8 PASS 유지.

- [ ] **Step 4: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java
git commit -m "refactor(sfs-samples): TodoDemoApplication @ComponentScan 축약 — 7 클래스 명시 등록 → AppConfig.class 한 줄 + @ComponentScan(basePackages=\"...\")"
```

---

## 섹션 E: 마감 (Task E1)

### Task E1: `sfs-samples/README.md` 갱신 + DoD 체크 + 마감 게이트

> **TDD 적용 여부:** 제외 — 마감 + 문서.
> **Dispatch:** spec only (마감 게이트 3단계는 별도 의식 — `Plan 2A 완료 후 다음 단계` 참조)

**Files:**
- Modify: `sfs-samples/README.md`
- Modify: `docs/superpowers/plans/2026-04-26-phase-2a-byte-buddy-cleanup.md` (DoD 18항목 모두 `[x]` + 실행 기록 블록)

- [ ] **Step 1: 전체 회귀 + 빌드 검증**

```bash
./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test :sfs-samples:test
./gradlew build
```

예상:
- sfs-core: 28 PASS (변동 없음)
- sfs-beans: 58 PASS (변동 없음)
- sfs-context: 49 PASS (44 → +5: B1 +3, B2 +2, C2 +2 — 단 C1은 어서션 갱신만이라 0; 정확 카운트는 실측)
- sfs-samples: 8 PASS (변동 없음 — D1/D2/D3 모두 어서션 갱신/rename/축약, 카운트 무영향)
- 총합: **~143 PASS / 0 FAIL** (138 → +5) / BUILD SUCCESSFUL

> **메모:** 실측 시 변동 가능 — Spec 8.5에선 +3~4 보수 추정, 본 plan은 +5 (B1 3, B2 2, C2 2). 차이가 있으면 실행 기록 블록에 정정.

- [ ] **Step 2: `sfs-samples/README.md` 갱신**

기존 README의 패키지 구조 표 + Phase 1 기능 매핑 표를 그대로 유지하되, 다음 두 섹션을 추가:

````markdown
## Phase 2A 갱신 사항

본 모듈은 Phase 2A에서 다음과 같이 변경:

- **`@ComponentScan` 적용** — `AppConfig`에 `@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")` 추가. `TodoDemoApplication`의 7 클래스 명시 등록이 한 줄 진입점으로 축약됨.
- **IdGenerator/Clock 통일** — `UserService`가 `Clock`을 직접 주입받지 않고 `IdGenerator.nowInstant()`로 시간을 얻음. `AppConfig`의 `@Bean Clock systemClock()`은 *IdGenerator의 매개변수 자동 주입* 시연 빈으로 그대로 유지.
- **`EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo` rename** — Phase 1C에서 박제한 `→ false`가 Phase 2A enhance 도입과 동시에 `→ true`로 변형. 살아있는 마일스톤.

## 시연 마일스톤

| 시점 | `account.user == ctx.user` (직접 호출 형태) |
|---|---|
| Phase 1C 끝 | **false** (enhance 미구현 — Phase 1C 박제) |
| Phase 2A 끝 | **true** (byte-buddy enhance — Phase 2A 회수) |

`ConfigurationEnhanceDemoTest`가 위 변형을 자동 검증.
````

기존 패키지 구조 다이어그램의 파일명도 갱신:

```diff
 com.choisk.sfs.samples.todo/
 ├── TodoDemoApplication.java          # main #1
-├── EnhanceAbsenceDemo.java           # main #2
+├── ConfigurationEnhanceDemo.java     # main #2
 ├── config/AppConfig.java
```

- [ ] **Step 3: 본 plan DoD 18항목 모두 `[x]` + 실행 기록 블록 추가**

`docs/superpowers/plans/2026-04-26-phase-2a-byte-buddy-cleanup.md` 하단 DoD 섹션의 18항목을 모두 `[x]`로 갱신. 그리고 마지막에:

```markdown
> **실행 기록 (YYYY-MM-DD):**
>
> - **회귀:** sfs-core 28 + sfs-beans 58 + sfs-context <실측> + sfs-samples 8 = **총 <실측> PASS / 0 FAIL**
> - **빌드:** `./gradlew build` → BUILD SUCCESSFUL
> - **추가 커밋:** A1 + B1 + B2 + C1 + C2 + D1 + D2 + D3 + E1 = 9 커밋
> - **Phase 2A 종료** — 마감 게이트 진입 준비 완료
```

- [ ] **Step 4: 최종 커밋**

```bash
git add sfs-samples/README.md docs/superpowers/plans/2026-04-26-phase-2a-byte-buddy-cleanup.md
git commit -m "docs: Plan 2A 마감 — sfs-samples README Phase 2A 갱신 사항 추가 + DoD 18항목 [x] + Phase 2A 종료"
```

---

## 🎯 Plan 2A Definition of Done — 최종 체크리스트 (18항목)

**다운캐스팅 정리:**

- [ ] 1. `ConfigurableBeanFactory`에 `registerDisposableBean` + `containsSingleton` 시그니처 추가 (Task A1)
- [ ] 2. `ConfigurableListableBeanFactory`에 `resolveDependency` 시그니처 추가 (Task A1)
- [ ] 3. `DefaultListableBeanFactory.resolveDependency`에 `@Override` 적용 (Task A1)
- [ ] 4. `AutowiredAnnotationBeanPostProcessor` 생성자 파라미터 → `ConfigurableListableBeanFactory` (Task A1)
- [ ] 5. `CommonAnnotationBeanPostProcessor` 생성자 파라미터 → `ConfigurableBeanFactory` (Task A1)
- [ ] 6. `AnnotationConfigUtils` 다운캐스팅 블록 제거 (Task A1)

**byte-buddy 인프라:**

- [ ] 7. `BeanMethodInterceptor` 신설 + 단위 테스트 3건 (Task B1)
- [ ] 8. `ConfigurationClassEnhancer` 신설 + 단위 테스트 2건 (Task B2)

**`ConfigurationClassPostProcessor` 확장:**

- [x] 9. `enhanceConfigurationClasses()` 추가 — `proxyBeanMethods=true`인 `@Configuration` BD의 beanClass를 enhance 클래스로 교체 (Task C1)
- [x] 10. `Phase1IntegrationTest.directCallFormCreatesDistinctInstanceWithoutEnhance` 메서드명 + 어서션 + Javadoc 갱신 (Task C1)
- [x] 11. `@ComponentScan` 애노테이션 신설 (`value`/`basePackages` 동의어) (Task C2)
- [x] 12. `processComponentScans()` 추가 — `@Configuration` 클래스의 `@ComponentScan` 발견 시 `ClassPathBeanDefinitionScanner.scan` 호출 (Task C2)
- [x] 13. `ComponentScanFromConfigurationTest` 통합 테스트 2건 신설 (Task C2)

**`sfs-samples` 갱신:**

- [x] 14. `UserService` IdGenerator/Clock 통일 — `@Autowired Clock` 제거, `idGen.nowInstant()` 사용 (Task D1)
- [x] 15. `EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo` rename + 출력문구 갱신 + 테스트 어서션 갱신 (Task D2)
- [ ] 16. `TodoDemoApplication` 7 클래스 명시 등록 → `AppConfig.class` + `@ComponentScan` 축약 (Task D3)

**품질:**

- [ ] 17. `./gradlew build` 전체 PASS + 누적 ~141~143 PASS / 0 FAIL (Task E1)
- [ ] 18. `sfs-samples/README.md` Phase 2A 갱신 사항 + 마일스톤 표 추가 (Task E1)

---

## ▶ Plan 2A 완료 후 다음 단계

1. **CLAUDE.md "완료 후 품질 게이트" 3단계** — 다관점 코드리뷰 → 리팩토링 → `/simplify` 패스. Q5(A')에 따른 task별 dispatch는 *task 단위 quality review*만 담당하고, 본 마감 게이트는 *Phase 2A 변경 전체*를 다관점에서 검토.
2. **main 머지** — `feat/phase2a-byte-buddy` → main `--no-ff` 또는 GitHub PR (Phase 1C와 동일한 패턴).
3. **Phase 2B (AOP) brainstorming** — `sfs-aop` 모듈 신설 + `@Aspect`/`@Pointcut`/`@Around` + logging advice 시연 (Q4=A 결정 적용). byte-buddy 인프라가 본 phase에서 깔렸으므로 *advice 체인 도입*에 집중 가능.

---

## 11. Self-Review 체크리스트 (plan 작성자 자기검토)

**1. Spec coverage:** spec § 9 DoD 18항목 모두 task에 매핑됨 — § 9-1 → A1 step 1~3, § 9-2 → B2, § 9-3 → B1, § 9-4 → C1+C2, § 9-5 → C1 step 1, § 9-6 → C2, § 9-7 → A1 step 2, § 9-8 → A1 step 4~5, § 9-9 → A1 step 6, § 9-10 → D1, § 9-11 → D2, § 9-12 → D2, § 9-13 → D3, § 9-14 → D3, § 9-15 → E1, § 9-16 → 모든 task 커밋, § 9-17 → E1, § 9-18 → E1.

**2. 회귀 카운트 보정:** spec § 8.5는 +3~4를 추정했으나 본 plan은 B1(3) + B2(2) + C2(2) = +7로 계산됨. 단 *C1은 어서션 갱신만이라 +0*, D1/D2/D3는 +0. 즉 138 → 145일 가능성 — 실행 기록에서 정확 카운트 박제.

**3. Type consistency 확인:** `BeanMethodInterceptor(ConfigurableBeanFactory)`, `ConfigurationClassEnhancer(ConfigurableBeanFactory)`. CCPostProcessor는 `ConfigurableListableBeanFactory bf`를 받고 그걸 그대로 enhancer에 전달 (`new ConfigurationClassEnhancer(bf)` — `ConfigurableListableBeanFactory extends ConfigurableBeanFactory`이므로 자동 만족). ✅

**4. 누락 없는지 재확인:** 단위 테스트(B1/B2)와 통합 테스트(C1/C2) 분리 확실. rename(D2)은 git mv로 history 보존. README 갱신(E1) 빠뜨리지 않음.
