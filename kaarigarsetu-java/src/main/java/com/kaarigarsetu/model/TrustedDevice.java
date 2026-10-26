package com.kaarigarsetu.model;

import java.time.LocalDateTime;

/** A browser that has signed in as this account before. It decides which rate-limit bucket a code request uses; it is never a way to sign in. */
public record TrustedDevice(long deviceId, String role, long accountId, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
}
