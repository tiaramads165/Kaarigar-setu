package com.kaarigarsetu.exception;

/** The caller is authenticated but not allowed to do this. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
