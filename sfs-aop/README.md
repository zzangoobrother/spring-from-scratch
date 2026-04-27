# sfs-aop

Spring From Scratch의 **AOP 본체 모듈**. byte-buddy 기반 advice chain을 컨테이너에 *2차 추상*으로 통합.

## 의존 관계

- 의존: `sfs-context` (api), `byte-buddy 1.14.19` (implementation, 직접 명시)
- 의존됨: `sfs-samples`

의존 그래프 (모듈 자치 원칙):

```
sfs-samples → sfs-aop → sfs-context → sfs-beans → sfs-core
```

## 핵심 컴포넌트

### 애노테이션 5종 (`annotation/`)

| 애노테이션 | 부착 대상 | 역할 |
|---|---|---|
| `@Aspect` | TYPE | "이 클래스가 advice 정의" 마커. `@Component`와 함께 부착해야 컨테이너 등록 |
| `@Loggable` | METHOD/TYPE | 시연용 매칭 마커. 사용자 정의 마커 자유 (advice의 `value()` 인자) |
| `@Around` | METHOD | around advice — `value: Class<? extends Annotation>`, 메서드 시그니처 `Object(ProceedingJoinPoint)` |
| `@Before` | METHOD | before advice — 메서드 시그니처 `void(JoinPoint)` |
| `@After` | METHOD | after advice — `try/finally`의 finally, 시그니처 `void(JoinPoint)` |

### 인프라 (`support/`)

- `AspectEnhancingBeanPostProcessor` — BPP 본체. 빈 생성 후 매칭 검사 + byte-buddy 서브클래스 + 필드 복사
- `AdviceInterceptor` — byte-buddy MethodDelegation 인터셉터. 3종 advice 합성 (around 바깥 / before 진입 / super / after finally)
- `AspectRegistry` — BPP 내부 advice 자료구조 (빈으로 등록 안 함)
- `JoinPoint`/`ProceedingJoinPoint` — advice 권한 사다리를 시그니처에 박제
- `MethodInvocationJoinPoint` — 두 인터페이스 단일 구현
- `AdviceInfo` (record) / `AdviceType` (enum) — 메타데이터

## 사용법

```java
@Configuration
@ComponentScan(basePackages = "com.example")
public class AppConfig {
    // AOP 활성화 — 한 줄 boilerplate
    @Bean
    public AspectEnhancingBeanPostProcessor aspectBpp() {
        return new AspectEnhancingBeanPostProcessor();
    }
}

@Aspect
@Component
public class LoggingAspect {
    @Around(Loggable.class)
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long t = System.nanoTime();
        try { return pjp.proceed(); }
        finally { System.out.println("elapsed=" + (System.nanoTime() - t)); }
    }
}

@Component
public class TodoController {
    @Loggable
    public void complete(Long id) { ... }
}
```

## 한계 (본 phase 의도된 단순화)

- **표현식 파서 없음** — `@Around("@annotation(...)")` 같은 문자열 표현식 미지원. *Class 직접 지정*만
- **`@AfterReturning`/`@AfterThrowing` 없음** — `@Around` 안의 try/catch로 표현 가능 (Phase 3에서 도입)
- **advice 우선순위 (`@Order`) 없음** — 한 메서드당 advice 종류별 1개씩 가정
- **`@EnableAspect` 자동 등록 없음** — 사용자가 `@Bean`으로 BPP 명시 등록 (Phase 3)
- **인스턴스 교체 = 필드 복사** — `final` 필드는 fail-fast로 throw. `target` 위임 wrapper는 Phase 3 이후
- **`final` 메서드 silent skip** — Phase 2C에서 검출 추가 예정
- **early reference 문제** — 다른 빈에서 *주입을 통한 호출* 시 advice 비적용 (Phase 2C 박제)

## 학습 가치

- **byte-buddy 인프라 재활용**: Phase 2A의 `ConfigurationClassEnhancer` 패턴이 *advice chain*에 그대로 적용
- **BFPP/BPP 시점 분리의 진짜 보상**: Configuration enhance(BFPP) vs Aspect proxy(BPP)
- **advice 합성 = 함수 합성**: `try/finally`로 wrap한 `Callable`이 `ProceedingJoinPoint.proceed()`에 연결되는 lambda 중첩이 *advice chain의 본질*을 박제
- **AOP = IoC 안에 접어 넣은 시스템**: Aspect가 빈으로 등록되어 `@Autowired` 의존 사용 가능 — Spring AOP의 영리한 단순화 직접 체험

## 실행

```bash
./gradlew :sfs-aop:test  # 단위 + 통합 테스트
```
