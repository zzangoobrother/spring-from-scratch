package com.choisk.sfs.orm.support;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;

/**
 * SEQUENCE 전략 식별자 생성기 stub — E2에서 generate() 본격 구현.
 * INSERT 전 시퀀스 nextval을 조회하는 pre-insert 방식이므로 isPostInsert() = false.
 */
public class SequenceGenerator implements IdentifierGenerator {

    public SequenceGenerator(String sequenceName, JdbcTemplate jdbc) {
        /* E2 */
    }

    @Override
    public Object generate(Object entity, EntityMetadata md) {
        // E2에서 본격 구현 예정 — 현재는 컴파일 통과를 위한 stub
        throw new UnsupportedOperationException("E2");
    }

    @Override
    public boolean isPostInsert() {
        return false;
    }
}
