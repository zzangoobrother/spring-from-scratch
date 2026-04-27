package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanPostProcessor;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AspectEnhancingBeanPostProcessorTest {

    @Aspect
    public static class TestAspect {
        @Around(Loggable.class)
        public Object passthrough(ProceedingJoinPoint pjp) throws Throwable { return pjp.proceed(); }
    }

    public static class TargetWithLoggable {
        public String name = "original";

        @Loggable
        public String greet() { return "hello " + name; }
    }

    public static class PlainBean {
        public String value = "x";
    }

    static final class FinalClass {
        @Loggable
        public void noop() {}
    }

    @Loggable
    public static class FinalFieldBean {
        public final String fixed = "cannot copy";
    }

    public static class AnotherBpp implements BeanPostProcessor {}

    private DefaultListableBeanFactory beanFactory;
    private AspectEnhancingBeanPostProcessor bpp;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        // preRegisterAspects가 setBeanFactory 시점에 BD를 순회하므로, BD를 먼저 등록 후 setBeanFactory 호출
        beanFactory.registerBeanDefinition("testAspect", new BeanDefinition(TestAspect.class));
        bpp = new AspectEnhancingBeanPostProcessor();
        bpp.setBeanFactory(beanFactory);
    }

    @Test
    void aspectBeanRegistersAdvicesAndReturnsOriginal() {
        TestAspect aspect = new TestAspect();
        Object result = bpp.postProcessAfterInitialization(aspect, "testAspect");

        assertThat(result).isSameAs(aspect);  // enhance 안 됨
        // 후속 빈 매칭에서 advice가 적용되는지 다음 테스트로 검증
    }

    @Test
    void matchingBeanIsEnhancedWithFieldsCopied() {
        // setUp에서 BD 등록 + setBeanFactory로 preRegisterAspects 완료
        beanFactory.registerSingleton("testAspect", new TestAspect());

        TargetWithLoggable original = new TargetWithLoggable();
        original.name = "copied";
        Object result = bpp.postProcessAfterInitialization(original, "target");

        assertThat(result).isNotSameAs(original);
        assertThat(result.getClass()).isNotEqualTo(TargetWithLoggable.class);
        assertThat(TargetWithLoggable.class.isAssignableFrom(result.getClass())).isTrue();
        assertThat(((TargetWithLoggable) result).name).isEqualTo("copied");  // 필드 복사 확인
    }

    @Test
    void nonMatchingBeanIsReturnedUnchanged() {
        PlainBean plain = new PlainBean();
        Object result = bpp.postProcessAfterInitialization(plain, "plain");
        assertThat(result).isSameAs(plain);
    }

    @Test
    void beanPostProcessorBeanIsNotEnhanced() {
        AnotherBpp anotherBpp = new AnotherBpp();
        Object result = bpp.postProcessAfterInitialization(anotherBpp, "anotherBpp");
        assertThat(result).isSameAs(anotherBpp);  // BPP는 enhance 안 됨
    }

    @Test
    void finalClassThrowsClearError() {
        // setUp에서 BD 등록 + setBeanFactory로 preRegisterAspects 완료
        assertThatThrownBy(() -> bpp.postProcessAfterInitialization(new FinalClass(), "finalBean"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final");
    }

    @Test
    void finalFieldThrowsClearError() {
        // setUp에서 BD 등록 + setBeanFactory로 preRegisterAspects 완료
        assertThatThrownBy(() -> bpp.postProcessAfterInitialization(new FinalFieldBean(), "finalFieldBean"))
                .isInstanceOf(IllegalStateException.class)
                // 메시지 형태가 바뀌면 테스트도 깨지도록 구체적 단언
                .hasMessageContaining("Cannot copy final field fixed")
                .hasMessageContaining("FinalFieldBean");
    }
}
