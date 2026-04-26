package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;
import com.choisk.sfs.samples.todo.support.IdGenerator;

import java.util.Optional;

@Service
public class UserService {
    @Autowired
    UserRepository userRepo;

    @Autowired
    IdGenerator idGen;

    @PostConstruct
    void seedDefaultUser() {
        userRepo.save("기본 사용자", idGen.nowInstant());
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) {
        return userRepo.save(name, idGen.nowInstant());
    }

    public Optional<User> find(Long id) {
        return userRepo.findById(id);
    }

    public int total() {
        return userRepo.count();
    }
}
