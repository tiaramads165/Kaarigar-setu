package com.kaarigarsetu.console;

import java.util.List;

/** What the moderator's Security tab shows: overall status, alerts, event counts, recent events and the audit-trail integrity line. */
public record SecurityOverview(String status, List<Alert> alerts, List<Count> counts, List<Event> recent, String integrity) {
    public record Alert(String level, String title, String detail) {
    }

    public record Count(String type, long lastHour, long last24h) {
    }

    public record Event(String at, String type, String actor, String details) {
    }

    /** Plain-language names for the event types the backend reports. */
    public static String describe(String type) {
        return switch (type) {
            case "LOGIN_FAILED" -> "Failed sign-ins";
            case "LOGIN_OTP_REQUESTED_UNKNOWN" -> "Codes asked for unregistered numbers";
            case "ATTESTATION_OTP_FAILED" -> "Wrong attestation codes";
            case "CSRF_REJECTED" -> "Requests rejected by CSRF protection";
            case "ACCESS_DENIED" -> "Role violations (403)";
            case "RATE_LIMITED" -> "Clients hitting rate limits";
            case "DEMO_LOGIN_REFUSED" -> "Demo sign-in refused";
            case "CREDENTIAL_REVOKED" -> "Credentials revoked";
            case "MODERATOR_DECISION" -> "Moderator decisions";
            case "ATTESTATION_DISPUTED" -> "Confirmations disputed by workers";
            case "MODERATOR_ACTION_PROPOSED" -> "Moderator proposals (waiting for a second person)";
            case "MODERATOR_ACTION_APPROVED" -> "Moderator proposals approved";
            case "MODERATOR_ACTION_REJECTED" -> "Moderator proposals rejected";
            case "REPLAY_SUSPECTED" -> "Voice answers matching an earlier recording or words";
            case "CHALLENGE_NOT_HEARD" -> "Answers where the spoken code was not heard";
            case "MODERATOR_SELF_APPROVAL_REFUSED" -> "Moderators refused approving their own proposal";
            default -> type;
        };
    }
}
