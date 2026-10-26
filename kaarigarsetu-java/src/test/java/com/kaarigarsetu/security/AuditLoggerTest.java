package com.kaarigarsetu.security;

import com.kaarigarsetu.model.AuditLog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditLoggerTest {
    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final StringBuilder file = new StringBuilder();
    private final AuditFileSink sink = file::append;
    private final AuditLogger logger = new AuditLogger(repository, sink);

    @Test
    void writesToBothTheDatabaseAndTheFile() {
        logger.log("LOGIN_SUCCESS", "worker:7", "method=otp");
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        assertEquals("LOGIN_SUCCESS", saved.getValue().eventType());
        assertEquals("LOGIN_SUCCESS actor=worker:7 details=method=otp", file.toString());
    }

    @Test
    void controlCharactersCannotForgeExtraLogLines() {
        logger.log("EVT", "worker:1", "ok\nFORGED actor=admin details=pwned\r\nmore");
        assertFalse(file.toString().contains("\n"));
        assertFalse(file.toString().contains("\r"));
    }

    @Test
    void phoneNumbersAreMaskedSoTheAuditTrailIsNotASecondCopyOfPersonalData() {
        logger.log("EVT", "worker:1", "called 9876543210 and +91 91234 56780 and 919876543210");
        assertFalse(file.toString().matches(".*[6-9][0-9]{9}.*"), file.toString());
        assertTrue(file.toString().contains("[phone]"));
    }

    @Test
    void overlongFieldsAreCapped() {
        logger.log("EVT", "worker:1", "x".repeat(5000));
        assertTrue(file.length() < 700);
    }

    @Test
    void blankFieldsAreRefusedAndNothingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> logger.log("", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> logger.log("E", null, "b"));
        assertThrows(IllegalArgumentException.class, () -> logger.log("E", "a", " "));
        verify(repository, never()).save(any());
    }

    @Test
    void aFailingFileSinkSurfacesAsAnErrorRatherThanSilentlyLosingTheTrail() {
        AuditLogger failing = new AuditLogger(repository, e -> { throw new IOException("disk full"); });
        assertThrows(IllegalStateException.class, () -> failing.log("EVT", "a", "b"));
    }

    // ---- anchoring the file in the database

    private static final String HASH = "a".repeat(64);

    @Test
    void anchoringPinsTheNewestFileLineInTheDatabaseOnlyOnceAndNeverWritesToTheFile() {
        AuditFileSink withHead = new AuditFileSink() {
            @Override public void append(String event) { file.append(event); }
            @Override public java.util.Optional<Head> head() { return java.util.Optional.of(new Head(7, HASH)); }
        };
        AuditLogger anchoring = new AuditLogger(repository, withHead);
        assertTrue(anchoring.anchorFileHead());
        assertFalse(anchoring.anchorFileHead(), "nothing new to pin");
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        assertEquals(AuditLogger.ANCHOR_EVENT, saved.getValue().eventType());
        assertEquals("seq=7 hash=" + HASH, saved.getValue().details());
        assertEquals("", file.toString(), "anchors go to the database only, so they cannot feed back into the file");
    }

    @Test
    void aSinkWithoutLinesOrWithoutAHeadHasNothingToAnchor() {
        assertFalse(logger.anchorFileHead());
        verify(repository, never()).save(any());
    }

    @Test
    void anchorsAreRecordedAutomaticallyEveryFewEvents() {
        java.util.concurrent.atomic.AtomicLong lines = new java.util.concurrent.atomic.AtomicLong();
        AuditFileSink counting = new AuditFileSink() {
            @Override public void append(String event) { lines.incrementAndGet(); }
            @Override public java.util.Optional<Head> head() { return java.util.Optional.of(new Head(lines.get(), HASH)); }
        };
        AuditLogger auto = new AuditLogger(repository, counting);
        for (int i = 0; i < 60; i++) auto.log("EVT", "worker:1", "n=" + i);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, org.mockito.Mockito.times(62)).save(saved.capture());                           // 60 events + 2 anchors (after the 25th and the 50th)
        assertEquals(2, saved.getAllValues().stream().filter(l -> l.eventType().equals(AuditLogger.ANCHOR_EVENT)).count());
    }

    @Test
    void anchorsAreReadBackFromTheDatabaseAndMalformedRowsAreIgnored() {
        org.mockito.Mockito.when(repository.findByType(AuditLogger.ANCHOR_EVENT, 10)).thenReturn(java.util.List.of(
                new AuditLog(3L, AuditLogger.ANCHOR_EVENT, "system", "seq=40 hash=" + HASH, null),
                new AuditLog(2L, AuditLogger.ANCHOR_EVENT, "system", "seq=oops hash=zzz", null),
                new AuditLog(1L, AuditLogger.ANCHOR_EVENT, "system", "seq=15 hash=" + HASH, null)));
        java.util.List<ChainedAuditFileSink.Anchor> anchors = logger.anchors(10);
        assertEquals(java.util.List.of(40L, 15L), anchors.stream().map(ChainedAuditFileSink.Anchor::seq).toList());
    }
}
