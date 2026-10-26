package com.kaarigarsetu.attestation;

import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.model.Attestation;

import java.util.Objects;

/** Business rules around an attestation; secure OTP hashing is added in Phase 4. */
public class AttestationService {
    private final AttestationStore store;

    public AttestationService(AttestationStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public void record(Attestation attestation) {
        store.add(attestation);
    }

    public void verifyOtp(String expectedOtp, String submittedOtp) {
        if (expectedOtp == null || submittedOtp == null || !expectedOtp.equals(submittedOtp)) {
            throw new InvalidOTPException("The supplied OTP is invalid");
        }
    }
}
