package com.kaarigarsetu.moderator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityMonitorTest {
    private static List<SecurityMonitor.Alert> alerts(Map<String, Long> hour) {
        return SecurityMonitor.alerts(hour, true, "ok", Optional.of(true), Optional.of(Duration.ofMinutes(3)), true);
    }

    @Test
    void aQuietSystemRaisesNothing() {
        assertEquals(List.of(), alerts(Map.of()));
        assertEquals("OK", SecurityMonitor.status(alerts(Map.of("LOGIN_FAILED", 2L, "RATE_LIMITED", 4L))));
    }

    @Test
    void everyRuleWarnsAtItsThresholdAndEscalatesAtTheHighOne() {
        for (SecurityMonitor.Rule rule : SecurityMonitor.RULES) {
            assertEquals(List.of(), alerts(Map.of(rule.eventType(), (long) rule.warnAt() - 1)), rule.eventType() + " just below the threshold");
            var warn = alerts(Map.of(rule.eventType(), (long) rule.warnAt()));
            assertEquals(1, warn.size(), rule.eventType());
            assertEquals("WARN", warn.get(0).level(), rule.eventType());
            var high = alerts(Map.of(rule.eventType(), (long) rule.highAt()));
            assertEquals("HIGH", high.get(0).level(), rule.eventType());
            assertTrue(high.get(0).detail().contains(rule.highAt() + " in the last hour"), high.get(0).detail());
        }
    }

    @Test
    void aFailedAuditCheckIsCriticalAndSaysWhat() {
        var fileBroken = SecurityMonitor.alerts(Map.of(), false, "file has 40 lines but the database anchored line 90: rolled back or truncated", Optional.of(true), Optional.empty(), true);
        assertEquals("CRITICAL", fileBroken.get(0).level());
        assertTrue(fileBroken.get(0).detail().contains("rolled back"));
        assertEquals("CRITICAL", SecurityMonitor.status(fileBroken));
        var databaseBroken = SecurityMonitor.alerts(Map.of(), true, "ok", Optional.of(false), Optional.empty(), false);
        assertEquals("CRITICAL", databaseBroken.get(0).level());
    }

    @Test
    void aStalledAnchorWarnsOnlyWhenThereIsSomethingNewerThanTheAnchor() {
        var stale = SecurityMonitor.alerts(Map.of(), true, "ok", Optional.of(true), Optional.of(Duration.ofMinutes(45)), true);
        assertEquals("Audit anchoring has stalled", stale.get(0).title());
        assertEquals(List.of(), SecurityMonitor.alerts(Map.of(), true, "ok", Optional.of(true), Optional.of(Duration.ofMinutes(45)), false), "nothing newer than the anchor: not a problem");
        assertEquals(List.of(), SecurityMonitor.alerts(Map.of(), true, "ok", Optional.empty(), Optional.empty(), true), "databases that cannot report simply add no alert");
    }

    @Test
    void severalProblemsAreAllReported() {
        var many = alerts(Map.of("LOGIN_FAILED", 50L, "CSRF_REJECTED", 4L));
        assertEquals(2, many.size());
        assertEquals("WARN", SecurityMonitor.status(many));
    }
}
