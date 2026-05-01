package com.choisk.sfs.tx.jdbc;

import com.choisk.sfs.tx.support.ConnectionHolder;
import com.choisk.sfs.tx.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
