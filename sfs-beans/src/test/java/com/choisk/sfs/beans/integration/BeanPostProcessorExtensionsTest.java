package com.choisk.sfs.beans.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.BeanReference;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.InstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.PropertyValue;
import com.choisk.sfs.beans.PropertyValues;
import com.choisk.sfs.beans.SmartInstantiationAwareBeanPostProcessor;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 1A DoD #7 보강: BPP 확장 4종(IABPP 3개 훅 + SIABPP + BFPP) 통합 검증.
 * 기본 BPP는 {@link BeanPostProcessorOrderTest}에서 호출 순서 검증.
 *
 * <p>Plan 1A에는 ApplicationContext가 없으므로 BFPP는 테스트가 직접 호출.
 * Plan 1B의 {@code AbstractApplicationContext.refresh()}에서 자동 호출로 승격 예정.
 */
class BeanPostProcessorExtensionsTest {

    static class Stub {
        String injected;
        public void setInjected(String injected) { this.injected = injected; }
    }

    /** IABPP의 postProcessBeforeInstantiation에서 객체를 직접 반환하면 생성자 호출이 스킵된다. */
    @Test
    void iabppBeforeInstantiationShortCircuitsCreation() {
        var factory = new DefaultListableBeanFactory();
        var preMade = new Stub();
        preMade.injected = "pre-made";
        var afterInitCalled = new AtomicInteger();

        factory.addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
            @Override
            public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
                return beanClass == Stub.class ? preMade : null;
            }
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                afterInitCalled.incrementAndGet();
                return bean;
            }
        });
        factory.registerBeanDefinition("stub",
                new BeanDefinition(Stub.class).addPropertyValue("injected", "from-pvs"));

        var bean = factory.getBean("stub", Stub.class);

        // 단축된 객체가 그대로 반환됐고, populate(PVs)는 적용되지 않음
        assertThat(bean).isSameAs(preMade);
        assertThat(bean.injected).isEqualTo("pre-made");
        // Spring 스펙: 단축 경로에서도 after-initialization은 한 번 호출됨
        assertThat(afterInitCalled).hasValue(1);
    }

    /** IABPP의 postProcessAfterInstantiation=false면 PVs 적용이 스킵된다. */
    @Test
    void iabppAfterInstantiationFalseSkipsPopulation() {
        var factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
            @Override
            public boolean postProcessAfterInstantiation(Object bean, String beanName) {
                return false;
            }
        });
        factory.registerBeanDefinition("stub",
                new BeanDefinition(Stub.class).addPropertyValue("injected", "should-not-apply"));

        var bean = factory.getBean("stub", Stub.class);

        assertThat(bean.injected).isNull();
    }

    /** IABPP의 postProcessProperties가 반환한 새 PVs가 실제로 적용된다. */
    @Test
    void iabppPostProcessPropertiesTransformsPvs() {
        var factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new InstantiationAwareBeanPostProcessor() {
            @Override
            public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
                var transformed = new PropertyValues();
                pvs.all().forEach(pv -> transformed.add(new PropertyValue(pv.name(), "transformed:" + pv.value())));
                return transformed;
            }
        });
        factory.registerBeanDefinition("stub",
                new BeanDefinition(Stub.class).addPropertyValue("injected", "raw"));

        var bean = factory.getBean("stub", Stub.class);

        assertThat(bean.injected).isEqualTo("transformed:raw");
    }

    static class A {
        B b;
        public void setB(B b) { this.b = b; }
    }
    static class B {
        A a;
        public void setA(A a) { this.a = a; }
    }

    /** 순환 참조 시 SIABPP의 getEarlyBeanReference가 호출되고 그 결과가 노출된다. */
    @Test
    void siabppGetEarlyBeanReferenceInvokedOnCircularReference() {
        var factory = new DefaultListableBeanFactory();
        var earlyRefCallCount = new AtomicInteger();

        factory.addBeanPostProcessor(new SmartInstantiationAwareBeanPostProcessor() {
            @Override
            public Object getEarlyBeanReference(Object bean, String beanName) {
                earlyRefCallCount.incrementAndGet();
                // 원본을 그대로 노출 (단일 인스턴스 보장 검증을 위해)
                return bean;
            }
        });
        factory.registerBeanDefinition("a",
                new BeanDefinition(A.class).addPropertyValue("b", new BeanReference("b")));
        factory.registerBeanDefinition("b",
                new BeanDefinition(B.class).addPropertyValue("a", new BeanReference("a")));

        factory.preInstantiateSingletons();
        var a = factory.getBean("a", A.class);
        var b = factory.getBean("b", B.class);

        // 순환 참조에서 한쪽이 조기 노출되어야 하므로 최소 1회 호출
        assertThat(earlyRefCallCount.get()).isGreaterThanOrEqualTo(1);
        // 단일 인스턴스 보장: 노출된 조기 참조가 최종 빈과 동일
        assertThat(a.b).isSameAs(b);
        assertThat(b.a).isSameAs(a);
    }

    /** BFPP가 BeanDefinition을 변경하면 그 변경이 빈 생성에 반영된다. */
    @Test
    void bfppPostProcessBeanFactoryCanMutateBeanDefinitions() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("stub",
                new BeanDefinition(Stub.class).addPropertyValue("injected", "original"));

        // BFPP를 직접 호출 (Plan 1B의 ApplicationContext.refresh가 자동화 예정)
        BeanFactoryPostProcessor bfpp = (ConfigurableListableBeanFactory bf) -> {
            BeanDefinition def = bf.getBeanDefinition("stub");
            // PVs에 같은 이름을 다시 add 하면 마지막 값이 우선 적용됨
            def.addPropertyValue("injected", "mutated-by-bfpp");
        };
        bfpp.postProcessBeanFactory(factory);

        var bean = factory.getBean("stub", Stub.class);
        assertThat(bean.injected).isEqualTo("mutated-by-bfpp");
    }
}
