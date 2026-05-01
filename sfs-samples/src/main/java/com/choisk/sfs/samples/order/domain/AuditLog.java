package com.choisk.sfs.samples.order.domain;

import java.time.Instant;

public record AuditLog(Long id, Instant occurredAt, String message) {

    public static AuditLog toCreate(Instant occurredAt, String message) {
        return new AuditLog(null, occurredAt, message);
    }
}
