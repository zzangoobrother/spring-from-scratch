package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PostConstruct;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상속 계층에 선언된 @PostConstruct 메서드가 올바르게 호출되는지 검증한다.
 * <p>현재 CommonAnnotationBeanPostProcessor가 getDeclaredMethods()만 사용하여
 * 부모 클래스 @PostConstruct 메서드를 silently 무시하는 회귀를 박제하기 위한 테스트.
 */
class PostConstructInheritedMethodTest {

    /** 부모 클래스: @PostConstruct 메서드를 선언 */
    static class BaseService {
        boolean initCalled = false;

        @PostConstruct
        void init() {
            initCalled = true;
        }
    }

    /** 자식 클래스: 부모로부터 @PostConstruct 메서드를 상속 */
    static class MyService extends BaseService {}

    @Test
    void postConstructInParentClassShouldBeCalled() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("myService", new BeanDefinition(MyService.class));

        MyService svc = bf.getBean(MyService.class);

        // 부모 클래스에 선언된 @PostConstruct 메서드가 호출되어야 함
        assertThat(svc.initCalled).isTrue();
    }
}
