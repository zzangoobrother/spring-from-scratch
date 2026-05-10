package com.choisk.sfs.orm.support;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;

/**
 * SEQUENCE 전략 식별자 생성기.
 *
 * <p><b>학습 정점 ① 정상 박제:</b>
 * {@code isPostInsert() = false}이므로 INSERT 실행 전에 호출된다.
 * {@code generate()}는 {@code SELECT NEXTVAL('시퀀스명')}만 실행하고 INSERT는 하지 않는다.
 * IDENTITY(post-insert)와 달리 write-behind 큐를 우회하지 않으며, flush 시점에 INSERT가 실행된다.
 */
public class SequenceGenerator implements IdentifierGenerator {

    private final String sequenceName;
    private final JdbcTemplate jdbc;

    public SequenceGenerator(String sequenceName, JdbcTemplate jdbc) {
        this.sequenceName = sequenceName;
        this.jdbc = jdbc;
    }

    @Override
    public boolean isPostInsert() {
        return false;
    }

    /**
     * INSERT 전 시퀀스에서 다음 값을 조회하여 반환한다.
     * INSERT를 실행하지 않는다 — 학습 정점 ① 정상 박제.
     *
     * @param entity 삽입될 엔티티 인스턴스 (이 메서드에서는 사용하지 않음)
     * @param md     엔티티 메타데이터 (이 메서드에서는 사용하지 않음)
     * @return 시퀀스 nextval (Long)
     */
    @Override
    public Object generate(Object entity, EntityMetadata md) {
        return jdbc.queryForObject("SELECT NEXTVAL('" + sequenceName + "')", Long.class);
    }
}
