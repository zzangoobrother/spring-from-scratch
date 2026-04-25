# Phase 1C 작업 재개 체크포인트

- **작성일:** 2026-04-25 (저녁 중단 시점)
- **브랜치:** `feat/phase1c-samples` (main `e325a0c` 베이스)
- **재개 시 첫 작업:** 메타 결정 (A vs B 패턴) → C1 task 진행

---

## 1. 오늘 완료한 일 (시간 순)

### 1.1 Phase 1B-β 마감 + 품질 게이트 + main 머지
- 13 task 구현 (A1~H3) + 박제 갱신 + 품질 게이트 (다관점 리뷰 → ReflectionUtils 일원화 → simplify 패스 11건 반영)
- main 머지 완료 (PR #5, 머지 커밋 `e325a0c`)
- 종합 등급 **B+ (84/100)**, 보류 박제 5건 (Phase 2 직전 2건 + 다음 plan 3건)

### 1.2 Phase 1C 설계 (brainstorming → spec → plan)
- brainstorming 4 결정 (모두 A): 도메인 User+Todo / 사전 시퀀스 main / enhance 부재 별도 demo / 미니멀 시나리오
- 단, B-rich (풍부한 도메인) 변형으로 진행 — 3 layer (Repository/Service/Controller) + Configuration + IdGenerator
- **Spec:** `docs/superpowers/specs/2026-04-25-phase1c-samples-demo-design.md` (463 라인) — 커밋 `467114d`
- **Plan:** `docs/superpowers/plans/2026-04-25-phase-1c-samples-demo.md` (1153 라인, 9 task) — 커밋 `8dcad02`
  > *주의: plan 본문에 "총 8 Task"라 적혀있지만 실제 D 섹션이 D1+D2 분리로 9 task. self-review에서 놓친 사소한 카운트 오류*

### 1.3 Phase 1C 구현 진행 (subagent-driven-development)
- 9 task 중 **2건 완료**, 7건 남음
- 회귀 카운트: 130 → **132 PASS** (B1 +2)

| Task | 상태 | 커밋 | 비고 |
|---|---|---|---|
| A1: 모듈 신설 + User/Todo POJO | ✅ 완료 | `dc01adf` | spec ✅ + quality ✅ |
| B1: IdGenerator + 단위 테스트 (TDD) | ✅ 완료 | `b881a6f` + `4348c66` (docs) | spec/quality review 보류 — 메타 결정 후 |
| C1: AppConfig + Repository 2종 | ⏭ 다음 | — | TDD 제외 |
| D1: UserService + @PostConstruct | | — | TDD 적용 |
| D2: TodoService 다중 의존 | | — | TDD 적용 |
| E1: Controller 2종 | | — | TDD 제외 |
| F1: TodoDemoApplication + 통합 테스트 | | — | TDD 통합 |
| G1: EnhanceAbsenceDemo + 통합 테스트 | | — | TDD 통합 |
| H1: 마감 (README + DoD) | | — | TDD 제외 |

---

## 2. 보류된 메타 결정 (재개 시 첫 답변 필요)

`subagent-driven-development` 스킬이 task별 *3 dispatch* (implementer + spec reviewer + code quality reviewer) 패턴을 권장. 9 task × 3 = **27 subagent 호출**이 됨. A1 사이클을 한 번 돌려보니 *단순 task*(plan 코드 블록과 정확히 일치하는 데이터 컨테이너)는 quality review의 발견이 *의도된 설계*뿐 (A1 quality: Critical 0, Important 0, Minor 2 — 모두 false positive).

### 옵션
- **(A) 스킬 가이드 충실** — 모든 task에 impl + spec + quality (27 dispatch). 안전하지만 비용 큼.
- **(B) 압축 패턴** — task별 impl + spec만 (18 dispatch) + 9 task 모두 끝난 후 *final code quality review* 1회 (전체 변경 다관점 리뷰). CLAUDE.md "Phase 마감 게이트"와 부합. *권장.*
- **(B') 변형** — TDD 적용 task(D1/D2/F1/G1)는 풀 사이클, TDD 제외 task(C1/E1)는 spec only.

### 권장 답변
**B** — task별 spec만 + 마감 시 final code quality review 1회.

내일 재개 시 첫 메시지: "B로 진행" 또는 "A로 진행" 등.

---

## 3. 다음 작업 (Task C1 시작)

### Task C1 본문 (plan 위치: 라인 ~270)

**TDD 제외** — `AppConfig`는 메타정보, Repository 2종은 단순 `Map` 래퍼. 동작은 후속 통합 테스트(F1)로 검증.

**파일 (3개):**
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/config/AppConfig.java`
  - `@Configuration` + `@Bean Clock systemClock()` (no-arg) + `@Bean IdGenerator idGenerator(Clock clock)` (매개변수 자동 주입)
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/UserRepository.java`
  - `@Repository` + `ConcurrentHashMap<Long, User>` + 자체 `AtomicLong seq`
  - 메서드: `save(String name, Instant now)`, `findById(Long id)`, `count()`
- Create: `sfs-samples/src/main/java/com/choisk/sfs/samples/todo/repository/TodoRepository.java`
  - `@Repository` + `@Autowired IdGenerator idGen` (필드 주입)
  - 메서드: `save(Long ownerId, String title)`, `findById(Long id)`, `findByOwnerId(Long ownerId)` (id 정렬)

**커밋 메시지:** `feat(sfs-samples): AppConfig (@Bean 2종) + UserRepository + TodoRepository`

**검증:** `./gradlew :sfs-samples:compileJava :sfs-samples:test` (132 PASS 유지) + `./gradlew build`

### 위임 프롬프트 패턴 (참고)

오늘 사용한 implementer 위임 프롬프트는 다음 구조:

```
[프로젝트 규약 헤더 — CLAUDE.md TDD 가이드 + 한국어 커밋 + 명시 import + plan 체크박스 갱신 + 편차 기록]

## Task Description
[plan 본문 그대로 복사 — Task C1 섹션 전체]

## Context (Scene-setting)
- 작업 디렉토리: /Users/choeseongang/IdeaProjects/spring-from-scratch
- 현재 브랜치: feat/phase1c-samples (직전 커밋: B1 b881a6f + 4348c66)
- B1까지 완료된 상태. sfs-samples에 모듈 + User/Todo POJO + IdGenerator 존재.
- C1은 IdGenerator를 @Bean으로 등록 + Repository 2종 신설. 후속 D1/D2 Service가 Repository를 @Autowired.

## Before You Begin
[질문 안내]

## Your Job
[6 step 순차 — TDD 제외라 컴파일 + 회귀만]

## Self-Review 체크리스트
[plan 명세 일치, 명시 import, 회귀 132 유지, plan 체크박스 갱신]

## Report Format
[Status + 변경 파일 + 커밋 해시 + self-review]
```

### 재개 시 명령 시퀀스

```bash
# 1. 브랜치/상태 확인
git status                           # working tree clean 확인
git branch --show-current            # feat/phase1c-samples 확인
git log --oneline -5                 # 최근 커밋 확인 (b881a6f, 4348c66, dc01adf, 8dcad02, 467114d)

# 2. 현재 회귀 sanity check (선택)
./gradlew :sfs-samples:test          # IdGeneratorTest 2 PASS 확인

# 3. TaskList 확인
# → C1이 다음 in_progress 대상
```

---

## 4. 컨텍스트 핵심 (재개 시 빠르게 흡수)

### 4.1 Phase 1C 학습 가치 매핑

데모는 *Spring Phase 1의 7 기능을 1:1 매핑*해 시연:

| 기능 | 시연 위치 |
|---|---|
| `@Configuration` + no-arg `@Bean` | `AppConfig#systemClock()` (C1) |
| `@Bean` 매개변수 자동 주입 (Phase 1B-β C1 핵심) | `AppConfig#idGenerator(Clock)` (C1) |
| `@Component` 4 stereotype | `@Repository`/`@Service`/`@Controller` (C1, D1, D2, E1) |
| `@Autowired` 필드 주입 | 7곳 (Repository → Service → Controller 의존 그래프) |
| `@PostConstruct` (BPP:before) | `UserService.seedDefaultUser()` (D1) |
| `@PreDestroy` (LIFO destroy) | `UserController.logShutdown()` (E1) |
| 컨테이너 자동 등록 | `AnnotationConfigApplicationContext(...)` 생성자 한 줄 (F1) |

### 4.2 의도된 설계 결정 (이슈로 오인 금지)

- **`Todo.status`만 non-final** — D2의 `complete`가 변경하므로 의도. 다른 도메인 필드는 `final`.
- **`UserRepository`는 자체 `AtomicLong`, `TodoRepository`는 `IdGenerator` 주입** — 두 ID 발급 패턴을 학습용으로 보여주는 비대칭. 의도.
- **Controller는 thin layer + System.out** — F1 통합 테스트가 콘솔 출력 전체를 박제. 단위 테스트 생략 의도.
- **`EnhanceAbsenceDemo`의 `directCallFormCreatesDistinctInstanceWithoutEnhance` 결과는 `false`** — enhance 부재의 *살아있는 박제*. byte-buddy 도입 시 `true`로 바뀌는 마일스톤.

### 4.3 박제된 보류 항목 (Phase 1B-β plan 하단)

다음 5건은 본 phase 범위 외 (별도 plan 또는 Phase 2 진입 직전 처리):
- 🔵 `(DefaultListableBeanFactory) this` 다운캐스팅 + BPP 처리기의 `DefaultListableBeanFactory` 직접 의존 (Phase 2 직전)
- 🟣 `DisposableBean` 이중 등록 가능성 / `getBeanNamesForType` 반환 타입 과매칭 / `@Primary` 경로 불일치 (다음 plan)

---

## 5. 누적 회귀 카운트 추적

| 시점 | sfs-core | sfs-beans | sfs-context | sfs-samples | 합계 |
|---|---|---|---|---|---|
| Phase 1B-α 완료 | 25 | 48 | 31 | — | 104 |
| Phase 1B-β 완료 (마감) | 25 | 58 | 41 | — | 124 |
| 품질 게이트 후 | 28 | 58 | 44 | — | 130 |
| **현재 (B1 완료)** | 28 | 58 | 44 | **2** | **132** |
| Plan 1C 종료 목표 | 28 | 58 | 44 | ~7 | ~137 |

---

## 6. 학습 메타 (체크포인트의 학습 가치)

오늘의 작업 흐름이 보여준 패턴:
1. **plan 본문 단순화의 부작용** — 박제 블록 7건 + simplify에서 발견한 부분 일원화 누락. plan은 *학습 가독성* vs *실행 정확도*의 트레이드오프를 항상 가짐.
2. **subagent 자기보정** — Sonnet에 grep 가이드를 주면 plan과 실제 코드의 격차를 자동 해소. 위임 프롬프트의 *"plan 본문 + 박제 블록 함께 참조"* 한 줄이 핵심.
3. **품질 게이트 분리** — task별 quality review와 Phase 마감 다관점 review가 부분 중복. 학습 plan 컨텍스트에선 *압축 패턴*(B 옵션)이 합리적 균형.
4. **회귀 카운트의 변화 흐름** — 104 → 124 (+20 1B-β 구현) → 130 (+6 품질 게이트 reflection 일원화) → 132 (B1) → ~137 목표. 회귀 안전망이 *학습 진행의 박제*가 됨.

---

> **다음 세션 첫 메시지 추천:** "B 패턴으로 진행하자" (또는 다른 옵션) → 자동으로 C1 implementer dispatch.
