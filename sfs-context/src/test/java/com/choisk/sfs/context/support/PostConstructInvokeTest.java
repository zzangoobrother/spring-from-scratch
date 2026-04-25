package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostConstructInvokeTest {

    /** @PostConstruct 메서드가 있는 테스트용 워커 클래스 */
    static class Worker {
        boolean initCalled = false;

        @PostConstruct
        void init() {
            initCalled = true;
        }
    }

    @Test
    void postConstructInvokedDuringBeanCreation() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("worker", new BeanDefinition(Worker.class));

        Worker w = bf.getBean(Worker.class);

        // @PostConstruct 메서드가 빈 생성 시 호출되었는지 확인
        assertThat(w.initCalled).isTrue();
    }
}
