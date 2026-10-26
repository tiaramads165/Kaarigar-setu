package com.kaarigarsetu.security;

import java.io.IOException;

/** The file half of the audit trail. Implemented by the plain writer and by the tamper-evident chained writer. */
@FunctionalInterface
public interface AuditFileSink {
    /** Newest line of the file: its number and the SHA-256 of its text. Used to anchor the file in the database. */
    record Head(long seq, String lineHash) {
    }

    void append(String event) throws IOException;

    /** Empty when the sink cannot say (plain writer) or has no lines yet. */
    default java.util.Optional<Head> head() throws IOException {
        return java.util.Optional.empty();
    }
}
