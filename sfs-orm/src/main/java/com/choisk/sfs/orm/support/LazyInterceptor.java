package com.choisk.sfs.orm.support;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import java.lang.reflect.Method;

/**
 * byte-buddy 인터셉터 stub — I2에서 본격 구현.
 * <p>
 * 시그니처: {@code @RuntimeType Object intercept(@Origin Method, @AllArguments Object[])}
 * byte-buddy {@code MethodDelegation.toField(INTERCEPTOR_FIELD)} 와 호환되는 형태.
 */
public class LazyInterceptor {

    private final Class<?> targetClass;
    private final Object pk;
    private final PersistenceContext context;
    private Object target;

    public LazyInterceptor(Class<?> targetClass, Object pk, PersistenceContext context) {
        this.targetClass = targetClass;
        this.pk = pk;
        this.context = context;
    }

    public Object target() {
        return target;
    }

    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args) throws Throwable {
        throw new UnsupportedOperationException("I2: LazyInterceptor 본격 구현 예정");
    }
}
