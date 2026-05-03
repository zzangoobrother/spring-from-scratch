package com.choisk.sfs.beans.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 회귀 갭 — `AbstractAutowireCapableBeanFactory.doCreateBean`의 factoryMethod 분기가
 * *3차 캐시 등록 + populateBean + initializeBean* 풀 사이클을 거치는지 검증.
 *
 * <p>spec § 3.2, § 6.1 정합. Phase 3 커밋 `a74a1f6`로 *기능*은 보강됨 — 본 테스트는 *회귀망*.
 *
 * <p>핵심: factoryMethod 빈도 *생성자 분기와 동일하게* 순환 의존 시 early reference 활용 가능해야 함.
 */
class FactoryMethodEarlyReferenceTest {

    /** 팩토리 빈 — `@Configuration` 빈을 흉내냄. factoryMethod 호출로 Service/Helper 생성. */
    public static class Factory {
        public Service service() { return new Service(); }
        public Helper helper() { return new Helper(); }
    }

    public static class Service {
        public Helper helper;   // populateBean으로 PropertyValues 주입
    }

    public static class Helper {
        public Service service; // populateBean으로 PropertyValues 주입 (순환)
    }

    /**
     * 시나리오 1: factoryMethod 빈의 정상 인스턴스화 — factoryBean + factoryMethodName 셋업으로
     * 빈이 생성되고 1차 캐시에 들어감.
     */
    @Test
    void factoryMethodBeanInstantiates() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("factory", new BeanDefinition(Factory.class));

        BeanDefinition serviceBd = new BeanDefinition(Service.class)
                .setFactoryBeanName("factory")
                .setFactoryMethodName("service");
        bf.registerBeanDefinition("service", serviceBd);

        Service service = (Service) bf.getBean("service");

        assertThat(service).isNotNull();
        // 같은 호출 → 1차 캐시 히트 → 동일 인스턴스
        assertThat(bf.getBean("service")).isSameAs(service);
    }

    /**
     * 시나리오 2: factoryMethod 빈에 `propertyValues`로 의존성 지정 시
     * `populateBean`이 호출되어 필드가 주입됨.
     */
    @Test
    void factoryMethodBeanGetsPopulated() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("factory", new BeanDefinition(Factory.class));
        bf.registerBeanDefinition("helper", new BeanDefinition(Helper.class));  // 생성자 분기

        BeanDefinition serviceBd = new BeanDefinition(Service.class)
                .setFactoryBeanName("factory")
                .setFactoryMethodName("service")
                .addPropertyValue("helper", new BeanReference("helper"));
        bf.registerBeanDefinition("service", serviceBd);

        Service service = (Service) bf.getBean("service");

        assertThat(service.helper).isNotNull();   // populateBean이 적용되지 않았으면 null
    }

    /**
     * 시나리오 3 (정점): 두 factoryMethod 빈끼리 순환 의존 시
     * *3차 캐시 + early reference*로 같은 인스턴스 반환.
     *
     * <p>이 시나리오가 PASS하려면 factoryMethod 분기가 `registerSingletonFactory`를 호출해야 함
     * (Phase 3 커밋 `a74a1f6`의 본질).
     *
     * <p>검증 경로 박제: {@code service}와 {@code helper} 모두
     * {@code setFactoryBeanName("factory")} + {@code setFactoryMethodName(...)}이 설정되어
     * 생성자 분기가 아닌 factoryMethod 분기를 탄다.
     * {@code factory} 빈은 생성자 분기를 타지만 {@code service}/{@code helper}의
     * 순환 해소 경로(3차 캐시)에 관여하지 않는다.
     */
    @Test
    void factoryMethodBeansSupportCircularDependency() {
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("factory", new BeanDefinition(Factory.class));

        BeanDefinition serviceBd = new BeanDefinition(Service.class)
                .setFactoryBeanName("factory")
                .setFactoryMethodName("service")
                .addPropertyValue("helper", new BeanReference("helper"));
        bf.registerBeanDefinition("service", serviceBd);

        BeanDefinition helperBd = new BeanDefinition(Helper.class)
                .setFactoryBeanName("factory")
                .setFactoryMethodName("helper")
                .addPropertyValue("service", new BeanReference("service"));
        bf.registerBeanDefinition("helper", helperBd);

        Service service = (Service) bf.getBean("service");

        assertThat(service.helper).isNotNull();
        assertThat(service.helper.service).isSameAs(service);  // early reference 동일 인스턴스 보장
    }
}
