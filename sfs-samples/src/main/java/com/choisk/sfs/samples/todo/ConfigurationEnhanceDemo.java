package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.annotation.Bean;
import com.choisk.sfs.context.annotation.Configuration;
import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;

/**
 * inter-bean reference의 enhance 적용 시연.
 *
 * <p>Phase 1C 시절 두 번째 출력이 {@code false}였으나,
 * Phase 2A byte-buddy enhance 도입으로 {@code true}로 전환됨 — 마일스톤 증거.
 */
public class ConfigurationEnhanceDemo {

    /** todo 패키지의 User와 무관한 독립 내부 도메인 (학습 시야 분산 방지). */
    public static class User {
        public final long id;
        public User(long id) { this.id = id; }
    }

    public static class Account {
        public final User user;
        public Account(User user) { this.user = user; }
    }

    @Configuration
    public static class ArgFormConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account(User user) { return new Account(user); }
    }

    @Configuration
    public static class DirectCallConfig {
        @Bean public User user() { return new User(42); }
        @Bean public Account account() { return new Account(user()); }
    }

    public static void main(String[] args) {
        try (var argCtx = new AnnotationConfigApplicationContext(ArgFormConfig.class)) {
            boolean same = argCtx.getBean(Account.class).user == argCtx.getBean(User.class);
            System.out.println("Arg form (매개변수 라우팅): account.user == ctx.user → " + same);
        }
        try (var directCtx = new AnnotationConfigApplicationContext(DirectCallConfig.class)) {
            boolean same = directCtx.getBean(Account.class).user == directCtx.getBean(User.class);
            System.out.println("Direct call (본문 호출, enhance 적용): account.user == ctx.user → " + same);
        }
    }
}
