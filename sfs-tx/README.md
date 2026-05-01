# sfs-tx

Spring From Scratch의 **트랜잭션 추상화 모듈** (Phase 3). Spring 본가 `spring-tx` 대응.

## 의존 관계

- 내부: `sfs-aop` (transitive로 `sfs-context`, `sfs-beans`, `sfs-core`)
- 외부: byte-buddy 1.14 (BPP enhance), H2 2.2.x (test/runtime)

## 핵심 컴포넌트

### 애노테이션 + enum
- `@Transactional` — 메서드/클래스에 트랜잭션 경계 부여
- `Propagation` — REQUIRED, REQUIRES_NEW (5종 미지원, spec § 7 한계)

### 추상 인프라
- `PlatformTransactionManager` — 모든 TM의 공통 인터페이스
- `TransactionStatus` — 현재 트랜잭션 상태 핸들
- `TransactionDefinition` (record) — 트랜잭션 시작 시 의도
- `AbstractPlatformTransactionManager` — propagation 분기 알고리즘 추상 골격

### TM 구현
- `MockTransactionManager` — 콘솔 출력만 (학습 박제)
- `DataSourceTransactionManager` — JDBC `Connection` 기반

### TSM 구현 2종 (의사결정 #11)
- `ThreadLocalTsm` — 메인 (Spring 본가 정합)
- `ScopedValueTsm` — Java 25 idiom 비교 박제

### advice + BPP
- `TransactionInterceptor` — `@Transactional` advice 본체
- `TransactionalBeanPostProcessor` — byte-buddy enhance + SIABPP `getEarlyBeanReference` 훅 활용

### JDBC
- `JdbcTemplate` mini — query/update + transaction-aware connection

## 사용법

```java
@Configuration
public class AppConfig {
    @Bean DataSource dataSource() { /* JdbcDataSource */ }
    @Bean TransactionSynchronizationManager tsm() { return new ThreadLocalTsm(); }
    @Bean PlatformTransactionManager tm(DataSource ds, TransactionSynchronizationManager tsm) {
        return new DataSourceTransactionManager(ds, tsm);
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) {
        return new JdbcTemplate(ds, tsm);
    }
    @Bean TransactionalBeanPostProcessor txBpp() { return new TransactionalBeanPostProcessor(); }
}

@Service
class OrderService {
    @Autowired OrderRepository repo;

    @Transactional
    public void placeOrder(...) { ... }
}
```

## 한계 (본 phase 의도된 단순화)

자세한 내용은 `docs/superpowers/specs/2026-04-30-phase-3-transaction-design.md` § 7 참조.

- propagation 5종 미지원 (NESTED, SUPPORTS 등)
- isolation 동작 검증 없음 (시그니처만)
- `rollbackFor` 동작 미구현 (시그니처만)
- `TransactionTemplate` (programmatic) 미도입
- timeout, readOnly 미지원
- JTA / 분산 트랜잭션 미지원
- HikariCP 등 connection pool 미사용

## 학습 가치

본 phase의 정점은 *추상화 인터페이스의 교환 가능성*:
- 같은 `@Transactional` advice가 두 TM 구현체(Mock/DataSource)와 두 TSM 구현체(ThreadLocal/ScopedValue)에서 동일하게 작동.
- ScopedValueTsm의 *immutable 제약*이 Spring이 *왜 ThreadLocal을 쓰는가*를 직접 박제.
- Phase 2B AOP 인프라(BPP/byte-buddy/advice 패턴)가 *transaction에서 자연 회수*.
- Phase 1A 3-level cache의 SIABPP 훅이 *처음 의미 있게 사용*됨 (A-2 흡수).

## 실행

```bash
./gradlew :sfs-tx:test
./gradlew :sfs-samples:test
./gradlew build
```
