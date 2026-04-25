package com.choisk.sfs.context.integration;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.PreDestroy;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 종료 시연 통합 테스트.
 *
 * <p>처리기 3종(ConfigurationClassPostProcessor, AutowiredAnnotationBeanPostProcessor,
 * CommonAnnotationBeanPostProcessor)이 동시에 동작하는 풀 시나리오를 검증하며,
 * inter-bean reference의 두 형태(매개변수 라우팅 ✅ / 직접 호출 ❌)를 명시적으로 박제한다.
 */
class Phase1IntegrationTest {

    /** 테스트용 저장소 도메인 클래스 */
    static class Repo {
        /** 간단한 동작 확인용 메서드 */
        String greet() { return "data"; }
    }

    /** Repo에 의존하는 서비스 도메인 클래스 */
    static class Service {
        final Repo repo;

        Service(Repo repo) { this.repo = repo; }
    }

    /**
     * 매개변수 형태 inter-bean reference 시나리오용 @Configuration.
     * service(Repo repo) 형태 — 컨테이너가 resolveDependency로 동일 싱글톤 라우팅.
     */
    @Configuration
    static class AppConfigArgInjection {
        @Bean
        public Repo repo() { return new Repo(); }

        /**
         * 매개변수로 의존성을 받으므로 컨테이너가 resolveDependency를 통해 같은 Repo 싱글톤을 주입.
         * enhance 없이도 inter-bean reference의 가장 흔한 패턴이 동작한다.
         */
        @Bean
        public Service service(Repo repo) { return new Service(repo); }
    }

    /**
     * 직접 호출 형태 inter-bean reference 시나리오용 @Configuration.
     * service() 본문에서 repo()를 직접 호출 — enhance 없으므로 매번 새 Repo 인스턴스.
     */
    @Configuration
    static class AppConfigDirectCall {
        @Bean
        public Repo repo() { return new Repo(); }

        /**
         * 본문에서 repo()를 직접 호출하면 컨테이너 경유 없이 새 인스턴스가 생성된다.
         * byte-buddy enhance가 없기 때문에 서비스가 갖는 repo와 컨테이너의 repo는 다른 인스턴스.
         */
        @Bean
        public Service service() { return new Service(repo()); }
    }

    /**
     * @Autowired 필드 주입 + @PostConstruct/@PreDestroy 라이프사이클 시연용 컴포넌트.
     */
    @Component
    static class Worker {
        @Autowired
        Service service;

        boolean initCalled = false;
        boolean destroyCalled = false;

        @PostConstruct
        void init() { initCalled = true; }

        @PreDestroy
        void cleanup() { destroyCalled = true; }
    }

    /**
     * 테스트 1: 매개변수 형태 inter-bean reference — 컨테이너가 동일 싱글톤 라우팅 ✅
     *
     * <p>@Bean 메서드가 매개변수로 의존성을 받으면 resolveDependency가 컨테이너에 등록된
     * 동일 Repo 싱글톤을 주입하므로 service.repo와 컨테이너의 Repo 빈이 같은 인스턴스여야 한다.
     */
    @Test
    void argFormBeanReferenceUsesContainerRouting() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(AppConfigArgInjection.class);

        Service service = ctx.getBean(Service.class);
        Repo repo = ctx.getBean(Repo.class);

        assertThat(service.repo)
                .as("매개변수로 받은 Repo는 컨테이너의 동일 싱글톤이어야 함")
                .isSameAs(repo);

        ctx.close();
    }

    /**
     * 테스트 2: 직접 호출 형태 inter-bean reference — enhance 부재로 매번 새 인스턴스 ❌
     *
     * <p>byte-buddy enhance가 없으면 @Bean 메서드 본문의 repo() 직접 호출은 컨테이너를
     * 우회하여 새로운 Repo 인스턴스를 만든다. 이것이 바로 Spring이 byte-buddy를 쓰는 이유.
     * 이 테스트는 enhance 부재 효과를 박제한다 — enhance 도입 시 isSameAs로 변한다.
     */
    @Test
    void directCallFormCreatesDistinctInstanceWithoutEnhance() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(AppConfigDirectCall.class);

        Service service = ctx.getBean(Service.class);
        Repo repo = ctx.getBean(Repo.class);

        assertThat(service.repo)
                .as("enhance가 없으면 service() 본문의 repo() 직접 호출은 컨테이너를 우회하여 새 Repo를 만든다")
                .isNotSameAs(repo);

        ctx.close();
    }

    /**
     * 테스트 3: Phase 1 풀 시나리오.
     *
     * <p>@Configuration + @Bean 매개변수 주입 + @Component @Autowired 필드 주입 +
     * @PostConstruct (open 시) + @PreDestroy (close 시) 모두 동작하는 end-to-end 시연.
     */
    @Test
    void phase1FullScenario() {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(AppConfigArgInjection.class, Worker.class);

        Worker w = ctx.getBean(Worker.class);

        // @Autowired 필드 주입 확인
        assertThat(w.service).isNotNull();

        // 매개변수 라우팅 — service.repo와 컨테이너의 Repo 싱글톤이 같은 인스턴스
        assertThat(w.service.repo).isSameAs(ctx.getBean(Repo.class));

        // 동작 호출 가능 확인
        assertThat(w.service.repo.greet()).isEqualTo("data");

        // @PostConstruct 호출 확인
        assertThat(w.initCalled).as("@PostConstruct must fire").isTrue();

        // close 전에는 @PreDestroy 미호출
        assertThat(w.destroyCalled).as("@PreDestroy not called yet").isFalse();

        ctx.close();

        // close 후 @PreDestroy 호출 확인
        assertThat(w.destroyCalled).as("@PreDestroy fires on close()").isTrue();
    }
}
