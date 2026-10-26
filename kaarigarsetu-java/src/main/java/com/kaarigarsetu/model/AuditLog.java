package com.kaarigarsetu.model;

import java.time.LocalDateTime;

public record AuditLog(
        Long logId,
        String eventType,
        String actor,
        String details,
        LocalDateTime timestamp
) {
}
