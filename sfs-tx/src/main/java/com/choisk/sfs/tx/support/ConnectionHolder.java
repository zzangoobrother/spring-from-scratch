package com.choisk.sfs.tx.support;

import java.sql.Connection;

/**
 * TSM에 binding되는 트랜잭션 컨텍스트. {@link DataSourceTransactionManager}가 begin 시 생성/bind,
 * commit/rollback 시 unbind/close. {@link com.choisk.sfs.tx.jdbc.JdbcTemplate}이 getResource로 조회.
 */
public final class ConnectionHolder {

    private final Connection connection;

    public ConnectionHolder(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
