package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.repository.TodoRepository;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoServiceTest {

    @Test
    void createRejectsUnknownOwner() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class)) {

            TodoService todoService = ctx.getBean(TodoService.class);
            assertThatThrownBy(() -> todoService.create(999L, "ghost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown user id=999");
        }
    }

    @Test
    void completeFlipsStatusToDone() {
        try (var ctx = new AnnotationConfigApplicationContext(
                AppConfig.class, UserRepository.class, TodoRepository.class,
                UserService.class, TodoService.class)) {

            TodoService todoService = ctx.getBean(TodoService.class);
            // 시드 사용자 id=1을 활용
            Todo created = todoService.create(1L, "테스트");
            assertThat(created.status).isEqualTo(Todo.Status.TODO);

            todoService.complete(created.id);
            assertThat(created.status).isEqualTo(Todo.Status.DONE);
        }
    }
}
