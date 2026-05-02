package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.core.NoSuchBeanDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G1 회귀 갭 — `DefaultListableBeanFactory.resolveBeanNameByType` + `resolveBeansOfType`이
 * BeanDefinition 등록 빈 + `registerSingleton` 직접 등록 빈 *모두*를 type lookup 대상으로 검색하는지 검증.
 *
 * <p>spec § 3.1, § 6.1 정합. Phase 3 커밋 `6a8493b`로 *기능*은 보강됨 — 본 테스트는 *회귀망*.
 */
class DefaultListableBeanFactoryTypeLookupTest {

    interface Greeter {
        String greet();
    }

    static class Hello implements Greeter {
        @Override public String greet() { return "hello"; }
    }

    /**
     * 시나리오 1: `registerSingleton`만 사용한 직접 등록 빈에 대해
     * `getBean(Greeter.class)`가 단일 매칭 반환.
     */
    @Test
    void resolvesByTypeFromDirectSingletonOnly() {
        var bf = new DefaultListableBeanFactory();
        Hello hello = new Hello();
        bf.registerSingleton("greeter", hello);

        Greeter resolved = bf.getBean(Greeter.class);

        assertThat(resolved).isSameAs(hello);
    }

    /**
     * 시나리오 2: `BeanDefinition`으로 등록된 빈과 `registerSingleton` 직접 등록 빈이
     * *공존*하는 상태에서, *직접 등록 빈*의 타입(여기서는 `ArrayList`)이
     * type lookup으로 매칭됨 — `getBeansOfType`(BeanDefinition만)으로는 못 찾음을 박제.
     */
    @Test
    void resolvesByTypeForDirectSingletonWhileBeanDefinitionExists() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("hello", new BeanDefinition(Hello.class));
        ArrayList<String> directList = new ArrayList<>();
        bf.registerSingleton("directList", directList);

        // BeanDefinition 기반 빈도 정상 lookup
        Hello hello = bf.getBean(Hello.class);
        assertThat(hello).isInstanceOf(Hello.class);

        // 직접 등록 빈도 type lookup 가능 — G1 결함 보강의 본질
        @SuppressWarnings("rawtypes")
        ArrayList resolved = bf.getBean(ArrayList.class);
        assertThat(resolved).isSameAs(directList);
    }

    /**
     * 시나리오 3: 매칭 0건일 때 `NoSuchBeanDefinitionException` throw +
     * 메시지에 타입명(`Greeter.class.getName()`) 포함.
     */
    @Test
    void throwsNoSuchBeanWhenNoMatch() {
        var bf = new DefaultListableBeanFactory();

        assertThatThrownBy(() -> bf.getBean(Greeter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(Greeter.class.getName());
    }
}
