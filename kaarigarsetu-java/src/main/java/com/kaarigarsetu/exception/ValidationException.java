package com.kaarigarsetu.exception;

/** Client input failed validation. The message is safe to show to the caller. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
