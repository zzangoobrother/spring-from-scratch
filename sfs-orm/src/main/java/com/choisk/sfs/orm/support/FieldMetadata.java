package com.choisk.sfs.orm.support;

import java.lang.reflect.Field;

public record FieldMetadata(Field field, String columnName, Class<?> javaType) { }
