package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import com.choisk.sfs.context.annotation.Autowired;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutowiredFieldInjectionTest {

    /** 테스트용 저장소 클래스 */
    static class Repo {}

    /** @Autowired 필드가 있는 테스트용 워커 클래스 */
    static class Worker {
        @Autowired
        Repo repo;
    }

    @Test
    void autowiredFieldInjectedAfterConstruction() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();

        // Repo 빈 등록
        BeanDefinition repoDef = new BeanDefinition(Repo.class);
        bf.registerBeanDefinition("repo", repoDef);

        // Worker 빈 등록
        BeanDefinition workerDef = new BeanDefinition(Worker.class);
        bf.registerBeanDefinition("worker", workerDef);

        // AutowiredAnnotationBeanPostProcessor 등록
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor(bf);
        bf.addBeanPostProcessor(processor);

        // 모든 싱글톤 사전 인스턴스화
        bf.preInstantiateSingletons();

        // Worker 빈의 repo 필드가 주입되었는지 확인
        Worker worker = (Worker) bf.getBean("worker");
        Repo repo = (Repo) bf.getBean("repo");

        assertThat(worker.repo).isNotNull();
        assertThat(worker.repo).isSameAs(repo);
    }
}
