package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.repository.TodoRepository;
import com.choisk.sfs.samples.todo.repository.UserRepository;

import java.util.List;

@Service
public class TodoService {
    @Autowired
    TodoRepository todoRepo;

    @Autowired
    UserRepository userRepo;

    public Todo create(Long ownerId, String title) {
        userRepo.findById(ownerId).orElseThrow(() ->
                new IllegalArgumentException("Unknown user id=" + ownerId));
        return todoRepo.save(ownerId, title);
    }

    public List<Todo> listFor(Long ownerId) {
        return todoRepo.findByOwnerId(ownerId);
    }

    public Todo complete(Long todoId) {
        Todo t = todoRepo.findById(todoId).orElseThrow(() ->
                new IllegalArgumentException("Unknown todo id=" + todoId));
        t.status = Todo.Status.DONE;
        return t;
    }
}
