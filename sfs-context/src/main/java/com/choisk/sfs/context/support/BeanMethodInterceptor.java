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
     * byte-buddy MethodDelegation 진입점.
     *
     * <p>빈 이름을 결정한 뒤:
     * <ul>
     *   <li>캐시 hit → {@code beanFactory.getBean(beanName)} 반환</li>
     *   <li>캐시 miss → {@code superCall.call()} 위임 (원본 메서드 본문 실행)</li>
     * </ul>
     *
     * @param superCall byte-buddy가 주입하는 원본 메서드 호출 Callable
     * @param method    호출된 메서드 (애노테이션 추출용)
     * @param args      메서드 인자 배열 (현재 사용 안 함, 확장성 보존)
     * @return 캐시된 빈 또는 신규 생성된 빈
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
     * {@code @Bean(name=...)} 값이 있으면 첫 번째 값을 빈 이름으로 사용하고,
     * 없으면 메서드명을 빈 이름으로 사용한다.
     *
     * @param method 대상 메서드
     * @return 결정된 빈 이름
     */
    private String resolveBeanName(Method method) {
        Bean ann = method.getAnnotation(Bean.class);
        if (ann != null && ann.name().length > 0 && !ann.name()[0].isEmpty()) {
            return ann.name()[0];
        }
        return method.getName();
    }
}
