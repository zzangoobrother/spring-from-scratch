package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상속 계층에 선언된 @PreDestroy 메서드가 올바르게 호출되는지 검증한다.
 * <p>현재 CommonAnnotationBeanPostProcessor가 getDeclaredMethods()만 사용하여
 * 부모 클래스 @PreDestroy 메서드를 silently 무시하는 회귀를 박제하기 위한 테스트.
 */
class PreDestroyInheritedMethodTest {

    /** 정적 리스트: 각 테스트 시작 시 clear() 호출 */
    static List<String> destroyLog = new ArrayList<>();

    /** 부모 클래스: @PreDestroy 메서드를 선언 */
    static class BaseResource {
        @PreDestroy
        void cleanup() {
            destroyLog.add("BaseResource.cleanup");
        }
    }

    /** 자식 클래스: 부모로부터 @PreDestroy 메서드를 상속 */
    static class MyResource extends BaseResource {}

    @Test
    void preDestroyInParentClassShouldBeCalled() {
        destroyLog.clear();

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.addBeanPostProcessor(new CommonAnnotationBeanPostProcessor(bf));
        bf.registerBeanDefinition("myResource", new BeanDefinition(MyResource.class));

        bf.preInstantiateSingletons();
        bf.destroySingletons();

        // 부모 클래스에 선언된 @PreDestroy 메서드가 호출되어야 함
        assertThat(destroyLog).containsExactly("BaseResource.cleanup");
    }
}
