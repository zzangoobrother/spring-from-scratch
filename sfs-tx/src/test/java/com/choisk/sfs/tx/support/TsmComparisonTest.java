package com.choisk.sfs.tx.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ThreadLocal과 ScopedValue 두 TSM 구현체의 *동일 동작*과 *결정적 차이* 박제.
 *
 * spec § 4.5의 표를 테스트로 명시화 — 학습 정점 박제.
 */
class TsmComparisonTest {

    @Test
    void bothImplementationsBindAndRetrieveSameValue() {
        Object key = new Object();

        // ThreadLocal
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "v1");
        Object tlValue = tlTsm.getResource(key);
        tlTsm.clearAll();

        // ScopedValue
        ScopedValueTsm svTsm = new ScopedValueTsm();
        Object[] svValueRef = new Object[1];
        svTsm.runInScope(() -> {
            svTsm.bindResource(key, "v1");
            svValueRef[0] = svTsm.getResource(key);
        });

        // 동일 동작
        assertThat(tlValue).isEqualTo("v1");
        assertThat(svValueRef[0]).isEqualTo("v1");
    }

    @Test
    void threadLocalAllowsBindWithoutScope_butScopedValueDoesNot() {
        Object key = new Object();

        // ThreadLocal: scope 없이도 bind 가능 — 가변 ThreadLocal map
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "v1");
        assertThat(tlTsm.getResource(key)).isEqualTo("v1");
        tlTsm.clearAll();

        // ScopedValue: scope 밖에서는 IllegalStateException — immutable 제약 박제
        ScopedValueTsm svTsm = new ScopedValueTsm();
        assertThatThrownBy(() -> svTsm.bindResource(key, "v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopedValueAutoRestoresOuterScope_butThreadLocalRequiresExplicitUnbind() {
        Object key = new Object();

        // ThreadLocal: nested binding은 *덮어쓰기*. unbind 없이는 outer 복원 안 됨
        ThreadLocalTsm tlTsm = new ThreadLocalTsm();
        tlTsm.bindResource(key, "outer");
        tlTsm.bindResource(key, "inner");  // 덮어쓰기
        assertThat(tlTsm.getResource(key)).isEqualTo("inner");  // outer 사라짐
        tlTsm.clearAll();

        // ScopedValue: nested scope는 *자동 격리* + scope 종료 시 outer 자동 복원
        ScopedValueTsm svTsm = new ScopedValueTsm();
        Object[] outerAfterInner = new Object[1];
        svTsm.runInScope(() -> {
            svTsm.bindResource(key, "outer");
            svTsm.runInScope(() -> {
                svTsm.bindResource(key, "inner");
                // inner scope에서는 outer 안 보임
                assertThat(svTsm.getResource(key)).isEqualTo("inner");
            });
            // 자동 복원
            outerAfterInner[0] = svTsm.getResource(key);
        });

        assertThat(outerAfterInner[0]).isEqualTo("outer");
    }
}
