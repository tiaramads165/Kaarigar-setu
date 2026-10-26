package com.kaarigarsetu.util;

import com.kaarigarsetu.security.AuditFileSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Appends a simple, timestamped audit trail for the Phase 2 file-handling requirement. */
public class AuditLogWriter implements AuditFileSink {
    private final Path logFile;
    private final Clock clock;

    public AuditLogWriter(Path logFile) {
        this(logFile, Clock.systemUTC());
    }

    public AuditLogWriter(Path logFile, Clock clock) {
        this.logFile = Objects.requireNonNull(logFile, "logFile must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public synchronized void append(String event) throws IOException {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event must not be blank");
        }
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(logFile, Instant.now(clock) + " " + event.strip() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
