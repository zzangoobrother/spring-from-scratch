package com.choisk.sfs.samples.todo.controller;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Controller;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.service.TodoService;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class TodoController {
    @Autowired
    TodoService todoService;

    public Todo create(Long ownerId, String title) {
        Todo t = todoService.create(ownerId, title);
        System.out.println("[TodoController] Todo created: id=" + t.id + " owner=" + t.ownerId + " \"" + t.title + "\"");
        return t;
    }

    public void list(Long ownerId) {
        List<Todo> todos = todoService.listFor(ownerId);
        String summary = todos.stream()
                .map(t -> "[id=" + t.id + "] " + t.title + " [" + t.status + "]")
                .collect(Collectors.joining(", "));
        System.out.println("[TodoController] Todos for owner " + ownerId + ": " + summary);
    }

    public void complete(Long todoId) {
        todoService.complete(todoId);
        System.out.println("[TodoController] Todo " + todoId + " completed");
    }
}
