package com.choisk.sfs.beans;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * doCreateBean의 factoryMethod 분기 + 매개변수 자동 주입 검증.
 * <ul>
 *   <li>no-arg factoryMethod: greeting() → "hello"</li>
 *   <li>인자 있는 factoryMethod: describe(Repo r) → resolveDependency로 Repo 주입 후 "repo-tag=repo" 반환</li>
 *   <li>factory method 경로의 BeanFactoryAware 콜백 호출 검증 (T3 안전망)</li>
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

    static class AwareBean implements BeanFactoryAware {
        BeanFactory captured;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.captured = beanFactory;
        }
    }

    static class AwareBeanConfig {
        public AwareBean createAware() { return new AwareBean(); }
    }

    /**
     * factory method 경로(doCreateBean)도 생성자 경로와 동일하게 initializeBean이 호출되어
     * BeanFactoryAware.setBeanFactory 콜백을 트리거해야 한다 (두 경로의 라이프사이클 동등성).
     */
    @Test
    void factoryMethodPathInvokesBeanFactoryAware() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("awareConfig", new BeanDefinition(AwareBeanConfig.class));

        BeanDefinition awareBd = new BeanDefinition(AwareBean.class);
        awareBd.setFactoryBeanName("awareConfig");
        awareBd.setFactoryMethodName("createAware");
        bf.registerBeanDefinition("aware", awareBd);

        AwareBean result = (AwareBean) bf.getBean("aware");

        assertThat(result.captured)
                .as("factory method 경로에서 BeanFactoryAware.setBeanFactory가 호출되어야 함")
                .isSameAs(bf);
    }
}
