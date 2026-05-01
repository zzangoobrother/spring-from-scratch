package com.choisk.sfs.tx.support;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link ThreadLocal} 기반 TSM. Spring 본가 {@code TransactionSynchronizationManager} 동일 패턴.
 *
 * <p>가변 Map을 ThreadLocal에 보관 — synchronization 추가 등록 자연 가능. {@link ScopedValueTsm}의
 * immutable 제약과 대조되는 박제.
 */
public class ThreadLocalTsm implements TransactionSynchronizationManager {

    private final ThreadLocal<Map<Object, Object>> resources = ThreadLocal.withInitial(HashMap::new);

    @Override
    public void bindResource(Object key, Object value) {
        resources.get().put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        return resources.get().get(key);
    }

    @Override
    public Object unbindResource(Object key) {
        return resources.get().remove(key);
    }

    @Override
    public void clearAll() {
        resources.remove();
    }
}
