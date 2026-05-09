package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsManyToOne.FetchType;

import java.lang.reflect.Field;

public record RelationMetadata(
        Field field,
        FetchType fetch,
        Class<?> targetEntity,
        String joinColumnName
) { }
