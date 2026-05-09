package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType;

import java.util.List;

public record EntityMetadata(
        Class<?> entityClass,
        String tableName,
        FieldMetadata idField,
        IdentifierGenerator idGenerator,
        List<FieldMetadata> columns,
        List<RelationMetadata> manyToOnes,
        String insertSql,
        String selectByIdSql,
        String deleteSql
) {
    public boolean hasLazyFields() {
        return manyToOnes.stream().anyMatch(r -> r.fetch() == FetchType.LAZY);
    }
}
