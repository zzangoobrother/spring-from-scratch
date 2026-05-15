package com.choisk.sfs.tx.support;

import com.choisk.sfs.tx.PlatformTransactionManager;
import com.choisk.sfs.tx.TransactionDefinition;
import com.choisk.sfs.tx.TransactionStatus;

import java.util.function.Supplier;

/**
 * 트랜잭션 경계 람다 헬퍼.
 *
 * <p>Spring 본가 {@code TransactionTemplate}의 학습 박제 버전.
 * AOP 프록시({@link TransactionInterceptor}) 없이 {@link PlatformTransactionManager}를
 * 직접 사용하는 통합 테스트나 유틸리티 코드에서 트랜잭션 경계를 선언하는 데 사용한다.
 *
 * <p>동작:
 * <ol>
 *   <li>{@code tm.getTransaction(def)} — 트랜잭션 시작 (flag=true는 TM 책임)</li>
 *   <li>{@code action.get()} — 사용자 코드 실행</li>
 *   <li>정상 반환 시 {@code tm.commit(status)} + 결과 반환</li>
 *   <li>{@link RuntimeException}/{@link Error} 발생 시 {@code tm.rollback(status)} 후 rethrow</li>
 * </ol>
 *
 * <p>커밋 중 동기화 콜백({@code beforeCommit}, {@code afterCompletion}) 실행은
 * TM 내부({@link DataSourceTransactionManager})가 담당 — TransactionTemplate은 단순 경계 선언만.
 */
public final class TransactionTemplate {

    private TransactionTemplate() {
        // 정적 유틸리티 클래스 — 인스턴스화 금지
    }

    /**
     * 기본 REQUIRED propagation 트랜잭션 내에서 {@code action}을 실행하고 결과를 반환한다.
     *
     * @param tm     사용할 {@link PlatformTransactionManager}
     * @param action 트랜잭션 내에서 실행할 작업. null 반환 가능.
     * @param <T>    반환 타입
     * @return {@code action.get()}의 반환값
     * @throws RuntimeException action이 RuntimeException을 던지면 rollback 후 rethrow
     * @throws Error            action이 Error를 던지면 rollback 후 rethrow
     */
    public static <T> T execute(PlatformTransactionManager tm, Supplier<T> action) {
        TransactionStatus status = tm.getTransaction(TransactionDefinition.required());
        try {
            T result = action.get();
            tm.commit(status);
            return result;
        } catch (RuntimeException | Error t) {
            tm.rollback(status);
            throw t;
        }
    }
}
