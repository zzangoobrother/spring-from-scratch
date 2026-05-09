package com.choisk.sfs.orm.support;

public record InsertAction(Object entity, EntityMetadata metadata) implements EntityAction { }
