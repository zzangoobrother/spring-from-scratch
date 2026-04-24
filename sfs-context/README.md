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
