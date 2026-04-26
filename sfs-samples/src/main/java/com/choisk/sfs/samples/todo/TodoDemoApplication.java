package com.choisk.sfs.samples.todo;

import com.choisk.sfs.context.support.AnnotationConfigApplicationContext;
import com.choisk.sfs.samples.todo.config.AppConfig;
import com.choisk.sfs.samples.todo.controller.TodoController;
import com.choisk.sfs.samples.todo.controller.UserController;
import com.choisk.sfs.samples.todo.domain.User;

public class TodoDemoApplication {

    public static void main(String[] args) {
        try (var ctx = new AnnotationConfigApplicationContext(AppConfig.class)) {

            UserController userController = ctx.getBean(UserController.class);
            TodoController todoController = ctx.getBean(TodoController.class);

            User alice = userController.create("Alice");
            todoController.create(alice.id, "장보기");
            todoController.create(alice.id, "운동");
            todoController.list(alice.id);
            todoController.complete(1L);
            todoController.list(alice.id);
        }
        // try-with-resources가 ctx.close() 호출 → @PreDestroy 트리거
    }
}
