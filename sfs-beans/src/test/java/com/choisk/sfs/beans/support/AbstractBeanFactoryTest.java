package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 24 코드리뷰 발견 사항(#1, #2, #4) 검증.
 * 동시성(#3)은 {@code DefaultSingletonBeanRegistryTest}에서 단독 검증.
 */
class AbstractBeanFactoryTest {

    /** AbstractBeanFactory의 추상 메서드만 최소 구현한 테스트용 서브클래스. */
    private static class TestableBeanFactory extends AbstractBeanFactory {
        private final Map<String, BeanDefinition> defs = new HashMap<>();

        void register(String name, BeanDefinition def) {
            defs.put(name, def);
        }

        @Override
        protected BeanDefinition getBeanDefinition(String beanName) {
            return defs.get(beanName);
        }

        @Override
        protected boolean containsBeanDefinition(String beanName) {
            return defs.containsKey(beanName);
        }

        @Override
        protected String resolveBeanNameByType(Class<?> requiredType) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        protected Object createBean(String beanName, BeanDefinition definition) {
            try {
                return definition.getBeanClass().getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected String[] getBeanDefinitionNames() {
            return defs.keySet().toArray(new String[0]);
        }
    }

    /** #1: getBean(name, null) 호출 시 NPE 대신 IllegalArgumentException. */
    @Test
    void getBeanWithNullRequiredTypeThrowsIllegalArgument() {
        var factory = new TestableBeanFactory();
        factory.registerSingleton("foo", "hello");

        assertThatThrownBy(() -> factory.getBean("foo", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredType");
    }

    /** #2: registerSingleton으로 직접 등록된 빈은 isSingleton=true, isPrototype=false. */
    @Test
    void manuallyRegisteredSingletonReportsAsSingleton() {
        var factory = new TestableBeanFactory();
        factory.registerSingleton("manual", "instance");

        assertThat(factory.containsBean("manual")).isTrue();
        assertThat(factory.isSingleton("manual")).isTrue();
        assertThat(factory.isPrototype("manual")).isFalse();
    }

    /** #4: 오타로 조회 시 등록된 BeanDefinition 이름이 후보로 제안되어야 한다. */
    @Test
    void buildNoSuchBeanMessageIncludesBeanDefinitionCandidates() {
        var factory = new TestableBeanFactory();
        var def = new BeanDefinition(SampleBean.class);
        factory.register("userService", def);

        assertThatThrownBy(() -> factory.getBean("userServce")) // 오타: 'i' 누락
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("userService"); // 후보 제안에 등장해야 함
    }

    public static class SampleBean {
        public SampleBean() {}
    }
}
