package com.kaarigarsetu.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilitiesTest {
    @TempDir
    Path directory;

    @Test
    void exportsCredentialFilesAndAppendsAuditEntries() throws Exception {
        CredentialExporter exporter = new CredentialExporter();
        Path json = exporter.exportJson(directory.resolve("credentials/passport.json"), "{\"signature\":\"abc\"}");
        Path pdf = exporter.exportPdf(directory.resolve("credentials/passport.pdf"), "signed credential");
        AuditLogWriter writer = new AuditLogWriter(directory.resolve("logs/audit.log"),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        writer.append("CREDENTIAL_EXPORTED");
        assertEquals("{\"signature\":\"abc\"}", Files.readString(json));
        assertTrue(Files.readString(pdf).startsWith("%PDF-1.4"));
        assertEquals("2026-01-01T00:00:00Z CREDENTIAL_EXPORTED" + System.lineSeparator(),
                Files.readString(directory.resolve("logs/audit.log")));
    }
}
