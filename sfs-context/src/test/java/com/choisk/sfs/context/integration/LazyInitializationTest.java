package com.choisk.sfs.context.integration;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LazyInitializationTest {

    // 테스트 간 공유 카운터 — 각 테스트 시작 시 set(0) 필수
    static final AtomicInteger ctorCount = new AtomicInteger(0);

    // eager 빈: preInstantiate 시점에 즉시 생성
    static class Eager { Eager() { ctorCount.incrementAndGet(); } }
    // lazy 빈: 첫 getBean 호출 시에만 생성
    static class LazyOne { LazyOne() { ctorCount.incrementAndGet(); } }

    @Test
    void lazyBeanIsNotInstantiatedAtPreInstantiate() {
        ctorCount.set(0);
        var bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition("eager", new BeanDefinition(Eager.class));

        BeanDefinition lazyDef = new BeanDefinition(LazyOne.class);
        lazyDef.setLazyInit(true);
        bf.registerBeanDefinition("lazyOne", lazyDef);

        // preInstantiateSingletons 는 lazy 빈을 건너뛰어야 한다
        bf.preInstantiateSingletons();
        assertThat(ctorCount.get()).isEqualTo(1);  // eager만 생성

        // 첫 getBean 호출 시 비로소 lazy 빈이 생성된다
        bf.getBean("lazyOne");
        assertThat(ctorCount.get()).isEqualTo(2);  // lazy 생성 후 2
    }
}
