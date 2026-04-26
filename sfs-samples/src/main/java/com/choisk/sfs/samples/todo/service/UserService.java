package com.choisk.sfs.samples.todo.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.PostConstruct;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.todo.domain.User;
import com.choisk.sfs.samples.todo.repository.UserRepository;

import java.time.Clock;
import java.util.Optional;

@Service
public class UserService {
    @Autowired
    UserRepository userRepo;

    @Autowired
    Clock clock;

    @PostConstruct
    void seedDefaultUser() {
        userRepo.save("기본 사용자", clock.instant());
        System.out.println("[UserService] @PostConstruct: 기본 사용자 시드 완료");
    }

    public User register(String name) {
        return userRepo.save(name, clock.instant());
    }

    public Optional<User> find(Long id) {
        return userRepo.findById(id);
    }

    public int total() {
        return userRepo.count();
    }
}
