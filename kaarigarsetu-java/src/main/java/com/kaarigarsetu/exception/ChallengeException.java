package com.kaarigarsetu.exception;

/**
 * The spoken-code challenge of an answer was not met. {@code REQUIRED}: no valid, fresh challenge came with the answer (fetch a new one and record again).
 * {@code NOT_HEARD}: the recording was understood but the code was not in it (say it at the end of the answer and record again).
 */
public class ChallengeException extends RuntimeException {
    public enum Kind { REQUIRED, NOT_HEARD }

    private final Kind kind;

    public ChallengeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
