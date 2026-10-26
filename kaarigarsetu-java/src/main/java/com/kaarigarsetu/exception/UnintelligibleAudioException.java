package com.kaarigarsetu.exception;

/** Speech-to-text produced nothing usable; the worker may record again. */
public class UnintelligibleAudioException extends RuntimeException {
    public UnintelligibleAudioException(String message) {
        super(message);
    }
}
