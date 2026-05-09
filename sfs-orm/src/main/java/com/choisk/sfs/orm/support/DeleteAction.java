package com.choisk.sfs.orm.support;

public record DeleteAction(Object entity, EntityMetadata metadata) implements EntityAction { }
