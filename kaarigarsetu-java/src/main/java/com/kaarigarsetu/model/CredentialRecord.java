package com.kaarigarsetu.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record CredentialRecord(long credentialId, UUID credentialRef, long workerId, String signedPayload, String signature,
                               LocalDateTime issuedAt, LocalDateTime expiresAt, LocalDateTime revokedAt,
                               String revocationReason, String keyId) {
    public boolean revoked() {
        return revokedAt != null;
    }
}
