package com.kaarigarsetu.exception;

/** An upstream service (ML microservice, SMS) failed. Details are logged, never returned. */
public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String message) {
        super(message);
    }
}
