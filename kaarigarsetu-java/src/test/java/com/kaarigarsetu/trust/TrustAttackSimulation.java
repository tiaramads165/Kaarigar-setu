package com.kaarigarsetu.trust;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adversarial simulation of the trust score: how many colluding (Sybil) employer accounts does an attacker need to push a worker into each band, and what
 * does each extra capability buy? It calls the production scoring code, so the numbers are the real ones, not a model of them.
 *
 * <p>The attacker is given every advantage the fraud model cannot take away: perfect ratings, "would hire again", a long job, and anomaly 0 (the
 * confirmations look perfectly ordinary to the Isolation Forest). What is varied is the standing of the fake employers.
 */
public final class TrustAttackSimulation {
    private TrustAttackSimulation() {
    }

    /** What the attacker has done to each fake employer account before using it. */
    public enum Capability {
        THROWAWAY("brand-new, unverified account", new ReputationScoring.EmployerStanding(false, 0, 0, 0.5)),
        WARMED_UP("has already given 5 approved confirmations (to other fake workers)", new ReputationScoring.EmployerStanding(false, 5, 0, 0.5)),
        WARMED_AND_WELL_CONNECTED("warmed up AND maximum PageRank standing", new ReputationScoring.EmployerStanding(false, 5, 0, 1.0)),
        VETTED_INSIDER("also vetted by a moderator (needs an insider, a bribe or a breach of the vetting process)", new ReputationScoring.EmployerStanding(true, 5, 0, 1.0));

        public final String description;
        public final ReputationScoring.EmployerStanding standing;

        Capability(String description, ReputationScoring.EmployerStanding standing) {
            this.description = description;
            this.standing = standing;
        }
    }

    static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    /** Score of a worker endorsed by {@code employers} fake employers, each confirming {@code perEmployer} times with perfect feedback. */
    public static ReputationScoring.Report attack(Capability capability, int employers, int perEmployer, int fraudRulings) {
        return attack(capability, employers, perEmployer, fraudRulings, ReputationScoring.Params.defaults());
    }

    /** The same attack against the scoring as it was before the vetting rule (excellentNeedsVettedEmployer = false). */
    public static ReputationScoring.Params withoutVettingRule() {
        ReputationScoring.Params d = ReputationScoring.Params.defaults();
        return new ReputationScoring.Params(d.priorMean(), d.priorWeight(), d.lowPrior(), d.fullConfidenceEmployers(), d.recencyDays(), d.recencyFloor(), d.leniencyStrength(),
                d.leniencyCap(), d.minRatedForCalibration(), d.integrityPenalty(), d.integrityPenaltyCap(), d.unratedSatisfaction(), d.unratedWeightFactor(), false);
    }

    public static ReputationScoring.Report attack(Capability capability, int employers, int perEmployer, int fraudRulings, ReputationScoring.Params params) {
        List<ReputationScoring.Feedback> feedback = new ArrayList<>();
        Map<Long, ReputationScoring.EmployerStanding> standing = new HashMap<>();
        long id = 1;
        for (long e = 1; e <= employers; e++) {
            ReputationScoring.EmployerStanding s = capability.standing;
            standing.put(e, new ReputationScoring.EmployerStanding(s.verified(), s.approvedGiven(), fraudRulings > 0 && e == 1 ? 1 : 0, s.networkStanding()));
            for (int n = 0; n < perEmployer; n++) {
                feedback.add(new ReputationScoring.Feedback(id++, 1L, e, 5, 5, 5, true, 30, NOW.minusSeconds(10L * 86400 + n * 3600L), 0.0));
            }
        }
        return ReputationScoring.score(feedback, standing, ReputationScoring.calibrate(feedback, params), fraudRulings, NOW, params);
    }

    /** Smallest number of fake employers (one confirmation each) that reaches the score, or -1 if even {@code limit} employers do not. */
    public static int employersNeeded(Capability capability, double target, int limit) {
        return employersNeeded(capability, target, limit, ReputationScoring.Params.defaults());
    }

    public static int employersNeeded(Capability capability, double target, int limit, ReputationScoring.Params params) {
        for (int k = 1; k <= limit; k++) {
            if (attack(capability, k, 1, 0, params).score().doubleValue() >= target) return k;
        }
        return -1;
    }

    public static String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("| What the attacker did to each fake employer | Fake employers needed for Good (65) | for Excellent (80): before the vetting rule | for Excellent (80): now |\n");
        out.append("|---|---|---|---|\n");
        for (Capability capability : Capability.values()) {
            out.append("| ").append(capability.description).append(" | ").append(label(employersNeeded(capability, 65, 30))).append(" | ")
                    .append(label(employersNeeded(capability, 80, 30, withoutVettingRule()))).append(" | ").append(label(employersNeeded(capability, 80, 30))).append(" |\n");
        }
        return out.toString();
    }

    private static String label(int employers) {
        return employers < 0 ? "never (30 tried)" : employers + (employers == 1 ? " employer" : " employers");
    }

    public static void main(String[] args) {
        System.out.println(markdown());
        System.out.println("One employer confirming many times (best case for the attacker, vetted insider):");
        for (int n : new int[]{1, 5, 20, 100}) System.out.println("  " + n + " confirmations -> " + attack(Capability.VETTED_INSIDER, 1, n, 0).score());
        System.out.println("A moderator rules one of three throwaway confirmations fraudulent:");
        System.out.println("  before: " + attack(Capability.THROWAWAY, 3, 1, 0).score() + "   after: " + attack(Capability.THROWAWAY, 3, 1, 1).score());
    }
}
