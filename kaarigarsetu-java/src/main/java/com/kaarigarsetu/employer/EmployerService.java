package com.kaarigarsetu.employer;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.ConsentRequiredException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.worker.WorkerService;

import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Optional;

/** Employer registration (with consent). An employer becomes "verified" after their first approved attestation. */
public class EmployerService {
    private final EmployerDao employerDao;
    private final PhoneProtector phones;
    private final AuditLogger audit;
    private final Transactions transactions;

    public EmployerService(EmployerDao employerDao, PhoneProtector phones, AuditLogger audit, Transactions transactions) {
        this.employerDao = employerDao;
        this.phones = phones;
        this.audit = audit;
        this.transactions = transactions;
    }

    public Employer register(String name, String rawPhone, boolean consent) {
        if (!consent) throw new ConsentRequiredException("Consent is required before any personal data is recorded");
        if (name == null) throw new ValidationException("Name is required");
        String clean = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 120) throw new ValidationException("Name must be 2 to 120 characters");
        String phone = PhoneProtector.normalize(rawPhone);
        String lookup = phones.lookupKey(phone);
        Employer created;
        try {
            created = transactions.inTransaction(() -> {
                if (employerDao.findByPhone(lookup).isPresent()) throw new ConflictException("Already registered");
                return employerDao.insertProtected(new Employer(null, clean, lookup, false, null), phones.encrypt(phone), WorkerService.CONSENT_VERSION);
            });
        } catch (DataAccessException exception) {
            if (exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                throw new ConflictException("Already registered");
            }
            throw exception;
        }
        audit.log("EMPLOYER_REGISTERED", "employer:" + created.employerId(), "consent=" + WorkerService.CONSENT_VERSION);
        return created;
    }

    public Optional<Employer> findByPhone(String rawPhone) {
        return employerDao.findByPhone(phones.lookupKey(PhoneProtector.normalize(rawPhone)));
    }
}
