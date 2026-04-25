package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.BeanDefinition;
import com.choisk.sfs.beans.BeanFactoryPostProcessor;
import com.choisk.sfs.beans.ConfigurableListableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;

import java.lang.reflect.Method;

/**
 * @Configuration 클래스의 @Bean 메서드를 스캔해 factoryMethod BeanDefinition으로 등록하는 처리기.
 * byte-buddy enhance 없이 단순판으로 동작한다 (enhance 없이도 매개변수 형태 inter-bean reference는
 * C1의 resolveDependency 라우팅으로 처리됨).
 *
 * <p>등록 규칙:
 * <ul>
 *   <li>@Bean(name=...) 값이 있으면 첫 번째 값을 빈 이름으로 사용</li>
 *   <li>name이 비어있으면 메서드명을 빈 이름으로 사용</li>
 * </ul>
 *
 */
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        // registerBeanDefinition 호출이 컬렉션 변경을 일으킬 수 있으므로 이름 배열을 복사 후 순회
        String[] definitionNames = bf.getBeanDefinitionNames().clone();

        for (String configName : definitionNames) {
            BeanDefinition bd = bf.getBeanDefinition(configName);
            if (bd.getBeanClass() == null) {
                continue;
            }
            if (!bd.getBeanClass().isAnnotationPresent(Configuration.class)) {
                continue;
            }

            // @Configuration 클래스의 모든 @Bean 메서드 추출 후 BD 등록
            for (Method m : bd.getBeanClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) {
                    continue;
                }

                Bean beanAnno = m.getAnnotation(Bean.class);
                // name()이 비어있으면 메서드명, 아니면 첫 번째 name 값을 빈 이름으로 사용
                String[] names = beanAnno.name();
                String beanName = (names.length > 0 && !names[0].isEmpty()) ? names[0] : m.getName();

                BeanDefinition beanBd = new BeanDefinition(m.getReturnType());
                beanBd.setFactoryBeanName(configName);
                beanBd.setFactoryMethodName(m.getName());

                bf.registerBeanDefinition(beanName, beanBd);
            }
        }
    }
}
