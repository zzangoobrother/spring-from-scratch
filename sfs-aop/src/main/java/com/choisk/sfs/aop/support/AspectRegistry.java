package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Before;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 컨테이너 전체의 advice 레지스트리. BPP의 *내부 상태* — 빈으로 등록 안 함.
 * <p>BPP가 {@code @Aspect} 빈 발견 시 {@link #register} 호출, 일반 빈 매칭 시 {@link #findApplicable} lookup.
 *
 * <p><strong>캐시:</strong> {@link #findApplicable(Method)}는 Method → List&lt;AdviceInfo&gt; 매핑을 캐시한다.
 * advices가 startup 이후 불변이라는 가정에 의존하며, {@link #register} 호출 시 invalidate된다.
 *
 * <p><strong>스레드 안전성</strong>: 컨테이너 refresh 단일 스레드 가정 — 등록(write)은 refresh 중에만,
 * 매칭 조회(read)는 그 이후에만 발생. 멀티스레드 환경 진입 시 외부 동기화 필요.
 */
public class AspectRegistry {

    private final List<AdviceInfo> advices = new ArrayList<>();
    private final Map<Method, List<AdviceInfo>> cache = new HashMap<>();

    /**
     * {@code @Aspect} 빈의 메서드를 순회하며 {@code @Around}/{@code @Before}/{@code @After} 발견 시 advice 등록.
     *
     * <p>spec § 7.1 시그니처 규칙을 위반하는 메서드는 등록 즉시 {@link IllegalStateException}을 throw한다(fail-fast).
     * <ul>
     *   <li>{@code @Around}: 첫 인자가 {@link ProceedingJoinPoint}여야 함</li>
     *   <li>{@code @Before}: 첫 인자가 {@link JoinPoint}이고 반환 타입이 {@code void}여야 함</li>
     *   <li>{@code @After}: 첫 인자가 {@link JoinPoint}이고 반환 타입이 {@code void}여야 함</li>
     * </ul>
     *
     * <p>{@link Class#getDeclaredMethods}: aspect 클래스 <em>상속 미지원</em>이 의도. 부모 클래스의 advice는 무시됨
     * (Phase 2C+ 이후 상속 advice 지원 여부 재검토).
     */
    public void register(String aspectBeanName, Class<?> aspectClass) {
        for (Method m : aspectClass.getDeclaredMethods()) {
            Around around = m.getAnnotation(Around.class);
            if (around != null) {
                validateAroundSignature(m, aspectClass);
                advices.add(new AdviceInfo(AdviceType.AROUND, around.value(), m, aspectBeanName));
            }
            Before before = m.getAnnotation(Before.class);
            if (before != null) {
                validateBeforeAfterSignature(m, aspectClass, "@Before");
                advices.add(new AdviceInfo(AdviceType.BEFORE, before.value(), m, aspectBeanName));
            }
            After after = m.getAnnotation(After.class);
            if (after != null) {
                validateBeforeAfterSignature(m, aspectClass, "@After");
                advices.add(new AdviceInfo(AdviceType.AFTER, after.value(), m, aspectBeanName));
            }
        }
        cache.clear();  // advices 변경 시 캐시 invalidate
    }

    /**
     * {@code @Around} 메서드의 첫 인자가 {@link ProceedingJoinPoint}인지 검증한다.
     * 위반 시 {@link IllegalStateException} throw — 기대 시그니처 포함.
     */
    private void validateAroundSignature(Method m, Class<?> aspectClass) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0 || !ProceedingJoinPoint.class.isAssignableFrom(params[0])) {
            throw new IllegalStateException(
                    "@Around advice 시그니처 위반 — " + aspectClass.getName() + "#" + m.getName()
                            + ": 첫 인자는 ProceedingJoinPoint 이어야 합니다."
                            + " (기대: Object " + m.getName() + "(ProceedingJoinPoint pjp) throws Throwable)"
            );
        }
    }

    /**
     * {@code @Before}/{@code @After} 메서드의 첫 인자가 {@link JoinPoint}이고 반환 타입이 {@code void}인지 검증한다.
     * 위반 시 {@link IllegalStateException} throw — 기대 시그니처 포함.
     */
    private void validateBeforeAfterSignature(Method m, Class<?> aspectClass, String adviceLabel) {
        Class<?>[] params = m.getParameterTypes();
        boolean firstArgOk = params.length > 0 && JoinPoint.class.isAssignableFrom(params[0]);
        boolean returnOk = m.getReturnType() == void.class;
        if (!firstArgOk) {
            throw new IllegalStateException(
                    adviceLabel + " advice 시그니처 위반 — " + aspectClass.getName() + "#" + m.getName()
                            + ": 첫 인자는 JoinPoint 이어야 합니다."
                            + " (기대: void " + m.getName() + "(JoinPoint jp))"
            );
        }
        if (!returnOk) {
            throw new IllegalStateException(
                    adviceLabel + " advice 시그니처 위반 — " + aspectClass.getName() + "#" + m.getName()
                            + ": 반환 타입은 void 이어야 합니다."
                            + " (기대: void " + m.getName() + "(JoinPoint jp))"
            );
        }
    }

    /**
     * 메서드의 매칭 advice 반환. 메서드 단위 애노테이션 우선, 없으면 declaring 클래스 단위 매칭.
     * 결과는 Method 단위로 캐시되며 {@link #register} 호출 시 invalidate된다.
     */
    public List<AdviceInfo> findApplicable(Method targetMethod) {
        return cache.computeIfAbsent(targetMethod, key -> {
            List<AdviceInfo> result = new ArrayList<>();
            for (AdviceInfo info : advices) {
                Class<? extends Annotation> ann = info.targetAnnotation();
                if (key.isAnnotationPresent(ann)
                        || key.getDeclaringClass().isAnnotationPresent(ann)) {
                    result.add(info);
                }
            }
            return result;
        });
    }

    /**
     * 클래스에 매칭 advice가 *하나라도* 있는지 — BPP의 enhance 결정에 사용 (전 메서드 순회 회피).
     *
     * <p>{@link Class#getMethods()}로 public 상속 메서드까지 포함해 검사한다.
     * private/package-private 메서드는 advice 비대상이므로 검사 대상에서 제외해도 무방하다.
     *
     * <p>루프 구조: 클래스 레벨을 먼저 O(A) 스캔 후, 메서드 레벨에서는 {@code getMethods()} 1회만 호출하여
     * outer-method / inner-advice 순서로 early exit한다.
     */
    public boolean findAnyApplicable(Class<?> targetClass) {
        if (advices.isEmpty()) return false;
        // 클래스 레벨 매칭 — getMethods() 호출 없이 O(A)
        for (AdviceInfo info : advices) {
            if (targetClass.isAnnotationPresent(info.targetAnnotation())) return true;
        }
        // 메서드 레벨 — getMethods() 1회만 호출
        Method[] methods = targetClass.getMethods();
        for (Method m : methods) {
            for (AdviceInfo info : advices) {
                if (m.isAnnotationPresent(info.targetAnnotation())) return true;
            }
        }
        return false;
    }
}
