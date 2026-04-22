package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanReference;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 30: 세터/필드 순환 참조 해결 통합 테스트.
 * 3-level cache 핵심 동작을 검증한다.
 */
class CircularReferenceSetterTest {

    static class ServiceA {
        ServiceB b;
        public void setB(ServiceB b) { this.b = b; }
    }

    static class ServiceB {
        ServiceA a;
        public void setA(ServiceA a) { this.a = a; }
    }

    @Test
    void setterCircularResolved() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("a",
                new BeanDefinition(ServiceA.class).addPropertyValue("b", new BeanReference("b")));
        factory.registerBeanDefinition("b",
                new BeanDefinition(ServiceB.class).addPropertyValue("a", new BeanReference("a")));

        factory.preInstantiateSingletons();

        var a = factory.getBean("a", ServiceA.class);
        var b = factory.getBean("b", ServiceB.class);

        assertThat(a.b).isSameAs(b);
        assertThat(b.a).isSameAs(a);
    }
}
