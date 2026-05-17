package com.choisk.sfs.samples.orm.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.orm.SfsEntityManager;
import com.choisk.sfs.samples.orm.domain.User;
import com.choisk.sfs.tx.annotation.Transactional;

/**
 * 사용자 서비스 — 학습 정점 ① SEQUENCE 전략 시연.
 * persist 시점에 SEQUENCE로 id를 미리 채번하고, 실제 INSERT는 commit(flush) 시점에 발생.
 */
@Service
public class UserService {

    @Autowired
    private SfsEntityManager em;

    /**
     * 사용자 생성 — SEQUENCE 전략으로 persist 시 id 즉시 채번.
     * INSERT는 트랜잭션 commit 시 write-behind 큐에서 실행.
     */
    @Transactional
    public User createUser(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        em.persist(user);
        // SEQUENCE 전략: persist 시점에 id 할당됨, INSERT는 commit 시점 (write-behind)
        return user;
    }
}
