package com.choisk.sfs.samples.todo.repository;

import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.todo.domain.User;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class UserRepository {
    private final ConcurrentHashMap<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    public User save(String name, Instant now) {
        long id = seq.incrementAndGet();
        User u = new User(id, name, now);
        store.put(id, u);
        return u;
    }

    public Optional<User> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public int count() {
        return store.size();
    }
}
