package com.choisk.sfs.aop.support;

import com.choisk.sfs.aop.annotation.After;
import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Before;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 컨테이너 전체의 advice 레지스트리. BPP의 *내부 상태* — 빈으로 등록 안 함.
 * <p>BPP가 {@code @Aspect} 빈 발견 시 {@link #register} 호출, 일반 빈 매칭 시 {@link #findApplicable} lookup.
 *
 * <p><strong>스레드 안전성</strong>: 컨테이너 refresh 단일 스레드 가정 — 등록(write)은 refresh 중에만,
 * 매칭 조회(read)는 그 이후에만 발생. 멀티스레드 환경 진입 시 외부 동기화 필요.
 */
public class AspectRegistry {

    private final List<AdviceInfo> advices = new ArrayList<>();

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
     */
    public List<AdviceInfo> findApplicable(Method targetMethod) {
        List<AdviceInfo> result = new ArrayList<>();
        for (AdviceInfo info : advices) {
            Class<? extends Annotation> ann = info.targetAnnotation();
            if (targetMethod.isAnnotationPresent(ann)
                    || targetMethod.getDeclaringClass().isAnnotationPresent(ann)) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 클래스에 매칭 advice가 *하나라도* 있는지 — BPP의 enhance 결정에 사용 (전 메서드 순회 회피).
     */
    public boolean findAnyApplicable(Class<?> targetClass) {
        for (AdviceInfo info : advices) {
            Class<? extends Annotation> ann = info.targetAnnotation();
            if (targetClass.isAnnotationPresent(ann)) return true;
            for (Method m : targetClass.getDeclaredMethods()) {
                if (m.isAnnotationPresent(ann)) return true;
            }
        }
        return false;
    }
}
