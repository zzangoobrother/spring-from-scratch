# sfs-beans

Spring From Scratch의 **컨테이너 코어 모듈**. `BeanDefinition`, `BeanFactory` 계층,
`FactoryBean`, 확장점 인터페이스들, 3-level cache 기반 싱글톤 레지스트리를 담당한다.

## 의존 관계
- 의존: `sfs-core` (유틸, 예외, ASM 메타데이터)
- 의존됨: `sfs-context` (Plan 1B에서 추가)

## Spring 원본 매핑

| 이 모듈 | Spring 원본 |
|---|---|
| `BeanFactory` / `ListableBeanFactory` / `HierarchicalBeanFactory` | 동명 인터페이스 |
| `BeanDefinition` | `AbstractBeanDefinition` 계열을 mutable class로 단순화 |
| `DefaultSingletonBeanRegistry` | 동명. `CacheLookup` sealed 결과는 우리 개선 |
| `AbstractBeanFactory` / `AbstractAutowireCapableBeanFactory` / `DefaultListableBeanFactory` | 동명 |
| `FactoryBean` | 동명 |
| `BeanPostProcessor` / `InstantiationAwareBeanPostProcessor` / `SmartInstantiationAwareBeanPostProcessor` | 동명 |
| `BeanFactoryPostProcessor` | 동명 |

## 내부 구현 특징 (Approach 3)

- `CacheLookup` **sealed interface**로 3-level 조회 결과 표현 → 호출부에서 pattern matching switch
- `Scope` **sealed interface**로 Singleton/Prototype 닫기 (Request/Session은 sealed에 추가로 확장)
- `BeanDefinition`은 **mutable class**로 유지 — BFPP가 수정할 수 있어야 함
- `PropertyValue`, `BeanReference`, `BeanCreationContext`는 **record**

## 학습 포인트

### 3-Level Cache 핵심 아이디어
1차 `singletonObjects` = 완성된 빈, 2차 `earlySingletonObjects` = 조기 노출된 참조,
3차 `singletonFactories` = `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference`
훅을 실행할 수 있는 지연 팩토리. 자세한 시나리오는 `docs/superpowers/specs/2026-04-19-ioc-container-design.md` 섹션 5 참조.

### 승격 시점 분리
`lookup()`이 3차 hit일 때 자동 승격하지 않고 `promoteToEarlyReference()` 별도 호출.
이유: 호출자가 SmartBPP 체인 실행 여부를 제어해야 하므로 (Spring 원본과 동일 의도).

## 테스트 실행
```bash
./gradlew :sfs-beans:test
```

주요 통합 테스트:
- `integration/CircularReferenceSetterTest` — 3-level cache 순환 해결
- `integration/CircularReferenceConstructorTest` — 생성자 순환 적절 예외
- `integration/FactoryBeanTest` — `&` 접두사 + 싱글톤 캐싱
- `integration/BeanPostProcessorOrderTest` — 콜백 순서 (awareName → bpp:before → afterProps → customInit → bpp:after)
- `integration/BeanPostProcessorExtensionsTest` — IABPP / SIABPP / BFPP 확장 훅 검증
