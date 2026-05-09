package com.choisk.sfs.orm.support;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;

/**
 * IDENTITY 전략 식별자 생성기 stub — E1에서 generate() 구현.
 * INSERT 후 DB 생성 키를 읽어오는 post-insert 방식이므로 isPostInsert() = true.
 */
public class IdentityGenerator implements IdentifierGenerator {

    public IdentityGenerator(JdbcTemplate jdbc) {
        /* E1 */
    }

    @Override
    public boolean isPostInsert() {
        return true;
    }
}
