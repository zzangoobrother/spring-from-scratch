package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommonAnnotationBeanPostProcessor의 @PreDestroy 등록 + LIFO 순서 호출을 검증한다.
 */
class PreDestroyOrderTest {

    /** 정적 리스트: 테스트 간 공유를 방지하기 위해 각 테스트 시작 시 clear() 호출 */
    static List<String> destroyOrder = new ArrayList<>();

    static class A {
        @PreDestroy
        void cleanup() {
            destroyOrder.add("A");
        }
    }

    static class B {
        @PreDestroy
        void cleanup() {
            destroyOrder.add("B");
        }
    }

    @Test
    void preDestroyCalledInLifoOrder() {
        destroyOrder.clear();
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("a", new BeanDefinition(A.class));
        bf.registerBeanDefinition("b", new BeanDefinition(B.class));

        bf.preInstantiateSingletons();
        bf.destroySingletons();

        // 등록 순서: a → b, 소멸 순서(LIFO): b → a → ["B", "A"]
        assertThat(destroyOrder).containsExactly("B", "A");
    }
}
