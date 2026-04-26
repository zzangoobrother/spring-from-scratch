package com.choisk.sfs.samples.todo.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.todo.domain.Todo;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class TodoRepository {
    @Autowired
    IdGenerator idGen;

    private final ConcurrentHashMap<Long, Todo> store = new ConcurrentHashMap<>();

    public Todo save(Long ownerId, String title) {
        Todo t = new Todo(idGen.next(), ownerId, title);
        store.put(t.id, t);
        return t;
    }

    public Optional<Todo> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Todo> findByOwnerId(Long ownerId) {
        return store.values().stream()
                .filter(t -> t.ownerId.equals(ownerId))
                .sorted(Comparator.comparing(t -> t.id))
                .toList();
    }
}
