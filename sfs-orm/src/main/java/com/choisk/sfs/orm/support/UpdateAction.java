package com.choisk.sfs.orm.support;

import java.util.BitSet;

public record UpdateAction(Object entity, EntityMetadata metadata, BitSet dirtyColumns) implements EntityAction { }
