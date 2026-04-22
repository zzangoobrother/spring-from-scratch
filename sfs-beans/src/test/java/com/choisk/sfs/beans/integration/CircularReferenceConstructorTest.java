package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanReference;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.core.BeanCreationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 31: 생성자 순환 참조 예외 통합 테스트.
 * 생성자 주입으로 형성된 순환 참조는 3-level cache로 해결 불가하므로
 * BeanCreationException 또는 IllegalStateException이 발생해야 한다.
 */
class CircularReferenceConstructorTest {

    static class A { A(B b) {} }
    static class B { B(A a) {} }

    @Test
    void constructorCircularThrows() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a",
                new BeanDefinition(A.class).addConstructorArg(new BeanReference("b")));
        factory.registerBeanDefinition("b",
                new BeanDefinition(B.class).addConstructorArg(new BeanReference("a")));

        assertThatThrownBy(factory::preInstantiateSingletons)
                .isInstanceOfAny(BeanCreationException.class, IllegalStateException.class)
                .hasMessageContaining("circular");
    }
}
