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

1. 실패 테스트 작성
2. `./gradlew :<module>:test --tests <TestName>` 로 FAIL 확인
3. 최소 구현
4. PASS 확인
5. 한국어 커밋 메시지로 커밋 (`feat(<module>): ...`, `test(<module>): ...`, `chore: ...`, `docs: ...`)
6. Plan 문서의 해당 Step 체크박스 `- [ ]` → `- [x]` 업데이트
7. 실행 중 편차 발생 시 Plan 문서에 `> **실행 기록 (YYYY-MM-DD):**` 블록으로 기록

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
