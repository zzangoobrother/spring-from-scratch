# sfs-core

Spring From Scratch의 공통 유틸리티 계층. 다른 모든 모듈이 의존하지만
이 모듈 자체는 ASM 외 외부 의존이 없다.

## 주요 타입
- `Assert` — 인자 검증 유틸 (Spring `Assert` 대응)
- `ClassUtils`, `ReflectionUtils` — 리플렉션 래퍼
- `ClassPathScanner` — 파일 시스템 기반 `.class` 열거
- `AnnotationMetadataReader` — ASM 기반 애노테이션 메타데이터 추출 (클래스 로드 없이)
- `BeansException` sealed 계층 — 모든 컨테이너 예외의 루트

## Spring 원본 매핑

| 이 모듈 | Spring 원본 |
|---|---|
| `Assert` | `org.springframework.util.Assert` |
| `ClassUtils` / `ReflectionUtils` | 동명 (Spring `core` 모듈) |
| `ClassPathScanner` | `ClassPathScanningCandidateComponentProvider`의 단순화 |
| `AnnotationMetadataReader` | `MetadataReader` + ASM `ClassReader` 기반 |
| `BeansException` (sealed) | 동명. sealed로 닫아 분기 누락을 컴파일 타임에 차단 |

## 테스트 실행
```bash
./gradlew :sfs-core:test
```
