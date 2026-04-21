package com.choisk.sfs.beans;

/**
 * "빈을 만드는 빈". new로 만들 수 없는 객체를 컨테이너에 등록하는 공식 통로.
 * <p>Spring 원본: {@code org.springframework.beans.factory.FactoryBean<T>}.
 *
 * <p>사용 규칙:
 * <ul>
 *   <li>{@code getBean("myFactory")} → {@code getObject()} 호출 결과 반환</li>
 *   <li>{@code getBean("&myFactory")} → FactoryBean 자신 반환</li>
 *   <li>{@code isSingleton() == true}면 getObject() 결과도 캐시됨</li>
 * </ul>
 */
public interface FactoryBean<T> {

    T getObject() throws Exception;

    Class<?> getObjectType();

    default boolean isSingleton() { return true; }
}
