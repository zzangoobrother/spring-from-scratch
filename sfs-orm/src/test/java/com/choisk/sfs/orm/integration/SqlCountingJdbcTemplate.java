package com.choisk.sfs.orm.integration;

import com.choisk.sfs.tx.jdbc.JdbcTemplate;
import com.choisk.sfs.tx.jdbc.RowMapper;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 실행 횟수를 카운트하는 spy JdbcTemplate — *테스트 전용 서브클래스*.
 * production JdbcTemplate은 변경 0.
 *
 * <p>MP-2 N+1 박제 + lazy 발화 시점 검증에 사용.
 */
public class SqlCountingJdbcTemplate extends JdbcTemplate {

    /** 실행된 SQL 문자열 목록 — 동시성 안전(synchronized list). */
    private final List<String> executedSqls = Collections.synchronizedList(new ArrayList<>());

    public SqlCountingJdbcTemplate(DataSource ds, TransactionSynchronizationManager tsm) {
        super(ds, tsm);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rm, Object... params) {
        executedSqls.add(sql);
        return super.query(sql, rm, params);
    }

    @Override
    public int update(String sql, Object... params) {
        executedSqls.add(sql);
        return super.update(sql, params);
    }

    /** {@code pattern}을 포함하는 SQL 실행 횟수를 반환한다. */
    public int countMatching(String pattern) {
        return (int) executedSqls.stream().filter(s -> s.contains(pattern)).count();
    }

    /** 카운터를 초기화한다 — 테스트 내 구간별 측정에 사용. */
    public void reset() {
        executedSqls.clear();
    }
}
