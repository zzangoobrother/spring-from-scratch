package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.orm.exception.SfsPersistenceException;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * byte-buddy lazy proxy 인터셉터 — J1 통합 완성.
 *
 * <p>동작 원리:
 * <ul>
 *   <li>{@code MethodDelegation.toField(INTERCEPTOR_FIELD)}로 enhanced 클래스에서 위임 받음</li>
 *   <li>첫 메서드 호출 시 {@code target} 필드가 null이면 {@link PersistenceContext}의 identityMap 조회</li>
 *   <li>identityMap miss 시 {@link LazyTargetLoader}로 DB fallback 로드 — 이후 identityMap에 등록</li>
 *   <li>이후 호출은 캐시된 {@code target}에 직접 위임 — lazy 초기화 1회만 수행</li>
 * </ul>
 *
 * <p>학습 정점 ③ — lazy proxy의 identity 보장 완전 회수 (J1):<br>
 * {@code PersistenceContext.identityMap}이 단일 SoT이므로, 같은 PK에 대한 모든 인터셉터가
 * 동일 인스턴스를 반환한다. DB fallback 후 identityMap에 등록하므로 재조회도 같은 인스턴스.
 * (Hibernate {@code ByteBuddyInterceptor} 동일 패턴)
 *
 * <p>fallback loader의 context는 null로 고정 — fallback 로드는 관계를 재귀 채우지 않음 (순환 참조 회피).
 */
public class LazyInterceptor {

    private final Class<?> targetClass;
    private final Object pk;
    private final PersistenceContext context;
    // J1: DB fallback 로더 — context.getEntity() miss 시 EntityPersister.loadById() 위임
    private final LazyTargetLoader loader;
    // 학습 정점 ③: 한 번 초기화된 후 불변 — identityMap 보장으로 같은 PK = 같은 인스턴스
    private Object target;

    public LazyInterceptor(Class<?> targetClass, Object pk, PersistenceContext context,
                           LazyTargetLoader loader) {
        this.targetClass = targetClass;
        this.pk = pk;
        this.context = context;
        this.loader = loader;
    }

    /** lazy 초기화 후 채워진 원본 엔티티 반환 (테스트·통합용) */
    public Object target() {
        return target;
    }

    /**
     * byte-buddy MethodDelegation 진입점.
     *
     * <ol>
     *   <li>target이 null이면 PersistenceContext 상태 확인 후 identityMap에서 로드</li>
     *   <li>context가 이미 닫혀 있으면 {@link SfsLazyInitializationException} 발생</li>
     *   <li>identityMap miss 시 {@link LazyTargetLoader}로 DB fallback 로드 + identityMap 등록</li>
     *   <li>캐시 hit 후 {@code method.invoke}로 실제 메서드 위임</li>
     *   <li>{@link InvocationTargetException} 발생 시 원인 예외를 언래핑해 그대로 재전파 (JDK Proxy 표준)</li>
     * </ol>
     */
    @RuntimeType
    public Object intercept(@Origin Method method, @AllArguments Object[] args) throws Throwable {
        if (target == null) {
            if (context.isClosed()) {
                // PersistenceContext 닫힌 후 lazy init 시도 — Hibernate와 동일한 예외 패턴
                throw new SfsLazyInitializationException(targetClass.getSimpleName() + "#" + pk);
            }
            // 학습 정점 ③: identityMap 거침 — 같은 PK는 같은 인스턴스 보장
            target = context.getEntity(new EntityKey(targetClass, pk));
            if (target == null) {
                // DB fallback 로드 — fallback의 context는 null (순환 참조 회피)
                target = loader.load(targetClass, pk);
                if (target == null) {
                    throw new SfsPersistenceException(
                        "Lazy target not found: " + targetClass.getSimpleName() + "#" + pk);
                }
                // 로드된 인스턴스를 identityMap에 등록 — 이후 동일 PK 조회 시 같은 인스턴스 반환
                context.putEntity(new EntityKey(targetClass, pk), target);
            }
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // InvocationTargetException 언래핑 — 원인 예외를 그대로 전파
            throw e.getTargetException();
        }
    }
}
