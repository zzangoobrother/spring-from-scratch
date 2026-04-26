package com.choisk.sfs.samples.todo.domain;

import java.time.Instant;

public class User {
    public final Long id;
    public final String name;
    public final Instant createdAt;

    public User(Long id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }
}
