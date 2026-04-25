# Phase 1C — sfs-samples 데모 application 설계 스펙

- **작성일:** 2026-04-25
- **페이즈:** Phase 1C (Phase 1 완성 직후, AOP 진입 전)
- **관련 문서:**
  - 상위 설계: `docs/superpowers/specs/2026-04-19-ioc-container-design.md`
  - 선행 plan: `docs/superpowers/plans/2026-04-23-phase-1b-beta-processors.md` (완료, main 머지 `e325a0c`)
- **선행 조건:** Phase 1B-β 완료 (sfs-core 28 + sfs-beans 58 + sfs-context 44 = 130 PASS, 품질 게이트 통과 B+ 84/100, 보류 5건 박제됨)

---

## 1. 배경과 목표

### 1.1 Phase 1 완료 시점의 상태

Plan 1B-β 완료로 다음 시나리오가 동작하는 IoC 컨테이너가 갖춰졌다:

- `@Configuration` + `@Bean` 클래스 등록, `@Bean` 메서드 매개변수 자동 주입
- `@Component` (+ 메타 stereotype: `@Service`/`@Repository`/`@Controller`) 컴포넌트 스캔
- `@Autowired` 필드 주입 (`required=false` 시 null 주입)
- `@PostConstruct` (BPP:before) / `@PreDestroy` (LIFO destroy)
- `AnnotationConfigApplicationContext` 생성자 한 줄로 처리기 3종 자동 등록 + refresh

회귀 테스트 130개로 *각 기능의 동작*은 박제되었다. 그러나 학습자가 *전체 흐름이 IDE에서 어떻게 보이는지*를 확인할 진입점은 아직 없다.

### 1.2 본 phase의 목적

`sfs-samples` 신규 모듈에 *실행 가능한 데모 application*을 추가하여 다음을 달성한다:

1. **Phase 1의 7가지 기능을 1:1 매핑한 코드 시연** — 각 클래스가 어떤 Phase 1 기능을 시연하는지 명확.
2. **`main()` 메서드로 IDE 실행 가능** — 학습자가 콘솔 출력으로 *Spring 동작 흐름*을 눈으로 확인.
3. **enhance 부재의 효과를 데모로 박제** — Phase1IntegrationTest의 `directCallFormCreatesDistinctInstanceWithoutEnhance` 산출물을 *실행 가능한 별도 main*에서 콘솔 출력으로 시연. 향후 byte-buddy 도입 시 *동일 코드의 출력만 바뀌는* 마일스톤이 된다.
4. **JUnit 통합 테스트로 데모 출력 회귀 보장** — `System.setOut` 캡처로 기대 출력 검증.

### 1.3 비목표 (Out of Scope)

- HTTP 서버 / 웹 프레임워크 — Phase 2 (sfs-mvc) 영역.
- 영속화 (DB/JPA) — 인메모리 `Map`만 사용.
- 사용자 입력 받는 대화형 콘솔 — 사전 정의된 시연 시퀀스만.
- 도메인의 풍부한 비즈니스 로직 — User+Todo CRUD 미니멀 시나리오에 한정.
- byte-buddy enhance 도입 — `EnhanceAbsenceDemo`는 enhance 부재의 *시연*만 하고 실제 도입은 보류 항목.
- 다중 시나리오 분기 — 검색/통계/예외 처리/이벤트 등은 본 spec 범위 외.

---

## 2. 모듈 + 패키지 구조

### 2.1 모듈 신설

`settings.gradle.kts`에 `sfs-samples` 추가. `sfs-samples/build.gradle.kts`는 다음만 의존:

```kotlin
dependencies {
    implementation(project(":sfs-context"))   // 역방향 의존 차단을 위해 sfs-beans/sfs-core 직접 의존 금지
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}
```

`sfs-samples`는 어떤 다른 모듈도 import하지 않는다 (의존 방향: `sfs-samples → sfs-context → sfs-beans → sfs-core`).

### 2.2 패키지 레이아웃

```
sfs-samples/src/main/java/com/choisk/sfs/samples/todo/
├── TodoDemoApplication.java        # main #1 (도메인 시연 시퀀스)
├── EnhanceAbsenceDemo.java         # main #2 (enhance 부재 시연)
├── config/
│   └── AppConfig.java              # @Configuration + @Bean 2개
├── domain/
│   ├── User.java                   # POJO (id, name, createdAt)
│   └── Todo.java                   # POJO (id, ownerId, title, status)
├── repository/
│   ├── UserRepository.java         # @Repository, Map<Long, User>
│   └── TodoRepository.java         # @Repository, Map<Long, Todo>
├── service/
│   ├── UserService.java            # @Service
│   └── TodoService.java            # @Service
├── controller/
│   ├── UserController.java         # @Controller
│   └── TodoController.java         # @Controller
└── support/
    └── IdGenerator.java            # 학습용 ID 발급 유틸 (Clock 의존)

sfs-samples/src/test/java/com/choisk/sfs/samples/todo/
├── TodoDemoApplicationTest.java    # main #1 출력 캡처 검증
└── EnhanceAbsenceDemoTest.java     # main #2 출력 캡처 검증
```

도메인이 단일 묶음(todo)이므로 패키지 분기는 layer 별로만. 도메인 분기(`com.choisk.sfs.samples.user`, `...todo`)는 미니멀 시나리오와 어울리지 않아 미사용.

---

## 3. 컴포넌트 카탈로그

### 3.1 도메인 (POJO, 인스턴스 변수만)

```java
// User.java
public class User {
    public Long id;
    public String name;
    public java.time.Instant createdAt;
    public User(Long id, String name, java.time.Instant createdAt) { ... }
}

// Todo.java
public class Todo {
    public enum Status { TODO, DONE }
    public Long id;
    public Long ownerId;
    public String title;
    public Status status;
    public Todo(Long id, Long ownerId, String title) { this.status = Status.TODO; ... }
}
```

POJO는 `@Component`가 *아니다*. 도메인 객체는 매번 `new`로 생성한다 (Repository 안에서). 컨테이너가 관리하는 빈은 *layer 클래스*만.

### 3.2 Repository (`@Repository`)

```java
@Repository
public class UserRepository {
    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong seq = new AtomicLong(0);
    public User save(String name, Instant now) { ... }
    public Optional<User> findById(Long id) { ... }
    public int count() { ... }
}

@Repository
public class TodoRepository {
    @Autowired IdGenerator idGen;          // 필드 주입 (F1 시연)
    private final Map<Long, Todo> store = new ConcurrentHashMap<>();
    public Todo save(Long ownerId, String title) {
        Todo t = new Todo(idGen.next(), ownerId, title);
        store.put(t.id, t);
        return t;
    }
    public List<Todo> findByOwnerId(Long ownerId) { ... }
    public Optional<Todo> findById(Long id) { ... }
}
```

### 3.3 Service (`@Service`)

```java
@Service
public class UserService {
    @Autowired UserRepository userRepo;
    @Autowired java.time.Clock clock;       // @Bean으로 등록된 외부 객체 주입 (E1+C1 시연)

    @PostConstruct
    void seedDefaultUser() {                 // G1 시연 — 컨테이너 시작 직후 한 번 호출
        userRepo.save("기본 사용자", clock.instant());
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) { return userRepo.save(name, clock.instant()); }
    public Optional<User> find(Long id) { return userRepo.findById(id); }
    public int total() { return userRepo.count(); }
}

@Service
public class TodoService {
    @Autowired TodoRepository todoRepo;
    @Autowired UserRepository userRepo;     // 다중 의존 시연

    public Todo create(Long ownerId, String title) {
        userRepo.findById(ownerId).orElseThrow(() ->
            new IllegalArgumentException("Unknown user id=" + ownerId));
        return todoRepo.save(ownerId, title);
    }
    public List<Todo> listFor(Long ownerId) { return todoRepo.findByOwnerId(ownerId); }
    public Todo complete(Long todoId) {
        Todo t = todoRepo.findById(todoId).orElseThrow(() ->
            new IllegalArgumentException("Unknown todo id=" + todoId));
        t.status = Todo.Status.DONE;
        return t;
    }
}
```

### 3.4 Controller (`@Controller`)

`@Controller` 스테레오타입은 본 학습 컨테이너에서 동작상 `@Component`와 동일 (1A에서 메타애노테이션으로 등록). 본 데모에서는 *layer 분리의 의미*만 가진다.

```java
@Controller
public class UserController {
    @Autowired UserService userService;

    @PreDestroy                              // G2 시연 — close() 시 LIFO로 호출
    void logShutdown() {
        System.out.println("[UserController] @PreDestroy: " + userService.total() + "명 사용자 등록 상태로 종료");
    }

    public User create(String name) {
        User u = userService.register(name);
        System.out.println("[UserController] User created: " + u.name + " (id=" + u.id + ")");
        return u;
    }
}

@Controller
public class TodoController {
    @Autowired TodoService todoService;

    public Todo create(Long ownerId, String title) {
        Todo t = todoService.create(ownerId, title);
        System.out.println("[TodoController] Todo created: id=" + t.id + " owner=" + t.ownerId + " \"" + t.title + "\"");
        return t;
    }
    public void list(Long ownerId) {
        var todos = todoService.listFor(ownerId);
        String summary = todos.stream()
            .map(t -> "[id=" + t.id + "] " + t.title + " [" + t.status + "]")
            .collect(java.util.stream.Collectors.joining(", "));
        System.out.println("[TodoController] Todos for owner " + ownerId + ": " + summary);
    }
    public void complete(Long todoId) {
        todoService.complete(todoId);
        System.out.println("[TodoController] Todo " + todoId + " completed");
    }
}
```

### 3.5 Configuration (`@Configuration`)

```java
@Configuration
public class AppConfig {
    @Bean
    public java.time.Clock systemClock() {                    // no-arg @Bean
        return java.time.Clock.systemDefaultZone();
    }

    @Bean
    public IdGenerator idGenerator(java.time.Clock clock) {   // 매개변수 자동 주입 (C1 시연)
        return new IdGenerator(clock);
    }
}
```

### 3.6 Support 유틸

```java
public class IdGenerator {
    private final java.time.Clock clock;
    private final java.util.concurrent.atomic.AtomicLong seq = new AtomicLong(0);
    public IdGenerator(java.time.Clock clock) { this.clock = clock; }
    public long next() { return seq.incrementAndGet(); }
    public java.time.Instant nowInstant() { return clock.instant(); }
}
```

`IdGenerator`는 `@Component`가 *아니다*. `@Bean`으로만 등록 — Phase 1 7가지 기능 중 "@Bean 매개변수 자동 주입(C1)"을 시연하기 위한 *유일한* 빈이 매개변수를 받는 형태가 된다.

---

## 4. Bean wiring 매핑 — 클래스 ↔ Phase 1 기능

| Phase 1 기능 | 시연 클래스 / 위치 |
|---|---|
| `@Configuration` 인식 | `AppConfig` |
| `@Bean` (no-arg) | `AppConfig#systemClock()` |
| `@Bean` (매개변수 자동 주입, C1) | `AppConfig#idGenerator(Clock)` |
| `@Component` 메타애노테이션 (4 stereotype) | `@Repository`, `@Service`, `@Controller` 클래스 7종 |
| `@Autowired` 필드 주입 (F1) | `UserService.userRepo`, `UserService.clock`, `TodoService.todoRepo`, `TodoService.userRepo`, `UserController.userService`, `TodoController.todoService`, `TodoRepository.idGen` |
| `@PostConstruct` (G1, BPP:before) | `UserService.seedDefaultUser()` |
| `@PreDestroy` (G2, LIFO) | `UserController.logShutdown()` |
| 컨테이너 자동 등록 (H1) | `AnnotationConfigApplicationContext` 생성자 한 줄 호출 |

---

## 5. 데이터 흐름 — `TodoDemoApplication.main()` 시퀀스

```java
public class TodoDemoApplication {
    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class,
                UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class,
                UserController.class, TodoController.class)) {

            UserController userController = ctx.getBean(UserController.class);
            TodoController todoController = ctx.getBean(TodoController.class);

            User alice = userController.create("Alice");                  // (3)
            todoController.create(alice.id, "장보기");                     // (4)
            todoController.create(alice.id, "운동");                       // (5)
            todoController.list(alice.id);                                // (6)
            todoController.complete(1L);                                  // (7)
            todoController.list(alice.id);                                // (8)
        }
        // try-with-resources가 ctx.close() 호출 → @PreDestroy 트리거
    }
}
```

**기대 콘솔 출력 (순서대로):**

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

> **시퀀스 메모:**
> - 컨테이너가 `register(...)` + `refresh()`를 생성자에서 처리 (1A/1B-α 동작) → `@PostConstruct`가 main의 첫 줄(`userController.create`)보다 *먼저* 출력된다.
> - `User.id=1`은 `@PostConstruct`의 시드, `id=2`가 Alice. 시드 동작이 *컨테이너 라이프사이클*의 일부임을 출력 순서로 박제.
> - `Todo.id`는 `IdGenerator.next()`로 1부터 발급 (Repository는 `idGen`을 주입받음).

---

## 6. `EnhanceAbsenceDemo.main()` (별도 진입점)

```java
public class EnhanceAbsenceDemo {

    static class User { /* POJO with id field */ }
    static class Account { final User user; Account(User user) { this.user = user; } }

    @Configuration
    static class ArgFormConfig {
        @Bean public User user() { return new User(/* id=42 */); }
        @Bean public Account account(User user) { return new Account(user); }   // 매개변수 라우팅 ✓
    }

    @Configuration
    static class DirectCallConfig {
        @Bean public User user() { return new User(/* id=42 */); }
        @Bean public Account account() { return new Account(user()); }          // 직접 호출 ✗
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

**기대 콘솔 출력:**

```
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 부재): account.user == ctx.user → false
```

향후 byte-buddy enhance 도입 시 두 번째 줄이 `→ true`로 바뀌는 게 마일스톤. 본 데모는 *현재 동작*을 박제하는 *살아있는 문서*.

---

## 7. 테스트 전략

### 7.1 `TodoDemoApplicationTest`

```java
class TodoDemoApplicationTest {

    @Test
    void demoSequenceProducesExpectedOutput() {
        var stdout = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        System.setOut(new java.io.PrintStream(stdout, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            TodoDemoApplication.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        String[] lines = stdout.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n");

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

### 7.2 `EnhanceAbsenceDemoTest`

```java
class EnhanceAbsenceDemoTest {
    @Test
    void enhanceAbsenceShowsBothBranches() {
        // System.setOut 캡처 패턴 동일
        EnhanceAbsenceDemo.main(new String[0]);
        // 두 줄 모두 포함 확인:
        //  - "Arg form ... → true"
        //  - "Direct call ... → false"
    }
}
```

### 7.3 회귀 카운트 예상

본 phase 추가 후: 130 → **132 PASS** (+2 통합 테스트). 기존 회귀 변동 없음.

---

## 7.4 사전 확인 사항 (plan 시점에 grep)

- `AnnotationConfigApplicationContext`가 `AutoCloseable`을 구현하는지 확인. 1A/1B-α에서 `Closeable`만 구현했다면 try-with-resources에서 `IOException` catch가 필요해진다. 본 spec은 `AutoCloseable` 또는 `Closeable extends AutoCloseable`을 가정한다 (Spring 본가 패턴). 만약 인터페이스 미충족이면 plan에서 한 줄 보강 task로 처리.
- `AnnotationConfigApplicationContext`의 가변 인자 생성자(`Class<?>... componentClasses`)가 `@Component` + `@Configuration` 클래스를 모두 등록하는지 확인. 1B-α에서 추가됨.
- `ClassUtils`/`AnnotationUtils` 등 sfs-core 유틸은 본 spec 코드에서 *직접 사용하지 않으므로* import 경로만 점검.

---

## 8. 모듈 의존 정책

| 모듈 | 의존 (`api` 또는 `implementation`) |
|---|---|
| `sfs-core` | (외부만 — ASM, byte-buddy 카탈로그) |
| `sfs-beans` | `sfs-core` |
| `sfs-context` | `sfs-beans` |
| **`sfs-samples` (신설)** | `sfs-context` (sfs-beans/sfs-core 직접 의존 금지) |

`sfs-samples`는 *그래프의 leaf*. 어떤 모듈도 sfs-samples를 의존하지 않는다. `settings.gradle.kts`에만 추가.

---

## 9. Definition of Done

- [ ] `sfs-samples` 모듈 신설 + `settings.gradle.kts` 등록 + `build.gradle.kts` 작성
- [ ] 도메인 클래스 2개 (`User`, `Todo`)
- [ ] Layer 클래스 7개 (Repository 2 + Service 2 + Controller 2 + IdGenerator 1)
- [ ] `AppConfig` (`@Bean` 2개: no-arg + 매개변수 주입)
- [ ] `TodoDemoApplication.main()` — 시퀀스 8단계, 기대 출력 일치
- [ ] `EnhanceAbsenceDemo.main()` — 두 줄 출력, `false`로 enhance 부재 박제
- [ ] `TodoDemoApplicationTest` — 출력 캡처로 8 라인 순서 검증 PASS
- [ ] `EnhanceAbsenceDemoTest` — 두 boolean 출력 검증 PASS
- [ ] `./gradlew :sfs-samples:test` PASS
- [ ] `./gradlew build` 전체 PASS, 누적 132 테스트 PASS
- [ ] sfs-samples README 작성 — Phase 1 기능 1:1 매핑 표 + 실행 방법
- [ ] sfs-samples → sfs-context만 의존 확인 (gradle dependencies 그래프)

---

## 10. 후속 작업 (본 spec 범위 외)

- **main 머지** — `feat/phase1c-samples` → main `--no-ff` 또는 PR.
- **Phase 2 (AOP) brainstorming** — Phase 1B-β plan의 보류 박제 5건 중 *Phase 2 직전 처리 2건*(다운캐스팅 묶음)을 Phase 2 첫 task로 처리.
- **deep version 점진 추가** (선택) — byte-buddy enhance 도입 시 `EnhanceAbsenceDemo`의 두 번째 줄 출력이 `true`로 바뀌는 마일스톤 박제.
