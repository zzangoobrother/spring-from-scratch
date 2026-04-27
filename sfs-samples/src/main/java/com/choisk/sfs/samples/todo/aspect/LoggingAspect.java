package com.choisk.sfs.samples.todo.aspect;

import com.choisk.sfs.aop.annotation.Around;
import com.choisk.sfs.aop.annotation.Aspect;
import com.choisk.sfs.aop.annotation.Before;
import com.choisk.sfs.aop.annotation.Loggable;
import com.choisk.sfs.aop.support.JoinPoint;
import com.choisk.sfs.aop.support.ProceedingJoinPoint;
import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Component;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.util.Arrays;

/**
 * 실행 시간을 측정하고 출력하는 시연용 Around advice.
 * <p>{@code @Aspect}는 advice 정의 마커, {@code @Component}는 컨테이너 등록 — Spring 본가 패턴 그대로 둘 다 부착.
 * <p>출력 형식: {@code [Around id=N] <method> 실행 시간 <ms> ms}
 */
@Aspect
@Component
public class LoggingAspect {

    // @Autowired 필드 주입 — 학습 시연 단순화 (생성자 주입 + @Aspect 양립은 Phase 2C+ 검증)
    @Autowired
    private IdGenerator idGen;

    /**
     * {@code @Loggable} 부착 메서드 호출 직전에 메서드명과 인자를 출력한다.
     * 출력 형식: {@code [Before] <methodName> 호출 — args=<args>}
     *
     * @param jp 조인 포인트 — 메서드명·인자 읽기 전용, proceed() 호출 불가
     */
    @Before(Loggable.class)
    public void logCall(JoinPoint jp) {
        System.out.println("[Before] " + jp.getMethod().getName()
                + " 호출 — args=" + Arrays.toString(jp.getArgs()));
    }

    /**
     * {@code @Loggable} 부착 메서드의 실행 시간을 측정하고 콘솔에 출력한다.
     *
     * @param pjp 진행 중인 조인 포인트 — {@code proceed()}로 실제 메서드에 위임
     * @return 실제 메서드의 반환값 그대로 전달
     * @throws Throwable 실제 메서드가 던진 예외를 그대로 전파
     */
    @Around(Loggable.class)
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long id = idGen.next();
        String methodName = pjp.getMethod().getName();
        long start = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            long elapsedMs = System.currentTimeMillis() - start;
            System.out.println("[Around id=" + id + "] " + methodName + " 실행 시간 " + elapsedMs + " ms");
        }
    }
}
