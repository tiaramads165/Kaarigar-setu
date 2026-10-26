package com.kaarigarsetu.exception;

/** Raised when an operation needs more trust than a worker currently has. */
public class LowTrustScoreException extends RuntimeException {
    public LowTrustScoreException(String message) {
        super(message);
    }
}
