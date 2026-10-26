package com.kaarigarsetu.attestation;

import com.kaarigarsetu.exception.DuplicateAttestationException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.model.Attestation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttestationServiceTest {
    @Test
    void retainsAttestationsAndProtectsAgainstDuplicateAndInvalidOtp() {
        List<Attestation> persisted = new ArrayList<>();
        AttestationStore store = new AttestationStore(attestation -> {
            persisted.add(attestation);
            return attestation;
        });
        AttestationService service = new AttestationService(store);
        Attestation attestation = new Attestation(1L, 10L, 20L, "unused-in-phase-two", "PENDING", BigDecimal.ZERO, null);

        service.record(attestation);
        assertEquals(1, store.all().size());
        assertEquals(List.of(attestation), persisted);
        assertThrows(DuplicateAttestationException.class, () -> service.record(attestation));
        assertDoesNotThrow(() -> service.verifyOtp("123456", "123456"));
        assertThrows(InvalidOTPException.class, () -> service.verifyOtp("123456", "000000"));
    }
}
