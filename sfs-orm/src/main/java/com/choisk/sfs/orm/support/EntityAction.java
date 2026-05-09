package com.choisk.sfs.orm.support;

// write-behind 큐의 원소 타입 — persist()는 INSERT가 아니라 이 큐에 쌓는 것 (학습 정점 ①)
public sealed interface EntityAction permits InsertAction, DeleteAction, UpdateAction {
    Object entity();
    EntityMetadata metadata();
}
