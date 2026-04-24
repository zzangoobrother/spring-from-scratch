package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassPathBeanDefinitionScannerTest {

    @Test
    void scanRegistersComponentsAndMetaAnnotated() {
        // @Component 직접 부착(SimpleService)과 @Service 메타 인식(MetaTaggedService)은 등록,
        // 애노테이션 없는 PlainPojo는 제외 — count == 2 이어야 함.
        var bf = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(bf);

        int count = scanner.scan("com.choisk.sfs.context.samples.basic");

        assertThat(count).isEqualTo(2);
        assertThat(bf.containsBeanDefinition("simpleService")).isTrue();
        assertThat(bf.containsBeanDefinition("metaTaggedService")).isTrue();
        assertThat(bf.containsBeanDefinition("plainPojo")).isFalse();
    }

    @Test
    void scanReturnsZeroForEmptyPackage() {
        // 존재하지 않는 패키지를 스캔하면 빈 목록이 반환되어야 함.
        var bf = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(bf);

        int count = scanner.scan("com.choisk.sfs.context.samples.nonexistent");

        assertThat(count).isEqualTo(0);
    }
}
