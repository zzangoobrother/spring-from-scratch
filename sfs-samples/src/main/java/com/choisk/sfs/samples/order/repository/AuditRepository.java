package com.choisk.sfs.samples.order.repository;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Repository;
import com.choisk.sfs.samples.order.domain.AuditLog;
import com.choisk.sfs.tx.jdbc.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class AuditRepository {

    @Autowired private JdbcTemplate jdbc;

    public void save(AuditLog log) {
        jdbc.update("INSERT INTO audit_log (occurred_at, message) VALUES (?, ?)",
                Timestamp.from(log.occurredAt()), log.message());
    }

    public List<AuditLog> findAll() {
        return jdbc.query("SELECT id, occurred_at, message FROM audit_log ORDER BY id",
                (rs, i) -> new AuditLog(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3)));
    }

    public long count() {
        return jdbc.query("SELECT COUNT(*) FROM audit_log", (rs, i) -> rs.getLong(1)).get(0);
    }
}
