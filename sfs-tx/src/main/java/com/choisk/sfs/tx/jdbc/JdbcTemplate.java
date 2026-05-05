package com.choisk.sfs.tx.jdbc;

import com.choisk.sfs.tx.support.ConnectionHolder;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * mini JdbcTemplate. Spring 본가 {@code JdbcTemplate}의 query/update만 박제.
 *
 * <p>transaction-aware: TSM에 bind된 {@link ConnectionHolder}가 있으면 그 connection을 사용하고
 * close하지 않음 (TM이 close 책임). 없으면 {@link DataSource}에서 새로 가져와 finally close.
 */
public class JdbcTemplate {

    private final DataSource dataSource;
    private final TransactionSynchronizationManager tsm;

    public JdbcTemplate(DataSource dataSource, TransactionSynchronizationManager tsm) {
        this.dataSource = dataSource;
        this.tsm = tsm;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        ConnHandle h = obtainConnection();
        try {
            // prepareStatement 자체가 실패해도 outer finally에서 connection close 보장
            try (PreparedStatement ps = h.connection.prepareStatement(sql)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    int row = 0;
                    while (rs.next()) {
                        result.add(mapper.mapRow(rs, row++));
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw new RuntimeException("query failed: " + sql, e);
            }
        } finally {
            h.releaseIfNotBound();
        }
    }

    /**
     * SQL 쿼리 결과 행이 정확히 1건일 때 첫 번째 컬럼 값을 반환한다.
     *
     * <p>0건이면 null, 2건 이상이면 {@link IllegalStateException}.
     *
     * @param sql SQL 문자열
     * @param requiredType 반환 타입 (rs.getObject(1, requiredType) 으로 변환)
     * @param args 바인딩 파라미터
     * @param <T> 반환 타입
     * @return 첫 번째 행의 첫 번째 컬럼 값, 또는 결과 없으면 null
     */
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        List<T> results = query(sql, (rs, rowNum) -> rs.getObject(1, requiredType), args);
        if (results.isEmpty()) {
            return null;
        }
        if (results.size() > 1) {
            throw new IllegalStateException("Expected single result, got " + results.size());
        }
        return results.get(0);
    }

    /**
     * INSERT/UPDATE 실행 후 DB가 자동 생성한 키(generated key)를 반환한다.
     *
     * <p>ORM {@code IdentifierGenerator}가 IDENTITY 전략에서 호출한다.
     *
     * @param sql INSERT SQL
     * @param args 바인딩 파라미터
     * @return 생성된 키 (Number 하위 타입, DB가 반환한 그대로)
     * @throws IllegalStateException 키가 반환되지 않은 경우
     */
    public Number updateAndReturnKey(String sql, Object... args) {
        ConnHandle h = obtainConnection();
        try {
            // RETURN_GENERATED_KEYS: DB가 auto-increment/identity 컬럼 값을 반환하도록 지시
            try (PreparedStatement ps = h.connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindArgs(ps, args);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return (Number) keys.getObject(1);
                    }
                    throw new IllegalStateException("No generated key returned");
                }
            } catch (SQLException e) {
                throw new RuntimeException("updateAndReturnKey failed: " + sql, e);
            }
        } finally {
            h.releaseIfNotBound();
        }
    }

    public int update(String sql, Object... args) {
        ConnHandle h = obtainConnection();
        try {
            // prepareStatement 자체가 실패해도 outer finally에서 connection close 보장
            try (PreparedStatement ps = h.connection.prepareStatement(sql)) {
                bindArgs(ps, args);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("update failed: " + sql, e);
            }
        } finally {
            h.releaseIfNotBound();
        }
    }

    private ConnHandle obtainConnection() {
        ConnectionHolder bound = (ConnectionHolder) tsm.getResource(dataSource);
        if (bound != null) {
            return new ConnHandle(bound.getConnection(), false);
        }
        try {
            return new ConnHandle(dataSource.getConnection(), true);
        } catch (SQLException e) {
            throw new RuntimeException("getConnection failed", e);
        }
    }

    private static void bindArgs(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private record ConnHandle(Connection connection, boolean ownedByCaller) {
        void releaseIfNotBound() {
            if (ownedByCaller) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
    }
}
