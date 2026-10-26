package com.kaarigarsetu.console;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Map;

/** One row of the review queue: ids and facts only. The backend never sends phone numbers or names to the console. */
public record FlaggedRow(long flagId, long attestationId, double anomalyScore, String features, long workerId,
                         long employerId, String jobRole, String createdAt) {

    private static final Map<String, String> LABELS = Map.of(
            "ip_frequency", "Attestations from the same network address in 24 h",
            "device_frequency", "Attestations from the same device in 24 h",
            "minutes_since_previous", "Minutes since this employer's previous attestation",
            "reciprocity_ratio", "Share of this employer's vouches that are mutual (0 = none, 1 = all)");

    /** A plain-language explanation for the moderator of why the model flagged this attestation. */
    public String explain() {
        StringBuilder text = new StringBuilder();
        text.append("Attestation ").append(attestationId).append(": employer ").append(employerId).append(" vouching for worker ")
                .append(workerId).append(" (\"").append(jobRole).append("\").\n");
        text.append("Anomaly score ").append(String.format("%.2f", anomalyScore))
                .append(" (0 = ordinary, 1 = very unusual). Flagged at ").append(createdAt).append(".\n\nWhat the model saw:\n");
        try {
            JsonObject json = JsonParser.parseString(features).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getKey().equals("note")) {
                    text.append("  * Note: ").append(entry.getValue().getAsString().equals("ml_unavailable")
                            ? "the scoring service was unavailable, so this was sent to a human instead of being auto-approved."
                            : entry.getValue().getAsString()).append('\n');
                } else {
                    text.append("  * ").append(LABELS.getOrDefault(entry.getKey(), entry.getKey())).append(": ").append(entry.getValue().getAsString()).append('\n');
                }
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException malformed) {
            text.append("  (no structured evidence was recorded)\n");
        }
        return text.toString();
    }
}
