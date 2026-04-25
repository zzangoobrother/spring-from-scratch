# Phase 1C — sfs-samples 데모 application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 1 (IoC) 학습 산출물을 *실행 가능한 데모 application*으로 시연한다. `main()` 메서드 + 콘솔 출력으로 학습자가 컨테이너 동작을 직접 눈으로 확인하게 하고, `EnhanceAbsenceDemo`로 enhance 부재의 효과를 별도 박제한다.

**Architecture:** `sfs-samples` 신규 모듈에 User+Todo 도메인 미니멀 시나리오. 3 layer (Repository/Service/Controller) + `@Configuration` + 학습용 `IdGenerator`. `TodoDemoApplication.main()`은 도메인 시퀀스 8단계, `EnhanceAbsenceDemo.main()`은 inter-bean reference 두 형태 비교. 출력은 JUnit 통합 테스트 2건으로 회귀 보장.

**Tech Stack:** Java 25 LTS, Gradle 9.4.1 (Kotlin DSL), JUnit 5 + AssertJ. `sfs-context` 의존만.

**Selected Spec:** `docs/superpowers/specs/2026-04-25-phase1c-samples-demo-design.md`

**선행 조건:** Phase 1B-β 완료 (main 머지 `e325a0c`, 130 PASS). 브랜치: `feat/phase1c-samples` (이미 생성됨, main 베이스).

**확인된 인프라 (plan 작성 시점 grep):**
- `settings.gradle.kts`에 `// sfs-samples는 Plan 1C에서 추가` 주석이 이미 자리 잡힘 — 그 위치에 `"sfs-samples"` 추가
- `ConfigurableApplicationContext extends AutoCloseable` 확인됨 → try-with-resources 사용 가능, AutoCloseable 보강 task 불필요
- `AnnotationConfigApplicationContext(Class<?>... componentClasses)` 가변 인자 생성자 존재
- `sfs-context/build.gradle.kts`는 `api(project(":sfs-beans"))` 패턴 사용 — sfs-samples도 동일 패턴 적용

**End state:** 다음 두 main이 실행 가능하고 통합 테스트가 출력을 박제:

```bash
./gradlew :sfs-samples:run -PmainClass=com.choisk.sfs.samples.todo.TodoDemoApplication
# 또는 IDE에서 TodoDemoApplication.main() 실행

# 기대 콘솔 출력 (8 라인):
[UserService] @PostConstruct: 기본 사용자 시드 완료
[UserController] User created: Alice (id=2)
[TodoController] Todo created: id=1 owner=2 "장보기"
[TodoController] Todo created: id=2 owner=2 "운동"
[TodoController] Todos for owner 2: [id=1] 장보기 [TODO], [id=2] 운동 [TODO]
[TodoController] Todo 1 completed
[TodoController] Todos for owner 2: [id=1] 장보기 [DONE], [id=2] 운동 [TODO]
[UserController] @PreDestroy: 2명 사용자 등록 상태로 종료
```

```bash
# EnhanceAbsenceDemo.main() 실행 시 기대 출력:
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 부재): account.user == ctx.user → false
```

---

## 섹션 구조 (Task 한 줄 요약)

| 섹션 | 범위 | Task | TDD |
|---|---|---|---|
| **A** | 모듈 신설 (settings + build.gradle.kts) + 도메인 POJO 2종 | A1 | 제외 |
| **B** | `IdGenerator` (학습용 ID 발급 유틸) | B1 | 적용 |
| **C** | `AppConfig` (`@Configuration` + `@Bean` 2개) + Repository 2종 (Map 래퍼) | C1 | 제외 |
| **D** | `UserService` (`@PostConstruct` 포함) + `TodoService` (다중 의존) | D1, D2 | 모두 적용 |
| **E** | Controller 2종 (`UserController`에 `@PreDestroy` 포함) | E1 | 제외 (thin layer + 통합 테스트로 검증) |
| **F** | `TodoDemoApplication.main()` + `TodoDemoApplicationTest` | F1 | 적용 (통합) |
| **G** | `EnhanceAbsenceDemo.main()` + `EnhanceAbsenceDemoTest` | G1 | 적용 (통합) |
| **H** | 마감 (README + DoD + 전체 회귀) | H1 | 제외 |

총 **8 Task**. 누적 테스트 카운트 예상: 130 → **~135** (B1 +2, D1 +1, D2 +1, F1 +1, G1 +1, 그 외 통합 테스트로 간접 검증).

**의존 흐름:** `A1` (모듈/도메인) → `B1` (IdGenerator) → `C1` (AppConfig + Repository, B1 의존) → `D1` (UserService) → `D2` (TodoService) → `E1` (Controller, D1/D2 의존) → `F1` (통합 시연) → `G1` (enhance 부재 시연, 독립) → `H1` (마감).

---

## 섹션 A: 모듈 신설 + 도메인 POJO (Task A1)

### Task A1: `sfs-samples` 모듈 신설 + `User`/`Todo` POJO

> **TDD 적용 여부:** 제외 — 빌드 설정 + 데이터 컨테이너만. 컴파일 + 회귀 테스트로 검증.

**Files:**
- Modify: `settings.gradle.kts`
- Create: `sfs-samples/build.gradle.kts`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/User.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/Todo.java`

- [x] **Step 1: `settings.gradle.kts` 수정**

기존 `// sfs-samples는 Plan 1C에서 추가` 주석을 *대체*하여 `"sfs-samples"`를 include 블록에 추가:

```kotlin
// settings.gradle.kts
rootProject.name = "spring-from-scratch"

include(
    "sfs-core",
    "sfs-beans",
    "sfs-context",
    "sfs-samples",
)
```

- [x] **Step 2: `sfs-samples/build.gradle.kts` 작성**

```kotlin
// sfs-samples/build.gradle.kts
plugins {
    `java-library`
}

dependencies {
    implementation(project(":sfs-context"))
}
```

> **메모:** sfs-context의 `api(project(":sfs-beans"))` 덕분에 sfs-samples는 sfs-context만 의존해도 sfs-beans/sfs-core가 transitively 노출됨. 단 *직접* import는 sfs-context 패키지에 한정한다 (역방향 의존 차단).

- [x] **Step 3: `User.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/User.java
package com.choisk.sfs.samples.todo.domain;

import java.time.Instant;

public class User {
    public final Long id;
    public final String name;
    public final Instant createdAt;

    public User(Long id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }
}
```

- [x] **Step 4: `Todo.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/Todo.java
package com.choisk.sfs.samples.todo.domain;

public class Todo {
    public enum Status { TODO, DONE }

    public final Long id;
    public final Long ownerId;
    public final String title;
    public Status status;

    public Todo(Long id, Long ownerId, String title) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = title;
        this.status = Status.TODO;
    }
}
```

- [x] **Step 5: 컴파일 검증**

```bash
./gradlew :sfs-samples:compileJava
./gradlew build
```

예상: BUILD SUCCESSFUL. 모듈이 인식되고 도메인 클래스가 컴파일됨. 기존 130 PASS 유지.

- [x] **Step 6: 커밋**

```bash
git add settings.gradle.kts sfs-samples/build.gradle.kts \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/User.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/domain/Todo.java
git commit -m "feat(sfs-samples): 모듈 신설 + User/Todo 도메인 POJO"
```

---

## 섹션 B: `IdGenerator` (Task B1)

### Task B1: `IdGenerator` 학습용 ID 발급 유틸

> **TDD 적용 여부:** 적용 — `next()`의 atomic increment + `nowInstant()` 위임이 본질 동작.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/support/IdGenerator.java`
- Test: `sfs-samples/src/test/java/com/choisk/sfs/samples/todo/support/IdGeneratorTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/support/IdGeneratorTest.java
package com.choisk.sfs.samples.todo.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void nextProducesIncreasingIdsStartingFromOne() {
        IdGenerator gen = new IdGenerator(Clock.systemDefaultZone());
        assertThat(gen.next()).isEqualTo(1L);
        assertThat(gen.next()).isEqualTo(2L);
        assertThat(gen.next()).isEqualTo(3L);
    }

    @Test
    void nowInstantDelegatesToInjectedClock() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        Clock fixedClock = Clock.fixed(fixed, ZoneId.of("UTC"));
        IdGenerator gen = new IdGenerator(fixedClock);
        assertThat(gen.nowInstant()).isEqualTo(fixed);
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — 클래스 미존재)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.support.IdGeneratorTest"
```

- [x] **Step 3: `IdGenerator.java` 구현**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/support/IdGenerator.java
package com.choisk.sfs.samples.todo.support;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong(0);

    public IdGenerator(Clock clock) {
        this.clock = clock;
    }

    public long next() {
        return seq.incrementAndGet();
    }

    public Instant nowInstant() {
        return clock.instant();
    }
}
```

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.support.IdGeneratorTest"
```

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/support/IdGenerator.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/support/IdGeneratorTest.java
git commit -m "feat(sfs-samples): IdGenerator — Clock 의존 ID 발급 유틸 (학습용 단순판)"
```

---

## 섹션 C: `AppConfig` + Repository 2종 (Task C1)

### Task C1: `AppConfig` + `UserRepository` + `TodoRepository`

> **TDD 적용 여부:** 제외 — `AppConfig`는 메타정보, Repository 2종은 단순 `Map` 래퍼. 동작은 후속 통합 테스트(F1)로 검증.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/UserRepository.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/TodoRepository.java`

- [x] **Step 1: `AppConfig.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java
package com.choisk.sfs.samples.todo.config;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.time.Clock;

@Configuration
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

> **시연 요점:** `systemClock()`은 *no-arg* `@Bean` (가장 단순한 형태), `idGenerator(Clock clock)`은 *매개변수 자동 주입*을 받는 `@Bean` (Phase 1B-β Task C1의 핵심 시연). 컨테이너가 `Clock` 빈을 찾아 인자로 채워준다.

- [x] **Step 2: `UserRepository.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/UserRepository.java
package com.choisk.sfs.samples.todo.repository;

import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.todo.domain.User;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserRepository {
    private final ConcurrentHashMap<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    public User save(String name, Instant now) {
        long id = seq.incrementAndGet();
        User u = new User(id, name, now);
        store.put(id, u);
        return u;
    }

    public Optional<User> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public int count() {
        return store.size();
    }
}
```

> **시연 요점:** `@Repository`는 1A에서 정의된 메타애노테이션 (`@Component` 메타). 컴포넌트 스캐너가 `@Component` 보유로 인식한다. UserRepository는 *자체* `AtomicLong`으로 ID 발급 (TodoRepository와 다른 패턴 — 두 가지 ID 발급 패턴을 학습용으로 보여줌).

- [x] **Step 3: `TodoRepository.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/TodoRepository.java
package com.choisk.sfs.samples.todo.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class TodoRepository {
    @Autowired
    IdGenerator idGen;

    private final ConcurrentHashMap<Long, Todo> store = new ConcurrentHashMap<>();

    public Todo save(Long ownerId, String title) {
        Todo t = new Todo(idGen.next(), ownerId, title);
        store.put(t.id, t);
        return t;
    }

    public Optional<Todo> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Todo> findByOwnerId(Long ownerId) {
        return store.values().stream()
                .filter(t -> t.ownerId.equals(ownerId))
                .sorted((a, b) -> Long.compare(a.id, b.id))
                .collect(Collectors.toList());
    }
}
```

> **시연 요점:** `@Autowired IdGenerator idGen` 필드 주입 (F1 시연). `findByOwnerId`가 id 순서로 정렬 — 통합 테스트 출력에서 `[id=1] 장보기, [id=2] 운동` 순서를 보장.

- [x] **Step 4: 컴파일 + 회귀 검증**

```bash
./gradlew :sfs-samples:compileJava :sfs-samples:test
./gradlew build
```

예상: BUILD SUCCESSFUL. IdGeneratorTest 2 PASS 유지. 회귀 변동 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/UserRepository.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/TodoRepository.java
git commit -m "feat(sfs-samples): AppConfig (@Bean 2종) + UserRepository + TodoRepository"
```

---

## 섹션 D: Service 2종 (Task D1, D2)

### Task D1: `UserService` + `@PostConstruct` 시드

> **TDD 적용 여부:** 적용 — `@PostConstruct`로 인한 *컨테이너 시점* 시드 동작 + `register`/`find`/`total` 분기.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java`
- Test: `sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/UserServiceTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/UserServiceTest.java
package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    @Test
    void postConstructSeedsDefaultUser() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, UserService.class)) {

            UserService userService = ctx.getBean(UserService.class);
            assertThat(userService.total())
                    .as("@PostConstruct가 시드 사용자 1명을 생성해야 함")
                    .isEqualTo(1);

            User seeded = userService.find(1L).orElseThrow();
            assertThat(seeded.name).isEqualTo("기본 사용자");
        }
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — UserService 미존재)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.service.UserServiceTest"
```

- [x] **Step 3: `UserService.java` 구현**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java
package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;

import java.time.Clock;
import java.util.Optional;

@Service
public class UserService {
    @Autowired
    UserRepository userRepo;

    @Autowired
    Clock clock;

    @PostConstruct
    void seedDefaultUser() {
        userRepo.save("기본 사용자", clock.instant());
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) {
        return userRepo.save(name, clock.instant());
    }

    public Optional<User> find(Long id) {
        return userRepo.findById(id);
    }

    public int total() {
        return userRepo.count();
    }
}
```

> **시연 요점:** `@Autowired Clock` 필드 주입 — `Clock`은 도메인이 아니라 `@Bean`으로 등록된 외부 객체. 처리기는 BeanDefinition 출처를 묻지 않고 *타입 매칭만* 한다. `@PostConstruct seedDefaultUser`는 컨테이너 라이프사이클의 일부이므로 `main()`의 첫 호출보다 *먼저* 실행 (G1의 BPP:before 시점).

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-samples:test
```

예상: IdGeneratorTest 2 + UserServiceTest 1 = 3 PASS. 회귀 변동 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/UserService.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/UserServiceTest.java
git commit -m "feat(sfs-samples): UserService — @Autowired UserRepository/Clock + @PostConstruct 시드"
```

---

### Task D2: `TodoService` 다중 의존 + 분기

> **TDD 적용 여부:** 적용 — `create`의 `orElseThrow` 분기, `complete`의 상태 변경 동작.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/TodoService.java`
- Test: `sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/TodoServiceTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/TodoServiceTest.java
package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.repository.TodoRepository;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoServiceTest {

    @Test
    void createRejectsUnknownOwner() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class)) {

            TodoService todoService = ctx.getBean(TodoService.class);
            assertThatThrownBy(() -> todoService.create(999L, "ghost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown user id=999");
        }
    }

    @Test
    void completeFlipsStatusToDone() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class)) {

            TodoService todoService = ctx.getBean(TodoService.class);
            // 시드 사용자 id=1을 활용
            Todo created = todoService.create(1L, "테스트");
            assertThat(created.status).isEqualTo(Todo.Status.TODO);

            todoService.complete(created.id);
            assertThat(created.status).isEqualTo(Todo.Status.DONE);
        }
    }
}
```

- [x] **Step 2: 테스트 실행 (FAIL — TodoService 미존재)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.service.TodoServiceTest"
```

- [x] **Step 3: `TodoService.java` 구현**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/TodoService.java
package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.repository.TodoRepository;
import com.choisk.sfs.samples.todo.repository.UserRepository;

import java.util.List;

@Service
public class TodoService {
    @Autowired
    TodoRepository todoRepo;

    @Autowired
    UserRepository userRepo;

    public Todo create(Long ownerId, String title) {
        userRepo.findById(ownerId).orElseThrow(() ->
                new IllegalArgumentException("Unknown user id=" + ownerId));
        return todoRepo.save(ownerId, title);
    }

    public List<Todo> listFor(Long ownerId) {
        return todoRepo.findByOwnerId(ownerId);
    }

    public Todo complete(Long todoId) {
        Todo t = todoRepo.findById(todoId).orElseThrow(() ->
                new IllegalArgumentException("Unknown todo id=" + todoId));
        t.status = Todo.Status.DONE;
        return t;
    }
}
```

> **시연 요점:** `@Autowired TodoRepository`, `@Autowired UserRepository` — 같은 빈에 *다중 의존* 주입. 한 빈이 둘 이상의 의존을 받는 케이스가 단위 테스트와 결합되어 자연스럽게 시연됨.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-samples:test
```

예상: IdGeneratorTest 2 + UserServiceTest 1 + TodoServiceTest 2 = 5 PASS. 회귀 변동 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/service/TodoService.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/service/TodoServiceTest.java
git commit -m "feat(sfs-samples): TodoService — @Autowired TodoRepository/UserRepository + create 분기 + complete 상태 변경"
```

---

## 섹션 E: Controller 2종 (Task E1)

### Task E1: `UserController` (+ `@PreDestroy`) + `TodoController`

> **TDD 적용 여부:** 제외 — Controller는 *thin layer* (Service 위임 + System.out 출력만). 단위 테스트로는 의미 있는 동작 검증이 어렵고, F1 통합 테스트가 콘솔 출력으로 *모든* Controller 동작을 박제하므로 단독 테스트 생략.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/UserController.java`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/TodoController.java`

- [x] **Step 1: `UserController.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/UserController.java
package com.choisk.sfs.samples.todo.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.context.annotation.PreDestroy;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.service.UserService;

@Controller
public class UserController {
    @Autowired
    UserService userService;

    @PreDestroy
    void logShutdown() {
        System.out.println("[UserController] @PreDestroy: " + userService.total() + "명 사용자 등록 상태로 종료");
    }

    public User create(String name) {
        User u = userService.register(name);
        System.out.println("[UserController] User created: " + u.name + " (id=" + u.id + ")");
        return u;
    }
}
```

> **시연 요점:** `@PreDestroy logShutdown` — `ctx.close()` 시 LIFO 순서로 호출 (G2). UserController는 등록 순서상 후반이므로 destroy 시점에 호출됨. `userService.total()` 호출이 안전한 이유: UserService 객체 인스턴스는 destroy 콜백이 끝나도 Java 객체로 살아있음 (컨테이너의 destroy 콜백 호출이 끝났을 뿐).

- [x] **Step 2: `TodoController.java` 작성**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/TodoController.java
package com.choisk.sfs.samples.todo.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.service.TodoService;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class TodoController {
    @Autowired
    TodoService todoService;

    public Todo create(Long ownerId, String title) {
        Todo t = todoService.create(ownerId, title);
        System.out.println("[TodoController] Todo created: id=" + t.id + " owner=" + t.ownerId + " \"" + t.title + "\"");
        return t;
    }

    public void list(Long ownerId) {
        List<Todo> todos = todoService.listFor(ownerId);
        String summary = todos.stream()
                .map(t -> "[id=" + t.id + "] " + t.title + " [" + t.status + "]")
                .collect(Collectors.joining(", "));
        System.out.println("[TodoController] Todos for owner " + ownerId + ": " + summary);
    }

    public void complete(Long todoId) {
        todoService.complete(todoId);
        System.out.println("[TodoController] Todo " + todoId + " completed");
    }
}
```

- [x] **Step 3: 컴파일 + 회귀 검증**

```bash
./gradlew :sfs-samples:compileJava :sfs-samples:test
./gradlew build
```

예상: BUILD SUCCESSFUL. sfs-samples 5 PASS 유지. 회귀 변동 없음.

- [x] **Step 4: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/UserController.java \
        sfs-samples/src/main/java/com/choisk/sfs/samples/todo/controller/TodoController.java
git commit -m "feat(sfs-samples): UserController (+ @PreDestroy) + TodoController — thin layer + System.out 출력"
```

---

## 섹션 F: 통합 시연 — `TodoDemoApplication` (Task F1)

### Task F1: `TodoDemoApplication.main()` + 출력 캡처 통합 테스트

> **TDD 적용 여부:** 적용 (통합) — Phase 1 마감 시연. main 시퀀스의 콘솔 출력 8 라인을 회귀 안전망으로 박제.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java`
- Test: `sfs-samples/src/test/java/com/choisk/sfs/samples/todo/TodoDemoApplicationTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/TodoDemoApplicationTest.java
package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TodoDemoApplicationTest {

    @Test
    void demoSequenceProducesExpectedOutput() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            TodoDemoApplication.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String[] lines = stdout.toString(StandardCharsets.UTF_8).split("\\R");

        assertThat(lines).containsSequence(
                "[UserService] @PostConstruct: 기본 사용자 시드 완료",
                "[UserController] User created: Alice (id=2)",
                "[TodoController] Todo created: id=1 owner=2 \"장보기\"",
                "[TodoController] Todo created: id=2 owner=2 \"운동\"",
                "[TodoController] Todos for owner 2: [id=1] 장보기 [TODO], [id=2] 운동 [TODO]",
                "[TodoController] Todo 1 completed",
                "[TodoController] Todos for owner 2: [id=1] 장보기 [DONE], [id=2] 운동 [TODO]",
                "[UserController] @PreDestroy: 2명 사용자 등록 상태로 종료"
        );
    }
}
```

> **테스트 의도:** `containsSequence`는 *순서대로 포함*하는지 검증. 다른 출력(예: 디버그 로그)이 사이에 끼어 있어도 8 라인이 *순서대로* 등장하면 PASS. 순서 자체가 학습 가치(`@PostConstruct`가 첫 줄, `@PreDestroy`가 마지막 줄)이므로 `containsSequence`가 적합.

- [x] **Step 2: 테스트 실행 (FAIL — TodoDemoApplication 미존재)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.TodoDemoApplicationTest"
```

- [x] **Step 3: `TodoDemoApplication.java` 구현**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java
package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.controller.TodoController;
import com.choisk.sfs.samples.todo.controller.UserController;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.TodoRepository;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import com.choisk.sfs.samples.todo.service.TodoService;
import com.choisk.sfs.samples.todo.service.UserService;

public class TodoDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class,
                UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class,
                UserController.class, TodoController.class)) {

            UserController userController = ctx.getBean(UserController.class);
            TodoController todoController = ctx.getBean(TodoController.class);

            User alice = userController.create("Alice");
            todoController.create(alice.id, "장보기");
            todoController.create(alice.id, "운동");
            todoController.list(alice.id);
            todoController.complete(1L);
            todoController.list(alice.id);
        }
        // try-with-resources가 ctx.close() 호출 → @PreDestroy 트리거
    }
}
```

> **시연 요점:**
> 1. 컨테이너 생성 시점에 모든 빈이 인스턴스화 + `@Autowired` 주입 + `UserService.@PostConstruct` 호출 (BPP:before).
> 2. `userController.create("Alice")` 시점에 *이미 시드된 기본 사용자(id=1)*가 있어 Alice는 `id=2`.
> 3. `todoController.complete(1L)`은 첫 번째 Todo("장보기")의 status를 변경 — 같은 객체 참조라 다음 `list(...)` 출력에 반영.
> 4. try-with-resources 종료 시 `ctx.close()` → LIFO destroy → UserController.@PreDestroy 출력.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-samples:test
./gradlew build
```

예상: sfs-samples 6 PASS (이전 5 + 통합 1). 전체 BUILD SUCCESSFUL. 회귀 변동 없음.

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/TodoDemoApplication.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/TodoDemoApplicationTest.java
git commit -m "feat(sfs-samples): TodoDemoApplication.main() — Phase 1 7 기능 시연 8 라인 출력 + 회귀 안전망"
```

---

## 섹션 G: enhance 부재 시연 — `EnhanceAbsenceDemo` (Task G1)

### Task G1: `EnhanceAbsenceDemo.main()` + 출력 캡처 통합 테스트

> **TDD 적용 여부:** 적용 (통합) — Phase1IntegrationTest의 `directCallFormCreatesDistinctInstanceWithoutEnhance`와 *동일한 박제*를 데모 모듈에서도 콘솔 출력으로 시연.

**Files:**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemo.java`
- Test: `sfs-samples/src/test/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemoTest.java`

- [x] **Step 1: 실패 테스트 작성**

```java
// sfs-samples/src/test/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemoTest.java
package com.choisk.sfs.samples.todo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EnhanceAbsenceDemoTest {

    @Test
    void enhanceAbsenceShowsBothBranches() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            EnhanceAbsenceDemo.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .as("매개변수 라우팅 형태는 컨테이너 싱글톤이 라우팅되어 동일")
                .contains("Arg form (매개변수 라우팅): account.user == ctx.user → true");
        assertThat(output)
                .as("직접 호출 형태는 enhance 부재로 매번 새 인스턴스")
                .contains("Direct call (본문 호출, enhance 부재): account.user == ctx.user → false");
    }
}
```

> **학습 메모:** 두 번째 단언이 *깨지지 않는* 게 본 데모의 핵심 가치. byte-buddy 도입 시점에 이 단언이 깨지면(`true`로 바뀌면) enhance가 들어왔다는 마일스톤 증거.

- [x] **Step 2: 테스트 실행 (FAIL — EnhanceAbsenceDemo 미존재)**

```bash
./gradlew :sfs-samples:test --tests "com.choisk.sfs.samples.todo.EnhanceAbsenceDemoTest"
```

- [x] **Step 3: `EnhanceAbsenceDemo.java` 구현**

```java
// sfs-samples/src/main/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemo.java
package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;

public class EnhanceAbsenceDemo {

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
            System.out.println("Direct call (본문 호출, enhance 부재): account.user == ctx.user → " + same);
        }
    }
}
```

> **시연 요점:**
> - `ArgFormConfig.account(User user)` — 컨테이너가 `resolveDependency`로 `User` 빈을 인자로 채워 호출. 결과적으로 `Account.user`가 컨테이너의 `User` 싱글톤과 *동일* 인스턴스.
> - `DirectCallConfig.account()` — 메서드 본문에서 `user()`를 *직접 호출*. enhance가 없으니 컨테이너 라우팅이 없고 `new User(42)`가 새로 생성됨. 결과적으로 `Account.user`는 컨테이너의 `User` 빈과 *다른* 인스턴스.
> - 도메인 클래스를 데모 외부로 빼지 않고 `static class`로 내부에 둔 이유: enhance 부재 시연은 *기존 도메인(User+Todo)과 무관*한 학습용 단순 예시. 외부 도메인을 끌어오면 학습 시야가 분산됨.

- [x] **Step 4: 테스트 실행 (PASS 확인)**

```bash
./gradlew :sfs-samples:test
./gradlew build
```

예상: sfs-samples 7 PASS (이전 6 + 통합 1). 전체 BUILD SUCCESSFUL.

- [x] **Step 5: 커밋**

```bash
git add sfs-samples/src/main/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemo.java \
        sfs-samples/src/test/java/com/choisk/sfs/samples/todo/EnhanceAbsenceDemoTest.java
git commit -m "feat(sfs-samples): EnhanceAbsenceDemo.main() — inter-bean reference 두 형태 비교 (매개변수 vs 직접 호출)"
```

---

## 섹션 H: 마감 (Task H1)

### Task H1: README + DoD 갱신 + 전체 회귀 + 최종 커밋

> **TDD 적용 여부:** 제외 — 마감 + 문서.

**Files:**
- Create: `sfs-samples/README.md`
- Modify: `docs/superpowers/plans/2026-04-25-phase-1c-samples-demo.md` (DoD 12항목 모두 `[x]` + 실행 기록 블록)

- [x] **Step 1: 전체 회귀 + 빌드 검증**

```bash
./gradlew :sfs-core:test :sfs-beans:test :sfs-context:test :sfs-samples:test
./gradlew build
```

예상:
- sfs-core: 28 PASS (변동 없음)
- sfs-beans: 58 PASS (변동 없음)
- sfs-context: 44 PASS (변동 없음)
- sfs-samples: 7 PASS (B1 +2, D1 +1, D2 +2, F1 +1, G1 +1)
- 총합: **137 PASS / 0 FAIL** (130 → +7) / BUILD SUCCESSFUL

> **메모:** spec 7.3은 ~135를 예상했으나 D2가 2 테스트로 늘어나 ~137. 예상 범위 내.

- [x] **Step 2: `sfs-samples/README.md` 작성**

```markdown
# sfs-samples

Spring From Scratch의 **데모 application 모듈**. Phase 1(IoC) 학습 산출물을 *실행 가능한 코드*로 시연한다.

## 의존 관계

- 의존: `sfs-context` (sfs-beans/sfs-core는 transitive)
- 의존됨: 없음 (그래프의 leaf)

## 진입점

### `TodoDemoApplication.main()` — 도메인 시연

User+Todo CRUD 미니멀 시나리오. Phase 1의 7가지 기능이 한 시퀀스에서 모두 등장.

| 시연 기능 | 위치 |
|---|---|
| `@Configuration` + no-arg `@Bean` | `AppConfig#systemClock()` |
| `@Bean` 매개변수 자동 주입 | `AppConfig#idGenerator(Clock)` |
| `@Component` 4 stereotype (`@Repository`/`@Service`/`@Controller`) | `repository/`, `service/`, `controller/` |
| `@Autowired` 필드 주입 | 7곳 (Repository → Service → Controller 의존 그래프) |
| `@PostConstruct` (BPP:before) | `UserService.seedDefaultUser()` |
| `@PreDestroy` (LIFO destroy) | `UserController.logShutdown()` |
| 컨테이너 자동 등록 | `AnnotationConfigApplicationContext` 생성자 |

**기대 출력 8 라인 (`TodoDemoApplicationTest`로 회귀 박제):**

```
[UserService] @PostConstruct: 기본 사용자 시드 완료
[UserController] User created: Alice (id=2)
[TodoController] Todo created: id=1 owner=2 "장보기"
[TodoController] Todo created: id=2 owner=2 "운동"
[TodoController] Todos for owner 2: [id=1] 장보기 [TODO], [id=2] 운동 [TODO]
[TodoController] Todo 1 completed
[TodoController] Todos for owner 2: [id=1] 장보기 [DONE], [id=2] 운동 [TODO]
[UserController] @PreDestroy: 2명 사용자 등록 상태로 종료
```

### `EnhanceAbsenceDemo.main()` — enhance 부재 시연

`@Bean` 본문에서 다른 `@Bean` 메서드를 *직접 호출*하면 매번 새 인스턴스가 생성된다는 사실을 콘솔 출력으로 박제.

```
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 부재): account.user == ctx.user → false
```

향후 byte-buddy enhance 도입 시 두 번째 줄이 `→ true`로 바뀌는 게 마일스톤. 본 데모는 *현재 동작*을 박제하는 *살아있는 문서*.

## 실행

IDE에서 main() 직접 실행 또는:

```bash
./gradlew :sfs-samples:test    # 통합 테스트로 main 출력 회귀 검증
```

## 패키지 구조

```
com.choisk.sfs.samples.todo/
├── TodoDemoApplication.java          # main #1
├── EnhanceAbsenceDemo.java           # main #2
├── config/AppConfig.java
├── domain/User.java, Todo.java
├── repository/UserRepository.java, TodoRepository.java
├── service/UserService.java, TodoService.java
├── controller/UserController.java, TodoController.java
└── support/IdGenerator.java
```
```

- [x] **Step 3: 본 plan DoD 12항목 모두 `[x]` + 실행 기록 블록 추가**

`docs/superpowers/plans/2026-04-25-phase-1c-samples-demo.md` 하단 DoD 섹션의 12항목을 모두 `[x]`로 갱신. 그리고 마지막에:

```markdown
> **실행 기록 (YYYY-MM-DD):**
>
> - **회귀:** sfs-core 28 + sfs-beans 58 + sfs-context 44 + sfs-samples 7 = **총 137 PASS / 0 FAIL**
> - **빌드:** `./gradlew build` → BUILD SUCCESSFUL
> - **추가 커밋:** A1 + B1 + C1 + D1 + D2 + E1 + F1 + G1 + H1 = 9 커밋
> - **Phase 1C 종료** — main 머지 준비 완료
```

- [x] **Step 4: 최종 커밋**

```bash
git add sfs-samples/README.md docs/superpowers/plans/2026-04-25-phase-1c-samples-demo.md
git commit -m "docs: Plan 1C 마감 — sfs-samples README + DoD 12항목 [x] + Phase 1C 종료"
```

---

## 🎯 Plan 1C Definition of Done — 최종 체크리스트 (12항목)

**모듈/구조:**

- [x] 1. `settings.gradle.kts`에 `sfs-samples` 등록 (Task A1)
- [x] 2. `sfs-samples/build.gradle.kts` 작성 — sfs-context 의존만 (Task A1)
- [x] 3. 도메인 POJO 2종 (`User`, `Todo`) (Task A1)

**컴포넌트:**

- [x] 4. `IdGenerator` + 단위 테스트 (Task B1)
- [x] 5. `AppConfig` (`@Bean` 2종: no-arg + 매개변수 자동 주입) (Task C1)
- [x] 6. Repository 2종 (`UserRepository`, `TodoRepository`) — `@Repository` 메타애노테이션 + `@Autowired idGen` 시연 (Task C1)
- [x] 7. Service 2종 — `UserService`(`@PostConstruct` 시드) + `TodoService`(다중 의존 + 분기) (Task D1, D2)
- [x] 8. Controller 2종 — `UserController`(`@PreDestroy`) + `TodoController` (Task E1)

**시연:**

- [x] 9. `TodoDemoApplication.main()` — 8 라인 콘솔 출력 시퀀스 (Task F1)
- [x] 10. `EnhanceAbsenceDemo.main()` — 두 줄 비교 출력, `→ false`로 enhance 부재 박제 (Task G1)

**품질:**

- [x] 11. 통합 테스트 2건 — `TodoDemoApplicationTest` + `EnhanceAbsenceDemoTest` 모두 PASS (Task F1, G1)
- [x] 12. `./gradlew build` 전체 PASS + 누적 ~137 테스트 PASS (Task H1)

> **실행 기록 (2026-04-25):**
>
> - **회귀:** sfs-core 28 + sfs-beans 58 + sfs-context 44 + sfs-samples 7 = **총 137 PASS / 0 FAIL**
> - **빌드:** `./gradlew build` → BUILD SUCCESSFUL
> - **추가 커밋:** A1 + B1 + C1 + D1 + D2 + E1 + F1 + G1 + H1 = 9 커밋
> - **Phase 1C 종료** — main 머지 준비 완료

---

## ▶ Plan 1C 완료 후 다음 단계

1. **CLAUDE.md "완료 후 품질 게이트" 3단계** — 다관점 코드리뷰 → 리팩토링 → `/simplify` 패스. 단 본 plan은 *데모 application*이라 1B-α/1B-β보다 가벼운 게이트가 적절 (단일 다관점 리뷰 + simplify 패스만으로 충분할 수 있음).
2. **main 머지** — `feat/phase1c-samples` → main `--no-ff` 또는 GitHub PR.
3. **다음 방향 결정** — Phase 2 (AOP) 진입 (Phase 1B-β 보류 박제 5건 중 *Phase 2 직전 처리 2건* 먼저) / 보류 항목 점진 추가 / 다른 학습 영역.
