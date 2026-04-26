package com.choisk.sfs.samples.todo.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.context.annotation.PreDestroy;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.service.UserService;

@Controller
public class UserController {
    @Autowired
    UserService userService;

    @PreDestroy
    void logShutdown() {
        System.out.println("[UserController] @PreDestroy: " + userService.total() + "명 사용자 등록 상태로 종료");
    }

    public User create(String name) {
        User u = userService.register(name);
        System.out.println("[UserController] User created: " + u.name + " (id=" + u.id + ")");
        return u;
    }
}
