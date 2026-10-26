package com.kaarigarsetu.attestation;

import com.kaarigarsetu.model.Attestation;

/** Persistence boundary for the attestation service layer. */
@FunctionalInterface
public interface AttestationRepository {
    Attestation save(Attestation attestation);
}
