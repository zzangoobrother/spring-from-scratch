package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Autowired;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상속 계층에 선언된 @Autowired 필드가 올바르게 주입되는지 검증한다.
 * <p>현재 AutowiredAnnotationBeanPostProcessor가 getDeclaredFields()만 사용하여
 * 부모 클래스 필드를 silently 무시하는 회귀를 박제하기 위한 테스트.
 */
class AutowiredInheritedFieldTest {

    /** 테스트용 저장소 클래스 */
    static class Repo {}

    /** 부모 클래스: @Autowired 필드를 선언 */
    static class BaseWorker {
        @Autowired
        Repo repo;
    }

    /** 자식 클래스: 부모로부터 @Autowired 필드를 상속 */
    static class Worker extends BaseWorker {}

    @Test
    void autowiredFieldInParentClassShouldBeInjected() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();

        // Repo 빈 등록
        bf.registerBeanDefinition("repo", new BeanDefinition(Repo.class));

        // Worker 빈 등록 (부모 클래스에 @Autowired 필드 존재)
        bf.registerBeanDefinition("worker", new BeanDefinition(Worker.class));

        // AutowiredAnnotationBeanPostProcessor 등록
        bf.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(bf));

        bf.preInstantiateSingletons();

        Worker worker = (Worker) bf.getBean("worker");
        Repo repo = (Repo) bf.getBean("repo");

        // 부모 클래스에 선언된 @Autowired 필드가 주입되어야 함
        assertThat(worker.repo).isNotNull();
        assertThat(worker.repo).isSameAs(repo);
    }
}
