package com.choisk.sfs.tx.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code JdbcTemplate.query} 결과 row → 객체 변환 함수형 인터페이스.
 */
@FunctionalInterface
public interface RowMapper<T> {
    T mapRow(ResultSet rs, int rowNum) throws SQLException;
}
