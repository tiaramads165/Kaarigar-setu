package com.kaarigarsetu.security;

import com.kaarigarsetu.model.AuditLog;

@FunctionalInterface
public interface AuditLogRepository {
    /** Result of the database-side hash chain check (verify_audit_chain()). */
    record ChainStatus(boolean valid, long firstBadSeq, long checked) {
    }

    void save(AuditLog auditLog);

    /** Newest first. Repositories that cannot search (test doubles) return nothing. */
    default java.util.List<AuditLog> findByType(String eventType, int limit) {
        return java.util.List.of();
    }

    /** Number of events of each given type recorded in the last {@code minutes} minutes (by the database clock). */
    default java.util.Map<String, Long> countByType(int minutes, java.util.Collection<String> eventTypes) {
        return java.util.Map.of();
    }

    /** Age in minutes (database clock) of the newest event of this type; empty if there is none. */
    default java.util.Optional<Long> newestAgeMinutes(String eventType) {
        return java.util.Optional.empty();
    }

    /** Newest first. */
    default java.util.List<AuditLog> recent(java.util.Collection<String> eventTypes, int limit) {
        return java.util.List.of();
    }

    default java.util.Optional<ChainStatus> verifyChain() {
        return java.util.Optional.empty();
    }
}
