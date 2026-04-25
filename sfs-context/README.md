# sfs-context

Spring From Scratch의 **ApplicationContext 계층**. 애노테이션 메타데이터 처리와
라이프사이클(`refresh()`/`close()`) 책임을 담당한다.

## 의존 관계
- 의존: `sfs-beans` (BeanDefinition, BeanFactory)
- 외부: ASM(상속), byte-buddy(1B-α에서 카탈로그 등록만 유지. 학습용 최소 스코프 1B-β에서는 `@Configuration` enhance 보류)
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

## 1B-β 시점 동작 (학습용 최소 스코프, Phase 1 종료)

`@Component` + `@Configuration`/`@Bean` (매개변수 자동 주입 포함) + `@Autowired` (필드 주입만) + `@PostConstruct`/`@PreDestroy`가 동작하는 작은 IoC 컨테이너.

```java
@Configuration
class AppConfig {
    @Bean Repo repo() { return new Repo(); }
    @Bean Service service(Repo repo) { return new Service(repo); }  // 매개변수 자동 주입
}

@Component
class Worker {
    @Autowired Service service;
    @PostConstruct void init() { /* 호출됨 */ }
    @PreDestroy void cleanup() { /* close() 시 호출됨 */ }
}

var ctx = new AnnotationConfigApplicationContext(AppConfig.class, Worker.class);
Worker w = ctx.getBean(Worker.class);
// w.service 주입됨 + w.service.repo가 컨테이너 싱글톤과 동일
ctx.close();
// @PreDestroy 호출됨 (등록 역순)
```

**enhance 부재의 한계 (학습 시연):** `@Bean` 본문에서 다른 `@Bean` 메서드를 *직접 호출*하면 매번 새 인스턴스가 만들어진다. 매개변수 형태(컨테이너 라우팅)는 동일 싱글톤이 보장된다. `integration/Phase1IntegrationTest`의 `directCallFormCreatesDistinctInstanceWithoutEnhance`에서 박제.

**보류 항목 (deep version `75842e5` 참조):**
- byte-buddy enhance (직접 호출 형태도 동일 싱글톤 보장)
- 세터/생성자 주입
- `List<T>`/`Map<String, T>` 컬렉션 주입
- `@Primary`/`@Qualifier` 다수 후보 폴백
- ASM 사전 필터

## 테스트 실행
```bash
./gradlew :sfs-context:test
```

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
