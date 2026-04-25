# sfs-context

Spring From Scratch의 **ApplicationContext 계층**. 애노테이션 메타데이터 처리와
라이프사이클(`refresh()`/`close()`) 책임을 담당한다.

## 의존 관계
- 의존: `sfs-beans` (BeanDefinition, BeanFactory)
- 외부: ASM(상속), byte-buddy(1B-β의 `@Configuration` 클래스 enhance에서 사용)
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
