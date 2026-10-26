package com.kaarigarsetu.security;

import com.kaarigarsetu.dao.AuditLogDao;
import com.kaarigarsetu.model.AuditLog;

import java.util.Objects;

/** Connects the security audit chokepoint to the parameterised JDBC DAO. */
public class DaoAuditLogRepository implements AuditLogRepository {
    private final AuditLogDao auditLogDao;

    public DaoAuditLogRepository(AuditLogDao auditLogDao) {
        this.auditLogDao = Objects.requireNonNull(auditLogDao, "auditLogDao must not be null");
    }

    @Override
    public void save(AuditLog auditLog) {
        auditLogDao.insert(auditLog);
    }

    @Override
    public java.util.List<AuditLog> findByType(String eventType, int limit) {
        return auditLogDao.findByType(eventType, limit);
    }

    @Override
    public java.util.Map<String, Long> countByType(int minutes, java.util.Collection<String> eventTypes) {
        return auditLogDao.countByType(minutes, eventTypes);
    }

    @Override
    public java.util.Optional<Long> newestAgeMinutes(String eventType) {
        return auditLogDao.newestAgeMinutes(eventType);
    }

    @Override
    public java.util.List<AuditLog> recent(java.util.Collection<String> eventTypes, int limit) {
        return auditLogDao.recent(eventTypes, limit);
    }

    @Override
    public java.util.Optional<ChainStatus> verifyChain() {
        return java.util.Optional.of(auditLogDao.verifyChain());
    }
}
