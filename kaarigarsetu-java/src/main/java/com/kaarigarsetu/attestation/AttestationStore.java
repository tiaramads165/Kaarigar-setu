package com.kaarigarsetu.attestation;

import com.kaarigarsetu.exception.DuplicateAttestationException;
import com.kaarigarsetu.model.Attestation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory working set backed by an optional persistence adapter. */
public class AttestationStore {
    private final List<Attestation> attestations = new ArrayList<>();
    private final AttestationRepository repository;

    public AttestationStore() {
        this(attestation -> attestation);
    }

    public AttestationStore(AttestationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public synchronized void add(Attestation attestation) {
        Objects.requireNonNull(attestation, "attestation must not be null");
        boolean duplicate = attestations.stream().anyMatch(existing ->
                existing.workerId().equals(attestation.workerId())
                        && existing.employerId().equals(attestation.employerId()));
        if (duplicate) {
            throw new DuplicateAttestationException("An attestation already exists for this worker and employer");
        }
        attestations.add(repository.save(attestation));
    }

    public synchronized List<Attestation> all() {
        return List.copyOf(attestations);
    }
}
