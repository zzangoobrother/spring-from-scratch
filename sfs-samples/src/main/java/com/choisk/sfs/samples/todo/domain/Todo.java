package com.choisk.sfs.samples.todo.domain;

public class Todo {
    public enum Status { TODO, DONE }

    public final Long id;
    public final Long ownerId;
    public final String title;
    public Status status;

    public Todo(Long id, Long ownerId, String title) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = title;
        this.status = Status.TODO;
    }
}
