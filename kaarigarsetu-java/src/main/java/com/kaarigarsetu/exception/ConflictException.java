package com.kaarigarsetu.exception;

/** The requested change conflicts with current state. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
