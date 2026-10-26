package com.kaarigarsetu.attestation;

import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.model.Attestation;

import java.util.Objects;

/** Production persistence adapter; JDBC details stay outside the business service. */
public class DaoAttestationRepository implements AttestationRepository {
    private final AttestationDao attestationDao;

    public DaoAttestationRepository(AttestationDao attestationDao) {
        this.attestationDao = Objects.requireNonNull(attestationDao, "attestationDao must not be null");
    }

    @Override
    public Attestation save(Attestation attestation) {
        return attestationDao.insert(attestation);
    }
}
