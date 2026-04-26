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

### `ConfigurationEnhanceDemo.main()` — enhance 적용 시연

`@Bean` 본문에서 다른 `@Bean` 메서드를 *직접 호출*해도 byte-buddy enhance 인터셉터를 경유해 컨테이너 싱글톤이 라우팅됨을 콘솔 출력으로 박제.

```
Arg form (매개변수 라우팅): account.user == ctx.user → true
Direct call (본문 호출, enhance 적용): account.user == ctx.user → true
```

Phase 1C 시점에 두 번째 줄은 `→ false`였음 — Phase 2A에서 `ConfigurationClassEnhancer` 도입과 동시에 `→ true`로 회수. 코드 한 줄 안 바꾸고 *진실이 뒤집힌* 살아있는 마일스톤.

## Phase 2A 갱신 사항

본 모듈은 Phase 2A에서 다음과 같이 변경:

- **`@ComponentScan` 적용** — `AppConfig`에 `@ComponentScan(basePackages = "com.choisk.sfs.samples.todo")` 추가. `TodoDemoApplication`의 7 클래스 명시 등록이 한 줄(`AppConfig.class`) 진입점으로 축약됨. Spring Boot의 `@SpringBootApplication`(= `@Configuration + @ComponentScan`)을 분해해서 다시 조립한 형태.
- **IdGenerator/Clock 통일** — `UserService`가 `Clock`을 직접 주입받지 않고 `IdGenerator.nowInstant()`로 시간을 얻음. `AppConfig`의 `@Bean Clock systemClock()`은 *IdGenerator의 매개변수 자동 주입* 시연 빈으로 그대로 유지 (Phase 1B-β의 학습 가치 보존).
- **`EnhanceAbsenceDemo` → `ConfigurationEnhanceDemo` rename** — Phase 1C에서 박제한 `→ false`가 Phase 2A enhance 도입과 동시에 `→ true`로 회수. 클래스명/문구도 진실에 맞춰 갱신.

## 시연 마일스톤

| 시점 | `account.user == ctx.user` (직접 호출 형태) |
|---|---|
| Phase 1C 끝 | **false** (enhance 미구현 — Phase 1C 박제) |
| Phase 2A 끝 | **true** (byte-buddy enhance — Phase 2A 회수) |

`ConfigurationEnhanceDemoTest`가 위 변형을 자동 검증 — 박제(테스트)는 *진실이 바뀌면 함께 갱신*된다는 메타 학습 가치를 한 커밋에 화석화.

## 실행

IDE에서 main() 직접 실행 또는:

```bash
./gradlew :sfs-samples:test    # 통합 테스트로 main 출력 회귀 검증
```

## 패키지 구조

```
com.choisk.sfs.samples.todo/
├── TodoDemoApplication.java          # main #1
├── ConfigurationEnhanceDemo.java     # main #2
├── config/AppConfig.java
├── domain/User.java, Todo.java
├── repository/UserRepository.java, TodoRepository.java
├── service/UserService.java, TodoService.java
├── controller/UserController.java, TodoController.java
└── support/IdGenerator.java
```
