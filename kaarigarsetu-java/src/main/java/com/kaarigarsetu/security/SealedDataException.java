package com.kaarigarsetu.security;

/** A stored value could not be opened: it was altered, copied from somewhere else, sealed with a key this server does not hold, or is plaintext where sealing is required. */
public class SealedDataException extends RuntimeException {
    public SealedDataException(String message) {
        super(message);
    }
}
