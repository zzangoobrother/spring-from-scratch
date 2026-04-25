package com.choisk.sfs.beans;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BeanDefinitionFactoryMethodTest {

    @Test
    void factoryMethodFieldsRoundTrip() {
        // factoryBeanName / factoryMethodName setter → getter 왕복 검증
        BeanDefinition bd = new BeanDefinition(MyConfig.class);
        bd.setFactoryBeanName("myConfig");
        bd.setFactoryMethodName("repo");
        assertThat(bd.getFactoryBeanName()).isEqualTo("myConfig");
        assertThat(bd.getFactoryMethodName()).isEqualTo("repo");
    }

    @Test
    void factoryMethodFieldsDefaultNull() {
        // 초기 상태: 두 필드 모두 null 이어야 함
        BeanDefinition bd = new BeanDefinition(MyConfig.class);
        assertThat(bd.getFactoryBeanName()).isNull();
        assertThat(bd.getFactoryMethodName()).isNull();
    }

    static class MyConfig {}
}
