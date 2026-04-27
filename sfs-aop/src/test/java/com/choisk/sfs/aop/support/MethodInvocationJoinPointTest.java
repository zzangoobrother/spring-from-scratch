package com.choisk.sfs.aop.support;

import org.junit.jupiter.api.Test;

import com.choisk.sfs.aop.support.ThrowingCallable;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodInvocationJoinPointTest {

    static class Target {
        public String greet(String name) { return "hello " + name; }
    }

    @Test
    void proceedDelegatesToInnerCallable() throws Throwable {
        Target target = new Target();
        Method method = Target.class.getMethod("greet", String.class);
        Object[] args = {"world"};
        ThrowingCallable innerCall = () -> "intercepted: " + args[0];

        ProceedingJoinPoint pjp = new MethodInvocationJoinPoint(target, method, args, innerCall);

        assertThat(pjp.getTarget()).isSameAs(target);
        assertThat(pjp.getMethod()).isEqualTo(method);
        assertThat(pjp.getArgs()).isEqualTo(args);
        assertThat(pjp.proceed()).isEqualTo("intercepted: world");
    }

    @Test
    void proceedThrowsWhenInnerCallableIsNull() {
        Target target = new Target();
        Method method = target.getClass().getDeclaredMethods()[0];

        MethodInvocationJoinPoint jp = new MethodInvocationJoinPoint(target, method, new Object[0], null);

        assertThatThrownBy(jp::proceed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proceed()");
    }
}
