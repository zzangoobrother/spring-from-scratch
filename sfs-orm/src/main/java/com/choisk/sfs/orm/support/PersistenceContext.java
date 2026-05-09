package com.choisk.sfs.orm.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영속성 컨텍스트 — JPA Session의 핵심 자료구조.
 *
 * 학습 정점 ① write-behind: persist()는 INSERT가 아니라 actionQueue에 쌓는다.
 * 학습 정점 ② 1 entity = 1 instance: identityMap이 단일 SoT — 같은 PK는 항상 같은 인스턴스를 반환.
 */
public class PersistenceContext {

    // 학습 정점 ②: entityClass + PK → 인스턴스 1:1 보장 (1st-level cache)
    private final Map<EntityKey, Object> identityMap = new LinkedHashMap<>();
    private final Map<EntityKey, Object[]> snapshots = new HashMap<>();
    // 학습 정점 ①: flush 전까지 변경은 큐에만 쌓임 — INSERT/DELETE/UPDATE 즉시 실행 아님
    private final List<EntityAction> actionQueue = new ArrayList<>();
    private boolean closed = false;

    public void putEntity(EntityKey key, Object entity) {
        ensureOpen();
        identityMap.put(key, entity);
    }

    public Object getEntity(EntityKey key) {
        if (closed) return null;
        return identityMap.get(key);
    }

    public boolean contains(EntityKey key) {
        return !closed && identityMap.containsKey(key);
    }

    public void putSnapshot(EntityKey key, Object[] values) {
        ensureOpen();
        snapshots.put(key, values);
    }

    public Object[] getSnapshot(EntityKey key) {
        return snapshots.get(key);
    }

    public void enqueueAction(EntityAction action) {
        ensureOpen();
        actionQueue.add(action);
    }

    public List<EntityAction> actionQueue() {
        return Collections.unmodifiableList(actionQueue);
    }

    public Map<EntityKey, Object> identityMap() {
        return Collections.unmodifiableMap(identityMap);
    }

    public void clearActionQueue() {
        actionQueue.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        identityMap.clear();
        snapshots.clear();
        actionQueue.clear();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PersistenceContext is closed");
    }
}
