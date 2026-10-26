package com.kaarigarsetu.security;

/** Delivery channel for one-time codes. The pilot ships a console stub; an SMS provider plugs in here. */
public interface OtpSender {
    /** @param purpose e.g. "login", "attestation", "moderator"; @param code the plaintext 6-digit code (never stored) */
    void send(String normalizedPhone, String purpose, String code);
}
