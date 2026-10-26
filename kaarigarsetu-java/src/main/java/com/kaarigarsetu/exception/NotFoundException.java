package com.kaarigarsetu.exception;

/** The entity does not exist, or is not visible to this caller (avoids id-probing). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
