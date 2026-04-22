# Spring From Scratch — 프로젝트 규약

## 개발 방식

이 프로젝트의 구현 작업은 **superpowers 스킬 조합**으로 진행한다. 프로젝트 전용 TDD 스킬은 만들지 않는다 (이유: 이미 존재하는 `superpowers:test-driven-development`와 중복되고, Plan 문서 자체가 체크박스 기반 스텝을 갖고 있어 진실 원천이 분리될 위험).

### 사용 스킬

| 스킬 | 역할 |
|---|---|
| `superpowers:test-driven-development` | 모든 기능/버그픽스 구현 전 적용. 실패 테스트 → 구현 → 통과 사이클 강제 |
| `superpowers:executing-plans` | `docs/superpowers/plans/` 하위 구현 플랜을 체크박스 단위로 실행 |
| `superpowers:subagent-driven-development` | 독립 태스크가 다수일 때 서브에이전트로 병렬 실행 (선택) |

### 플랜 실행 루틴

Plan 문서 (`docs/superpowers/plans/*.md`)의 각 Task는 다음 사이클을 따른다:

1. 실패 테스트 작성 *(TDD 적용 대상에 한함, 아래 "TDD 적용 가이드" 참조)*
2. `./gradlew :<module>:test --tests <TestName>` 로 FAIL 확인
3. 최소 구현
4. PASS 확인 *(TDD 제외 대상은 1~2 생략, 3 이후 컴파일 + 회귀 테스트 `./gradlew :<module>:test`만 실행)*
5. 한국어 커밋 메시지로 커밋 (`feat(<module>): ...`, `test(<module>): ...`, `chore: ...`, `docs: ...`)
6. Plan 문서의 해당 Step 체크박스 `- [ ]` → `- [x]` 업데이트
7. 실행 중 편차 발생 시 Plan 문서에 `> **실행 기록 (YYYY-MM-DD):**` 블록으로 기록

### TDD 적용 가이드

TDD는 **동작(behavior)이 있는 코드**에만 적용한다. 동작 없는 코드(데이터 컨테이너, 시그니처, 설정)에 TDD를 강제하면 같은 정보를 두 번 쓰게 되어 가치가 없다.

**적용 대상 (필수)**
- 분기/조건/예외 처리, 상태 변경, 알고리즘
- 라이프사이클 콜백, 캐시/동시성/락 로직
- 검증·파싱·매핑에 분기가 있는 경우
- 외부 시스템 경계 (DB, HTTP, 파일 I/O)

**제외 가능 (TDD 사이클 생략하고 컴파일 + 회귀 테스트만)**
- DTO/record/POJO — 필드만 있는 데이터 컨테이너
- enum / sealed 단순 정의, 인터페이스 시그니처
- 설정 파일 (`build.gradle.kts`, `settings.gradle.kts`, `application.yml`)
- SQL DDL / 마이그레이션 스크립트
- README / 주석 / Javadoc / docs 문서
- **추상 골격 클래스** — 서브클래스 없이 인스턴스화 불가, 통합 테스트로 간접 검증 (예: Plan 1A의 Task 24~28 `AbstractBeanFactory` 계열)
- 단순 리네임 / 무동작 리팩토링 / import 정리

**판단 기준 (회색지대 한 줄)**

> *"이 코드의 오류를 잡아낼 자동 안전망이 다른 곳에 있는가?"*
> 있으면 단독 테스트 생략 가능. 없으면 TDD 필수.

예: `BeanDefinition`은 가변 DTO이지만 `getBean()` 통합 테스트가 모든 setter 경로를 거치므로 단독 테스트 생략. `DefaultSingletonBeanRegistry`의 3-level cache 승격 로직은 동시성·순서가 본질이므로 단독 TDD 필수.

### 위임 표준 헤더 (Sonnet 서브에이전트 위임 시 프롬프트 맨 앞에 포함)

`Agent` 툴에 `model: "sonnet"`으로 위임할 때, 사용자의 원 요청 앞에 다음 헤더를 그대로 붙인다:

```
[프로젝트 규약 헤더]
1. 본 Task가 CLAUDE.md "TDD 적용 가이드"의 제외 대상인지 먼저 판단하라.
   - 제외 대상이면: TDD 사이클 생략, 구현 후 `./gradlew :<module>:compileJava` + `:<module>:test`로 컴파일/회귀만 검증
   - 적용 대상이면: `Skill` 도구로 `superpowers:test-driven-development`를 invoke하고 그 사이클을 따라라
2. CLAUDE.md 전체 규약 준수:
   - 한국어 커밋 메시지 (`feat(<module>): ...` 등)
   - 명시 import (와일드카드 `*` 금지)
   - Plan 문서의 해당 Step 체크박스 업데이트
   - 편차 발생 시 Plan 문서에 `> **실행 기록 (YYYY-MM-DD):**` 블록 추가
3. 반환할 정보:
   - 변경한 파일 목록
   - diff 요약 (3~5줄)
   - 테스트 결과 (PASS/FAIL 카운트, 실패 시 메시지)
   - 적용/제외 판단 근거 한 줄
```

## 언어 규약

- 응답/문서/커밋 메시지/주석: **한국어**
- 변수명/함수명/클래스명: 영어 (Java/Spring 표준 준수)

## 빌드 환경

- Java: **25 LTS** (toolchain)
- Gradle: **9.4.1+** (Kotlin DSL, Java 25 toolchain은 Gradle 9.1.0부터 지원)
- 테스트: JUnit 5 + AssertJ (Mockito 사용 안 함)

## 모듈 의존성 방향

```
sfs-samples ──► sfs-context ──► sfs-beans ──► sfs-core
```

역방향 import는 컴파일 에러가 되도록 Gradle이 강제한다.

## 참조 문서

- 설계 스펙: `docs/superpowers/specs/2026-04-19-ioc-container-design.md`
- 구현 플랜: `docs/superpowers/plans/2026-04-19-phase-1a-scaffolding-and-beans.md`
