package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;

/**
 * inter-bean reference 두 가지 형태를 비교하는 데모.
 *
 * <p>ArgForm: 매개변수 라우팅 — 컨테이너가 resolveDependency로 User 빈을 인자로 채워 호출.
 * 결과적으로 Account.user가 컨테이너의 User 싱글톤과 동일 인스턴스.</p>
 *
 * <p>DirectCall: 본문에서 user()를 직접 호출 — enhance 없으면 컨테이너 라우팅 없이
 * new User(42)가 새로 생성됨. 결과적으로 Account.user는 컨테이너 User 빈과 다른 인스턴스.</p>
 *
 * <p>학습 메모: 두 번째 출력이 false인 것이 이 데모의 핵심 가치.
 * byte-buddy enhance 도입 시 이 값이 true로 바뀌면 마일스톤 증거.</p>
 */
public class EnhanceAbsenceDemo {

    /** 학습용 단순 사용자 도메인 — todo 패키지 User와 무관한 독립 내부 클래스 */
    public static class User {
        public final long id;
        public User(long id) { this.id = id; }
    }

    /** 학습용 단순 계정 도메인 — User를 보유 */
    public static class Account {
        public final User user;
        public Account(User user) { this.user = user; }
    }

    /** 매개변수 라우팅 형태: 컨테이너가 User 빈을 account() 인자로 주입 */
    @Configuration
    public static class ArgFormConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account(User user) { return new Account(user); }
    }

    /** 직접 호출 형태: account() 본문에서 user()를 직접 호출 (enhance 부재) */
    @Configuration
    public static class DirectCallConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account() { return new Account(user()); }
    }

    /**
     * 두 형태를 콘솔 출력으로 비교 시연.
     *
     * @param args 미사용
     */
    public static void main(String[] args) {
        // ArgForm: 매개변수 라우팅 — 컨테이너 싱글톤이 그대로 전달되어 동일 인스턴스
        try (var argCtx = new AnnotationConfigApplicationContext(ArgFormConfig.class)) {
            boolean same = argCtx.getBean(Account.class).user == argCtx.getBean(User.class);
            System.out.println("Arg form (매개변수 라우팅): account.user == ctx.user → " + same);
        }

        // DirectCall: 직접 호출 — enhance 없으면 새 인스턴스 생성, 다른 인스턴스
        try (var directCtx = new AnnotationConfigApplicationContext(DirectCallConfig.class)) {
            boolean same = directCtx.getBean(Account.class).user == directCtx.getBean(User.class);
            System.out.println("Direct call (본문 호출, enhance 부재): account.user == ctx.user → " + same);
        }
    }
}
