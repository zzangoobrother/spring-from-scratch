package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    @Test
    void postConstructSeedsDefaultUser() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, UserService.class)) {

            UserService userService = ctx.getBean(UserService.class);
            assertThat(userService.total())
                    .as("@PostConstruct가 시드 사용자 1명을 생성해야 함")
                    .isEqualTo(1);

            User seeded = userService.find(1L).orElseThrow();
            assertThat(seeded.name).isEqualTo("기본 사용자");
        }
    }
}
