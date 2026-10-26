package com.kaarigarsetu.exception;

/** Credentials were wrong. Deliberately vague so it reveals nothing about which part failed. */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
