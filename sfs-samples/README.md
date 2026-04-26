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
