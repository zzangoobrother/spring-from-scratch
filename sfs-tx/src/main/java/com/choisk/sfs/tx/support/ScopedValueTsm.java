package com.choisk.sfs.tx.support;

import java.util.HashMap;
import java.util.Map;

/**
 * Java 25 {@link ScopedValue} (JEP 506) 기반 TSM. {@link ThreadLocalTsm}과 *동일 인터페이스*이지만
 * scope 모델이 본질적으로 다름.
 *
 * <h3>박제 의도 (spec § 4.5)</h3>
 * <ul>
 *   <li>ScopedValue 자체는 *immutable* — bind/unbind 같은 추가 등록은 직접 불가</li>
 *   <li>우회 가변 영역 패턴: {@code ScopedValue<Map<Object, Object>>} — 외곽 ScopedValue는 immutable이지만 내부 Map은 가변</li>
 *   <li>nested scope는 자동 복원 — {@code where(...).run(...)} 종료 시 outer scope로 자동 복귀</li>
 *   <li>{@code transaction interceptor}가 채택하기엔 람다 스코프 강제로 부적합 — 이게 *왜 Spring이 ThreadLocal을 쓰는가*의 박제</li>
 * </ul>
 *
 * <p>{@link #bindResource}/{@link #getResource}/{@link #unbindResource}는 *현재 scope 안에서*만 의미가 있음.
 * scope 밖에서 호출 시 {@link IllegalStateException}.
 *
 * @see ThreadLocalTsm
 */
public class ScopedValueTsm implements TransactionSynchronizationManager {

    private static final ScopedValue<Map<Object, Object>> SLOT = ScopedValue.newInstance();

    /**
     * 새 scope를 열고 람다를 실행한다. 람다 안에서 {@link #bindResource} 등이 동작.
     * 람다 종료 시 scope 자동 복원.
     */
    public void runInScope(Runnable r) {
        ScopedValue.where(SLOT, new HashMap<>()).run(r);
    }

    @Override
    public void bindResource(Object key, Object value) {
        currentMap().put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        if (!SLOT.isBound()) return null;
        return SLOT.get().get(key);
    }

    @Override
    public Object unbindResource(Object key) {
        return currentMap().remove(key);
    }

    @Override
    public void clearAll() {
        // ScopedValue는 외부 명시적 clear 불가 — scope 종료 시 자동
        if (SLOT.isBound()) {
            SLOT.get().clear();
        }
    }

    private Map<Object, Object> currentMap() {
        if (!SLOT.isBound()) {
            throw new IllegalStateException("not in scope — call runInScope first");
        }
        return SLOT.get();
    }
}
