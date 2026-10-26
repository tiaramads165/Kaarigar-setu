package com.kaarigarsetu.exception;

/** Personal data processing was attempted without recorded consent (DPDP Act). */
public class ConsentRequiredException extends RuntimeException {
    public ConsentRequiredException(String message) {
        super(message);
    }
}
