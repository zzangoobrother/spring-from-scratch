package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.annotation.SfsId;
import com.choisk.sfs.orm.exception.SfsPersistenceException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LAZY 연관 필드용 byte-buddy 프록시 생성기.
 * <p>
 * 학습 정점 ③ (forward stub): lazy proxy의 identity 보장은 J1에서 통합 검증.
 * <ul>
 *   <li>PK getter는 enhanced 클래스가 hidden field($$lazyPk)에서 직접 반환 — lazy init 회피
 *       (J1에서 getId()와 find() 통합 시 최종 검증)</li>
 *   <li>enhanced 클래스 캐싱은 *클래스 단위*, 인스턴스 단위 아님
 *       — 동일 entity class면 같은 enhanced subclass 재사용</li>
 * </ul>
 */
public class LazyProxyFactory {

    /** byte-buddy 생성 서브클래스에 주입되는 인터셉터 필드명 */
    public static final String INTERCEPTOR_FIELD = "$$lazyInterceptor";
    /** byte-buddy 생성 서브클래스에 주입되는 PK hidden 필드명 */
    public static final String PK_FIELD = "$$lazyPk";

    // 학습 포인트: enhanced 클래스 캐싱은 클래스 단위 — 같은 targetClass면 buildEnhanced 재호출 안 함
    private final Map<Class<?>, Class<?>> enhancedCache = new ConcurrentHashMap<>();

    /**
     * targetClass의 byte-buddy 서브클래스 프록시 인스턴스를 생성한다.
     * <p>
     * PK getter(getId 계열)는 인터셉트에서 제외되어 $$lazyPk 필드와
     * targetClass의 원본 id 필드에서 즉시 반환한다.
     *
     * @param targetClass 프록시 대상 entity 클래스
     * @param pk          지연 로드될 entity의 PK 값
     * @param context     1차 캐시 — 실제 로드 시 identity 보장에 사용 (I2에서 본격 활용)
     * @return targetClass의 서브클래스 인스턴스
     */
    public Object createProxy(Class<?> targetClass, Object pk, PersistenceContext context) {
        Class<?> enhanced = enhancedCache.computeIfAbsent(targetClass, this::buildEnhanced);
        try {
            Constructor<?> ctor = enhanced.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object proxy = ctor.newInstance();

            // 인터셉터 필드 주입 — I2에서 본격 동작
            Field interceptorField = enhanced.getDeclaredField(INTERCEPTOR_FIELD);
            interceptorField.setAccessible(true);
            interceptorField.set(proxy, new LazyInterceptor(targetClass, pk, context));

            // hidden PK 필드 주입 — getId 비-인터셉트 반환용
            Field pkField = enhanced.getDeclaredField(PK_FIELD);
            pkField.setAccessible(true);
            pkField.set(proxy, pk);

            // PK getter가 원본 id 필드에서도 반환할 수 있도록 실제 id 필드도 채움
            Field idField = findIdField(targetClass);
            idField.setAccessible(true);
            idField.set(proxy, pk);

            return proxy;
        } catch (Exception e) {
            throw new SfsPersistenceException("Lazy proxy 생성 실패: " + targetClass.getName(), e);
        }
    }

    /**
     * byte-buddy로 targetClass의 서브클래스를 빌드한다.
     * <p>
     * PK getter({@code getId...} 계열)는 매처에서 제외 — 인터셉터로 위임하지 않음.
     * 나머지 public 메서드는 {@code INTERCEPTOR_FIELD}에 저장된 LazyInterceptor로 위임.
     */
    private Class<?> buildEnhanced(Class<?> targetClass) {
        try {
            Field idField = findIdField(targetClass);
            String pkGetterName = "get" + capitalize(idField.getName());

            // MethodHandles.privateLookupIn: targetClass의 classloader에서 서브클래스를 직접 정의
            // → ByteArrayClassLoader 경유 없이 같은 classloader 공간에 로드되므로 IllegalAccessError 없음
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    targetClass, MethodHandles.lookup());

            return new ByteBuddy()
                    .subclass(targetClass)
                    .defineField(INTERCEPTOR_FIELD, LazyInterceptor.class, Visibility.PRIVATE)
                    .defineField(PK_FIELD, Object.class, Visibility.PRIVATE)
                    // PK getter는 인터셉트 제외: lazy init 없이 hidden 필드에서 즉시 반환
                    .method(ElementMatchers.isPublic()
                            .and(ElementMatchers.not(ElementMatchers.named(pkGetterName)))
                            .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class))))
                    .intercept(MethodDelegation.toField(INTERCEPTOR_FIELD))
                    .make()
                    .load(targetClass.getClassLoader(),
                            ClassLoadingStrategy.UsingLookup.of(lookup))
                    .getLoaded();
        } catch (IllegalAccessException e) {
            throw new SfsPersistenceException(
                    "byte-buddy enhanced 클래스 빌드 실패: " + targetClass.getName(), e);
        }
    }

    /**
     * targetClass에서 {@code @SfsId} 어노테이션이 붙은 필드를 반환한다.
     *
     * @throws SfsPersistenceException @SfsId 필드가 없는 경우
     */
    private Field findIdField(Class<?> targetClass) {
        for (Field f : targetClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(SfsId.class)) {
                return f;
            }
        }
        throw new SfsPersistenceException("@SfsId 누락: " + targetClass.getName());
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
