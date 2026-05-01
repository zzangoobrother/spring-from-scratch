package com.choisk.sfs.samples.order.service;

import com.choisk.sfs.context.annotation.Autowired;
import com.choisk.sfs.context.annotation.Service;
import com.choisk.sfs.samples.order.domain.AuditLog;
import com.choisk.sfs.samples.order.repository.AuditRepository;
import com.choisk.sfs.tx.annotation.Propagation;
import com.choisk.sfs.tx.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditService {

    @Autowired private AuditRepository auditRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String message) {
        auditRepo.save(AuditLog.toCreate(Instant.now(), message));
    }
}
