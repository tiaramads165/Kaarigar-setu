package com.kaarigarsetu.model;

import java.time.LocalDateTime;

public record Credential(
        Long credentialId,
        Long workerId,
        String signedPayload,
        String signature,
        LocalDateTime issuedAt
) {
}
