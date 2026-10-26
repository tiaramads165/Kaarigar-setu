package com.kaarigarsetu.moderator;

import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.AuditLogRepository;
import com.kaarigarsetu.security.ChainedAuditFileSink;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a moderator needs to see to notice an attack: how many security events of each kind happened recently, whether any threshold was crossed, and
 * whether the audit trail itself is intact. It only reads the audit trail the application already writes, so it adds no new data collection.
 *
 * <p>Counts of CSRF, access-denied and rate-limit events mean "distinct clients per minute" (see SecurityEvents), so the thresholds below are deliberately low.
 */
public final class SecurityMonitor {
    /** Event types shown on the panel, in display order. */
    public static final List<String> TRACKED = List.of("LOGIN_FAILED", "LOGIN_OTP_REQUESTED_UNKNOWN", "ATTESTATION_OTP_FAILED", "CSRF_REJECTED", "ACCESS_DENIED",
            "RATE_LIMITED", "DEMO_LOGIN_REFUSED", "CREDENTIAL_REVOKED", "MODERATOR_DECISION", "ATTESTATION_DISPUTED",
            "MODERATOR_ACTION_PROPOSED", "MODERATOR_ACTION_APPROVED", "MODERATOR_ACTION_REJECTED", "MODERATOR_SELF_APPROVAL_REFUSED",
            "REPLAY_SUSPECTED", "CHALLENGE_NOT_HEARD");
    static final Duration ANCHOR_STALE_AFTER = Duration.ofMinutes(30);

    public record Alert(String level, String title, String detail) {
    }

    /** One rule: at {@code warnAt} events in the last hour raise a warning, at {@code highAt} a high-severity alert. */
    record Rule(String eventType, int warnAt, int highAt, String title, String meaning) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("LOGIN_FAILED", 10, 40, "Repeated failed sign-ins", "someone may be guessing codes or passwords"),
            new Rule("LOGIN_OTP_REQUESTED_UNKNOWN", 15, 60, "Codes requested for unregistered numbers", "someone may be probing which numbers have accounts"),
            new Rule("ATTESTATION_OTP_FAILED", 8, 30, "Wrong attestation codes", "an employer account may be guessing confirmation codes"),
            new Rule("CSRF_REJECTED", 3, 15, "Requests rejected by CSRF protection", "a cross-site attack, or a broken client, is hitting the API"),
            new Rule("ACCESS_DENIED", 5, 20, "Signed-in users reaching for another role's endpoints", "a user may be probing for privilege escalation"),
            new Rule("RATE_LIMITED", 15, 100, "Clients hitting rate limits", "automated abuse (sign-up flooding, code guessing, scraping)"),
            new Rule("DEMO_LOGIN_REFUSED", 1, 5, "Demo sign-in attempts refused", "demo sign-in is being tried where it is switched off"),
            new Rule("MODERATOR_SELF_APPROVAL_REFUSED", 1, 5, "A moderator tried to approve their own proposal", "someone is testing the two-person rule, or a moderator login is being misused"),
            new Rule("REPLAY_SUSPECTED", 1, 5, "Voice answers that match an earlier recording or earlier words", "someone may be playing recordings into the assessment, or sharing answers between accounts"),
            new Rule("ATTESTATION_DISPUTED", 8, 30, "Many confirmations disputed", "workers may be trying to hide ratings, or an employer is posting unfair ones"));

    private final AuditLogger audit;
    private final ChainedAuditFileSink file;
    private final Clock clock;

    public SecurityMonitor(AuditLogger audit, ChainedAuditFileSink file, Clock clock) {
        this.audit = audit;
        this.file = file;
        this.clock = clock;
    }

    /** Pure rule evaluation, separate from I/O so every threshold can be tested. */
    static List<Alert> alerts(Map<String, Long> lastHour, boolean fileIntact, String fileDetail, Optional<Boolean> databaseIntact, Optional<Duration> anchorAge,
                              boolean fileHasUnanchoredLines) {
        List<Alert> alerts = new ArrayList<>();
        if (!fileIntact) alerts.add(new Alert("CRITICAL", "Audit file integrity check FAILED", fileDetail + ". Treat the audit trail as compromised and preserve the files."));
        if (databaseIntact.isPresent() && !databaseIntact.get()) alerts.add(new Alert("CRITICAL", "Database audit chain FAILED", "the append-only audit table no longer verifies."));
        for (Rule rule : RULES) {
            long n = lastHour.getOrDefault(rule.eventType(), 0L);
            if (n >= rule.highAt()) alerts.add(new Alert("HIGH", rule.title(), n + " in the last hour: " + rule.meaning() + "."));
            else if (n >= rule.warnAt()) alerts.add(new Alert("WARN", rule.title(), n + " in the last hour: " + rule.meaning() + "."));
        }
        if (anchorAge.isPresent() && anchorAge.get().compareTo(ANCHOR_STALE_AFTER) > 0 && fileHasUnanchoredLines) {
            alerts.add(new Alert("WARN", "Audit anchoring has stalled", "the newest anchor in the database is " + anchorAge.get().toMinutes() + " minutes old, so a file rollback would go unnoticed for longer."));
        }
        return alerts;
    }

    static String status(List<Alert> alerts) {
        if (alerts.stream().anyMatch(a -> a.level().equals("CRITICAL"))) return "CRITICAL";
        return alerts.isEmpty() ? "OK" : "WARN";
    }

    public Map<String, Object> report() throws IOException {
        AuditLogRepository repository = audit.repository();
        Map<String, Long> hour = repository.countByType(60, TRACKED);
        Map<String, Long> day = repository.countByType(24 * 60, TRACKED);

        var anchors = audit.anchors(500);
        ChainedAuditFileSink.VerifyResult fileResult = file.verify(anchors);
        Optional<Boolean> databaseIntact = audit.databaseChain().map(AuditLogRepository.ChainStatus::valid);
        Optional<Duration> anchorAge = audit.newestAnchorAge();
        long newestAnchored = anchors.stream().mapToLong(ChainedAuditFileSink.Anchor::seq).max().orElse(0);
        List<Alert> alerts = alerts(hour, fileResult.valid(), fileResult.reason(), databaseIntact, anchorAge, fileResult.lines() > newestAnchored);

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String type : TRACKED) counts.put(type, Map.of("lastHour", hour.getOrDefault(type, 0L), "last24h", day.getOrDefault(type, 0L)));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (AuditLog row : repository.recent(TRACKED, 40)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("at", row.timestamp() == null ? "" : row.timestamp().toString());
            entry.put("type", row.eventType());
            entry.put("actor", row.actor());
            entry.put("details", row.details());
            recent.add(entry);
        }
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("fileChainValid", fileResult.valid());
        integrity.put("fileLines", fileResult.lines());
        integrity.put("fileDetail", fileResult.reason());
        integrity.put("anchorsChecked", anchors.size());
        databaseIntact.ifPresent(ok -> integrity.put("databaseChainValid", ok));
        anchorAge.ifPresent(age -> integrity.put("newestAnchorAgeMinutes", age.toMinutes()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status(alerts));
        body.put("alerts", alerts);
        body.put("counts", counts);
        body.put("integrity", integrity);
        body.put("recent", recent);
        return body;
    }
}
