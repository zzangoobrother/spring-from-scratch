package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.ComponentScan;
import com.choisk.sfs.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;

/**
 * @Configuration 클래스의 @Bean 메서드를 스캔해 factoryMethod BeanDefinition으로 등록하고,
 * proxyBeanMethods=true인 @Configuration 클래스는 byte-buddy로 enhance한다.
 *
 * <p>Phase 2A에서 enhance 동작 추가 — Phase 1B-β의 단순판은 enhance 없이 매개변수 라우팅만 지원했음.
 *
 * <p>등록 규칙 (변경 없음):
 * <ul>
 *   <li>@Bean(name=...) 값이 있으면 첫 번째 값을 빈 이름으로 사용</li>
 *   <li>name이 비어있으면 메서드명을 빈 이름으로 사용</li>
 * </ul>
 */
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // @ComponentScan으로 신규 BD를 추가 — 후속 단계가 그 BD도 처리하도록 최우선
        processComponentScans(bf);

        // @Bean 메서드 → factoryMethod BD 등록
        registerBeanMethodsForAllConfigurations(bf);

        // proxyBeanMethods=true인 @Configuration BD의 beanClass를 enhance 서브클래스로 교체
        enhanceConfigurationClasses(bf);
    }

    private void processComponentScans(ConfigurableListableBeanFactory bf) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(bf);
        // 스냅샷 — 스캔이 BD를 추가할 수 있으므로 동시 변경 방지
        String[] definitionNames = bf.getBeanDefinitionNames();

        for (String configName : definitionNames) {
            BeanDefinition bd = bf.getBeanDefinition(configName);
            if (bd.getBeanClass() == null) continue;

            ComponentScan scan = bd.getBeanClass().getAnnotation(ComponentScan.class);
            if (scan == null) continue;

            String[] basePackages = mergePackages(scan.basePackages(), scan.value());
            if (basePackages.length == 0) {
                // 학습 범위: 명시 지정만 지원 — 빈 배열은 no-op
                continue;
            }
            scanner.scan(basePackages);
        }
    }

    private String[] mergePackages(String[] basePackages, String[] value) {
        // 두 배열을 합쳐 빈 문자열 제거 — value()와 basePackages()가 동의어이므로 합집합 처리
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String p : basePackages) if (p != null && !p.isEmpty()) result.add(p);
        for (String p : value) if (p != null && !p.isEmpty()) result.add(p);
        return result.toArray(new String[0]);
    }

    private void registerBeanMethodsForAllConfigurations(ConfigurableListableBeanFactory bf) {
        // registerBeanDefinition 호출이 컬렉션 변경을 일으킬 수 있으므로 스냅샷 배열로 순회
        String[] definitionNames = bf.getBeanDefinitionNames();
        for (String configName : definitionNames) {
            BeanDefinition bd = bf.getBeanDefinition(configName);
            if (bd.getBeanClass() == null) continue;
            if (!bd.getBeanClass().isAnnotationPresent(Configuration.class)) continue;

            for (Method m : bd.getBeanClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) continue;

                Bean beanAnno = m.getAnnotation(Bean.class);
                String[] names = beanAnno.name();
                String beanName = (names.length > 0 && !names[0].isEmpty()) ? names[0] : m.getName();

                BeanDefinition beanBd = new BeanDefinition(m.getReturnType());
                beanBd.setFactoryBeanName(configName);
                beanBd.setFactoryMethodName(m.getName());

                bf.registerBeanDefinition(beanName, beanBd);
            }
        }
    }

    private void enhanceConfigurationClasses(ConfigurableListableBeanFactory bf) {
        ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(bf);
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            Class<?> beanClass = bd.getBeanClass();
            if (beanClass == null) continue;

            Configuration cfg = beanClass.getAnnotation(Configuration.class);
            if (cfg == null) continue;
            if (!cfg.proxyBeanMethods()) continue;

            // proxyBeanMethods=true — enhance 서브클래스로 beanClass 교체
            // bf.getBeanDefinition()이 등록된 BD 인스턴스를 그대로 반환하므로 setBeanClass가 직접 반영됨 (복사/재등록 불필요)
            Class<?> enhanced = enhancer.enhance(beanClass);
            bd.setBeanClass(enhanced);
        }
    }
}
