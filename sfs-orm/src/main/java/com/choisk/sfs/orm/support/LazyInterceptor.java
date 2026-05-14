package com.choisk.sfs.orm.support;

import com.choisk.sfs.orm.exception.SfsLazyInitializationException;
import com.choisk.sfs.orm.exception.SfsPersistenceException;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * byte-buddy lazy proxy 인터셉터 — I2 본격 구현.
 *
 * <p>동작 원리:
 * <ul>
 *   <li>{@code MethodDelegation.toField(INTERCEPTOR_FIELD)}로 enhanced 클래스에서 위임 받음</li>
 *   <li>첫 메서드 호출 시 {@code target} 필드가 null이면 {@link PersistenceContext}의 identityMap 조회</li>
 *   <li>이후 호출은 캐시된 {@code target}에 직접 위임 — lazy 초기화 1회만 수행</li>
 * </ul>
 *
 * <p>학습 정점 ③ — lazy proxy의 identity 보장:<br>
 * {@code PersistenceContext.identityMap}이 단일 SoT이므로, 같은 PK에 대한 모든 인터셉터가
 * 동일 인스턴스를 반환한다. (Hibernate {@code ByteBuddyInterceptor} 동일 패턴)
 *
 * <p>현재 단위 구현은 {@code context.getEntity(key)}만 사용 — <strong>DB 로드 fallback 없음</strong>.<br>
 * J1에서 {@code LazyProxyFactory}가 emf 참조를 받으면서 fallback 추가 — 그 시점에 학습 정점 ③ 완전 회수.
 *
 * <p>Step 4 박제 (J1 forward stub 안내):
 * <pre>
 *   if (target == null) {
 *       if (context.isClosed()) throw new SfsLazyInitializationException(...);
 *       target = context.getEntity(new EntityKey(targetClass, pk));
 *       if (target == null) {
 *           // J1: emf 주입 후 EntityPersister.loadById() 호출로 DB fallback 추가
 *           throw new SfsPersistenceException("Lazy target not in persistence context");
 *       }
 *   }
 * </pre>
 */
public class LazyInterceptor {

    private final Class<?> targetClass;
    private final Object pk;
    private final PersistenceContext context;
    // 학습 정점 ③: 한 번 초기화된 후 불변 — identityMap 보장으로 같은 PK = 같은 인스턴스
    private Object target;

    public LazyInterceptor(Class<?> targetClass, Object pk, PersistenceContext context) {
        this.targetClass = targetClass;
        this.pk = pk;
        this.context = context;
    }

    /** lazy 초기화 후 채워진 원본 엔티티 반환 (테스트·J1 통합용) */
    public Object target() {
        return target;
    }

    /**
     * byte-buddy MethodDelegation 진입점.
     *
     * <ol>
     *   <li>target이 null이면 PersistenceContext 상태 확인 후 identityMap에서 로드</li>
     *   <li>context가 이미 닫혀 있으면 {@link SfsLazyInitializationException} 발생</li>
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
                // DB 로드 fallback 없음 — J1에서 emf 주입 후 추가
                throw new SfsPersistenceException(
                    "Lazy target not in persistence context: " + targetClass.getSimpleName() + "#" + pk);
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
