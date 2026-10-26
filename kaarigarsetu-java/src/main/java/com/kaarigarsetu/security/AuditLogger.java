package com.kaarigarsetu.security;

import com.kaarigarsetu.model.AuditLog;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Single chokepoint that records sensitive events in both durable audit targets (the append-only, hash-chained
 * audit_log table and the tamper-evident audit file). Inputs are neutralised first: control characters are
 * stripped so a crafted value cannot forge extra log lines, and phone-number-shaped text is masked so the audit
 * trail never becomes a second copy of personal data.
 */
public class AuditLogger {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^ ]]+");
    private static final Pattern PHONE_LIKE = Pattern.compile("(?<![0-9])(\\+?91[\\s-]?)?[6-9][0-9]{9}(?![0-9])");
    private static final int MAX_FIELD = 500;
    /** Event type of the rows that pin the file's newest line in the database. */
    public static final String ANCHOR_EVENT = "AUDIT_FILE_ANCHOR";
    private static final int ANCHOR_EVERY = 25;
    private static final Pattern ANCHOR_DETAILS = Pattern.compile("^seq=(\\d+) hash=([0-9a-f]{64})$");

    private final AuditLogRepository repository;
    private final AuditFileSink fileSink;
    private final Clock clock;
    private long lastAnchoredSeq;
    private int sinceAnchor;

    public AuditLogger(AuditLogRepository repository, AuditFileSink fileSink) {
        this(repository, fileSink, Clock.systemUTC());
    }

    AuditLogger(AuditLogRepository repository, AuditFileSink fileSink, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.fileSink = Objects.requireNonNull(fileSink, "fileSink must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void log(String eventType, String actor, String details) {
        if (eventType == null || actor == null || details == null || eventType.isBlank() || actor.isBlank() || details.isBlank()) {
            throw new IllegalArgumentException("audit event fields must not be blank");
        }
        String type = clean(eventType);
        String who = clean(actor);
        String what = clean(details);
        repository.save(new AuditLog(null, type, who, what, LocalDateTime.now(clock)));
        try {
            fileSink.append(type + " actor=" + who + " details=" + what);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to append audit log", exception);
        }
        boolean due;
        synchronized (this) {
            due = ++sinceAnchor >= ANCHOR_EVERY;
        }
        if (due) anchorFileHead();
    }

    /**
     * Pins the file's newest line in the database audit table (append-only and hash-chained, a separate store). Called every {@value #ANCHOR_EVERY} events,
     * on a timer, and after each audit check. Returns false when there is nothing new to pin. It writes only to the database, never to the file, so it cannot
     * feed back into itself.
     */
    public synchronized boolean anchorFileHead() {
        try {
            java.util.Optional<AuditFileSink.Head> head = fileSink.head();
            if (head.isEmpty() || head.get().seq() <= lastAnchoredSeq) return false;
            repository.save(new AuditLog(null, ANCHOR_EVENT, "system", "seq=" + head.get().seq() + " hash=" + head.get().lineHash(), LocalDateTime.now(clock)));
            lastAnchoredSeq = head.get().seq();
            sinceAnchor = 0;
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to anchor the audit file", exception);
        }
    }

    /** Anchors recorded in the database, newest first. Rows that do not parse are ignored (they cannot be forged into acceptance, only missed). */
    public java.util.List<ChainedAuditFileSink.Anchor> anchors(int limit) {
        java.util.List<ChainedAuditFileSink.Anchor> anchors = new java.util.ArrayList<>();
        for (AuditLog row : repository.findByType(ANCHOR_EVENT, limit)) {
            java.util.regex.Matcher m = ANCHOR_DETAILS.matcher(row.details());
            if (m.matches()) anchors.add(new ChainedAuditFileSink.Anchor(Long.parseLong(m.group(1)), m.group(2)));
        }
        return anchors;
    }

    /** How long ago (database clock) the newest anchor was written, or empty if none yet. */
    public java.util.Optional<java.time.Duration> newestAnchorAge() {
        return repository.newestAgeMinutes(ANCHOR_EVENT).map(java.time.Duration::ofMinutes);
    }

    public AuditLogRepository repository() {
        return repository;
    }

    public java.util.Optional<AuditLogRepository.ChainStatus> databaseChain() {
        return repository.verifyChain();
    }

    static String clean(String value) {
        // the regexes below run over caller-controlled text (usernames, headers): bound the work first, then bound the result
        if (value.length() > 4 * MAX_FIELD) value = value.substring(0, 4 * MAX_FIELD);
        String masked = PHONE_LIKE.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll("[phone]");
        return masked.length() > MAX_FIELD ? masked.substring(0, MAX_FIELD) : masked;
    }
}
