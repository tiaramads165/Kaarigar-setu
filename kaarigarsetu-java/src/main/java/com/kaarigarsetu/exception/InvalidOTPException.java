package com.kaarigarsetu.exception;

/** Raised when an attestation OTP cannot be accepted. */
public class InvalidOTPException extends RuntimeException {
    public InvalidOTPException(String message) {
        super(message);
    }
}
