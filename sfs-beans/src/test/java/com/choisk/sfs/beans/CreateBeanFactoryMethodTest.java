package com.choisk.sfs.beans;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task C1: doCreateBean의 factoryMethod 분기 + 매개변수 자동 주입 검증.
 * <ul>
 *   <li>no-arg factoryMethod: greeting() → "hello"</li>
 *   <li>인자 있는 factoryMethod: describe(Repo r) → resolveDependency로 Repo 주입 후 "repo-tag=repo" 반환</li>
 * </ul>
 */
class CreateBeanFactoryMethodTest {

    static class Repo {
        String tag = "repo";
    }

    static class MyConfig {
        public String greeting() { return "hello"; }
        public Repo repo() { return new Repo(); }
        public String describe(Repo r) { return "repo-tag=" + r.tag; }
    }

    @Test
    void factoryMethodNoArgCreatesBean() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("myConfig", new BeanDefinition(MyConfig.class));

        BeanDefinition bd = new BeanDefinition(String.class);
        bd.setFactoryBeanName("myConfig");
        bd.setFactoryMethodName("greeting");
        bf.registerBeanDefinition("greeting", bd);

        assertThat(bf.getBean("greeting")).isEqualTo("hello");
    }

    @Test
    void factoryMethodWithArgsResolvesDependencies() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("myConfig", new BeanDefinition(MyConfig.class));

        // repo 빈 등록 (factoryMethod로)
        BeanDefinition repoBd = new BeanDefinition(Repo.class);
        repoBd.setFactoryBeanName("myConfig");
        repoBd.setFactoryMethodName("repo");
        bf.registerBeanDefinition("repo", repoBd);

        // describe 빈 등록 (Repo를 매개변수로 받음)
        BeanDefinition descBd = new BeanDefinition(String.class);
        descBd.setFactoryBeanName("myConfig");
        descBd.setFactoryMethodName("describe");
        bf.registerBeanDefinition("describe", descBd);

        assertThat(bf.getBean("describe")).isEqualTo("repo-tag=repo");
    }
}
