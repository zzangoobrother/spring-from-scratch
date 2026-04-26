package com.choisk.sfs.context.support;

import com.choisk.sfs.beans.ConfigurableBeanFactory;
import com.choisk.sfs.context.annotation.Bean;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * {@code @Configuration} 클래스의 enhance된 서브클래스에서 {@code @Bean} 메서드 호출을 가로채
 * 컨테이너 라우팅을 적용한다.
 *
 * <p>호출 시점에 {@code containsSingleton}으로 완성된 빈이 있는지 확인하고:
 * <ul>
 *   <li>있으면 → 캐시된 빈 반환 (직접 호출 → 컨테이너 라우팅 변형)</li>
 *   <li>없으면 → {@code superCall} 위임 (원본 메서드 본문 실행)</li>
 * </ul>
 *
 * <p>이 비대칭이 enhance의 본질 — 컨테이너 자체의 최초 호출은 통과시키고
 * (cache miss → superCall로 신규 인스턴스 생성), 사용자 코드의 직접 호출은 라우팅됨.
 */
public class BeanMethodInterceptor {

    private final ConfigurableBeanFactory beanFactory;

    public BeanMethodInterceptor(ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * byte-buddy MethodDelegation 진입점. 분기 전략은 클래스 Javadoc 참조.
     *
     * @param args 메서드 인자 (현재 미사용 — 인자형 {@code @Bean} 메서드 확장성 보존 목적)
     */
    @RuntimeType
    public Object intercept(
            @SuperCall Callable<Object> superCall,
            @Origin Method method,
            @AllArguments Object[] args
    ) throws Exception {
        String beanName = resolveBeanName(method);
        if (beanFactory.containsSingleton(beanName)) {
            return beanFactory.getBean(beanName);
        }
        return superCall.call();
    }

    /**
     * 빈 이름 결정 — Spring 관례를 따른다:
     * {@code @Bean(name="")}, {@code @Bean(name={})}, name 미지정은 모두 "이름 미지정"으로 간주하여 메서드명 폴백.
     * (배열 첫 요소만 주 이름으로 사용; 나머지 별칭 등록은 학습 범위 외)
     *
     * <p>동일 규칙이 {@link ConfigurationClassPostProcessor}의 BD 등록 단계에도 독립 적용됨.
     * 두 단계(등록 vs 런타임 라우팅) 분리는 Spring 본가 패턴 — 규칙 변경 시 양쪽 함께 갱신할 것.
     */
    private String resolveBeanName(Method method) {
        Bean ann = method.getAnnotation(Bean.class);
        if (ann != null && ann.name().length > 0 && !ann.name()[0].isEmpty()) {
            return ann.name()[0];
        }
        return method.getName();
    }
}
