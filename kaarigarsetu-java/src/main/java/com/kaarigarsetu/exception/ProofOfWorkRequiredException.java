package com.kaarigarsetu.exception;

/** The caller must solve a small proof-of-work puzzle and ask again; carries a fresh puzzle (HTTP 428). */
public class ProofOfWorkRequiredException extends RuntimeException {
    private final String challenge;
    private final int bits;

    public ProofOfWorkRequiredException(String challenge, int bits) {
        super("Proof of work required");
        this.challenge = challenge;
        this.bits = bits;
    }

    public String challenge() {
        return challenge;
    }

    public int bits() {
        return bits;
    }
}
