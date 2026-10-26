package com.kaarigarsetu.exception;

/** Raised when the same employer tries to attest the same worker twice. */
public class DuplicateAttestationException extends RuntimeException {
    public DuplicateAttestationException(String message) {
        super(message);
    }
}
