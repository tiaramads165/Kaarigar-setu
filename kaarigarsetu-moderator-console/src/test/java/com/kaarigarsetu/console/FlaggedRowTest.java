package com.kaarigarsetu.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlaggedRowTest {
    @Test
    void explanationNamesTheEvidenceInPlainLanguage() {
        FlaggedRow row = new FlaggedRow(1, 2, 0.8, "{\"ip_frequency\":9.0,\"reciprocity_ratio\":0.9}", 3, 4, "Wiring", "2026-10-07T10:00:00");
        String text = row.explain();
        assertTrue(text.contains("same network address"));
        assertTrue(text.contains("mutual"));
        assertTrue(text.contains("0.80"));
        assertFalse(text.contains("ip_frequency"), "raw field names are translated");
    }

    @Test
    void anMlOutageNoteIsExplainedAndMalformedEvidenceDoesNotThrow() {
        assertTrue(new FlaggedRow(1, 2, 0.5, "{\"note\":\"ml_unavailable\"}", 3, 4, "x", "t").explain().contains("sent to a human"));
        assertTrue(new FlaggedRow(1, 2, 0.5, "not json", 3, 4, "x", "t").explain().contains("no structured evidence"));
        assertTrue(new FlaggedRow(1, 2, 0.5, "", 3, 4, "x", "t").explain().contains("no structured evidence"));
    }
}
