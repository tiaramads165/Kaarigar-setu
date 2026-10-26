package com.kaarigarsetu.trust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trust ("credit") score of a worker: what employers said about the work, weighted by how much each employer can be believed.
 * A pure function (no database, no network) so every rule below can be tested and tuned on its own. Full description: docs/TRUST_SCORE.md.
 *
 * <p><b>Per piece of feedback</b> (one approved attestation):
 * <ul>
 *   <li><b>Satisfaction s</b> 0..1 from the employer's answers: quality of work 40 %, punctuality and reliability 25 %, conduct and honesty 20 %,
 *       would hire again 15 % (only the answers actually given count; a ratings-free confirmation counts as a neutral 0.55 at half weight);</li>
 *   <li><b>Leniency calibration</b>: an employer whose average is well above everyone's (an "always five stars" rater) has a little taken off,
 *       one who is well below has a little added, so a generous or harsh habit does not decide a worker's score;</li>
 *   <li><b>Weight w</b> = employer credibility x recency x engagement x (1 - half the fraud-anomaly score).</li>
 * </ul>
 * <b>Employer credibility</b> 0.1..1: base 0.40, +0.15 if verified, +0.20 for track record (up to 5 approved confirmations), +0.25 for network standing (PageRank
 * over the employer-worker graph), then scaled down by 30 % for every confirmation of theirs a moderator ruled fraudulent.
 *
 * <p><b>Combining</b>: a Bayesian average pulls few or weak pieces of evidence towards a prior of 0.60 (<code>rep = (k*0.60 + sum(w*s)) / (k + sum(w))</code>,
 * k = 1.5); a confidence factor C = min(1, distinct employers / 3) blends it with a low prior of 0.25, so one employer, however glowing, cannot make a top score:
 * <code>score = 100 * (C*rep + (1-C)*0.25)</code>, minus 5 for each confirmation about the worker that a moderator ruled fraudulent (at most 15).
 */
public final class ReputationScoring {
    private ReputationScoring() {}

    /** Every tunable number in one place. */
    public record Params(double priorMean, double priorWeight, double lowPrior, int fullConfidenceEmployers, double recencyDays, double recencyFloor,
                         double leniencyStrength, double leniencyCap, int minRatedForCalibration, double integrityPenalty, double integrityPenaltyCap,
                         double unratedSatisfaction, double unratedWeightFactor, boolean excellentNeedsVettedEmployer) {
        public static Params defaults() {
            return new Params(0.60, 1.5, 0.25, 3, 365.0, 0.15, 0.5, 0.10, 3, 5.0, 15.0, 0.55, 0.5, true);
        }
    }

    /** Just under the Excellent band (80). */
    static final double HELD_BELOW_EXCELLENT = 79.99;

    /** One approved attestation about the worker. Ratings are 1..5 or null; wouldHireAgain may be null. */
    public record Feedback(long attestationId, long workerId, long employerId, Integer quality, Integer punctuality, Integer conduct,
                           Boolean wouldHireAgain, Integer durationDays, Instant at, double anomaly) {}

    /**
     * What is known about an employer. {@code verified} means a MODERATOR vetted this employer (an identity or business check done outside the application);
     * it is never granted automatically, because anything an attacker can obtain by registering, confirming once and waiting is no evidence of honesty.
     * networkStanding is 0..1 (0.5 when unknown).
     */
    public record EmployerStanding(boolean verified, int approvedGiven, int fraudUpheld, double networkStanding) {
        public static EmployerStanding unknown() {
            return new EmployerStanding(false, 0, 0, 0.5);
        }
    }

    /** The score and the reasons behind it. Null numbers mean "not enough information", never zero. */
    public record Report(BigDecimal score, TrustBand band, double confidence, int feedbackCount, int employerCount, Double satisfaction,
                         Map<String, Double> dimensionAverages, Double wouldHireAgainRate, Double averageEmployerCredibility,
                         Double monthsSinceLastFeedback, Double consistency, int integrityFlags, List<String> tips) {}

    // ---------------------------------------------------------------- building blocks (public so each can be tested alone)

    /**
     * Edge weight for the endorsement graph that PageRank runs on: {@code h * e^(-age/365) * (1 - 0.5 * anomaly)}, kept in [0.05, 1].
     * h is 1.0 when the employer would hire again, 0.8 when that was not stated, 0.4 when they would not (see {@code AttestationRules.edgeWeight}).
     */
    public static double endorsementWeight(Feedback f, Instant now) {
        double ageDays = Math.max(0.0, java.time.Duration.between(f.at(), now).toSeconds() / 86400.0);
        double anomaly = Math.min(1.0, Math.max(0.0, f.anomaly()));
        double weight = com.kaarigarsetu.attestation.AttestationRules.edgeWeight(f.wouldHireAgain()).doubleValue() * Math.exp(-ageDays / 365.0) * (1.0 - 0.5 * anomaly);
        return Math.min(1.0, Math.max(0.05, weight));
    }

    /** Satisfaction 0..1 of one piece of feedback, or null when the employer gave no ratings and no would-hire answer. */
    public static Double satisfaction(Feedback f) {
        double sum = 0, weight = 0;
        if (f.quality() != null) { sum += 0.40 * ((f.quality() - 1) / 4.0); weight += 0.40; }
        if (f.punctuality() != null) { sum += 0.25 * ((f.punctuality() - 1) / 4.0); weight += 0.25; }
        if (f.conduct() != null) { sum += 0.20 * ((f.conduct() - 1) / 4.0); weight += 0.20; }
        if (f.wouldHireAgain() != null) { sum += 0.15 * (f.wouldHireAgain() ? 1.0 : 0.0); weight += 0.15; }
        return weight == 0 ? null : sum / weight;
    }

    public static double employerCredibility(EmployerStanding s) {
        double raw = 0.40 + (s.verified() ? 0.15 : 0) + 0.20 * Math.min(1.0, s.approvedGiven() / 5.0) + 0.25 * clamp(s.networkStanding(), 0, 1);
        return clamp(raw * Math.max(0.2, 1.0 - 0.3 * s.fraudUpheld()), 0.1, 1.0);
    }

    /**
     * How much to take off each employer's satisfaction values: half the amount by which their average exceeds the average of all rated feedback,
     * limited to the cap. Only employers with enough rated feedback are calibrated.
     */
    public static Map<Long, Double> calibrate(List<Feedback> all, Params p) {
        Map<Long, List<Double>> byEmployer = new HashMap<>();
        double total = 0;
        int count = 0;
        for (Feedback f : all) {
            Double s = satisfaction(f);
            if (s == null) continue;
            byEmployer.computeIfAbsent(f.employerId(), k -> new ArrayList<>()).add(s);
            total += s;
            count++;
        }
        Map<Long, Double> offsets = new HashMap<>();
        if (count == 0) return offsets;
        double population = total / count;
        for (Map.Entry<Long, List<Double>> e : byEmployer.entrySet()) {
            if (e.getValue().size() < p.minRatedForCalibration()) continue;
            double mean = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(population);
            offsets.put(e.getKey(), clamp(p.leniencyStrength() * (mean - population), -p.leniencyCap(), p.leniencyCap()));
        }
        return offsets;
    }

    public static double recency(Instant at, Instant now, Params p) {
        double days = Math.max(0, Duration.between(at, now).toSeconds() / 86_400.0);
        return Math.max(p.recencyFloor(), Math.exp(-days / p.recencyDays()));
    }

    /** Longer engagements say more: 0.6 for a one-day job rising to 1.0 at 30 days or more; 0.8 when the length is unknown. */
    public static double engagement(Integer durationDays) {
        return durationDays == null ? 0.8 : 0.6 + 0.4 * Math.min(1.0, Math.max(0, durationDays) / 30.0);
    }

    // ---------------------------------------------------------------- the score

    public static Report score(List<Feedback> feedback, Map<Long, EmployerStanding> standing, Map<Long, Double> leniency, int integrityFlags,
                               Instant now, Params p) {
        if (feedback.isEmpty()) {
            BigDecimal none = BigDecimal.ZERO.setScale(2);
            return new Report(none, TrustBand.NO_HISTORY, 0.0, 0, 0, null, Map.of(), null, null, null, null, integrityFlags,
                    List.of("No employer has confirmed your work yet. Ask someone you have worked for to confirm it from their dashboard."));
        }
        double sumW = 0, sumWS = 0, sumCred = 0, unrated = 0;
        List<Double> satisfactions = new ArrayList<>();
        Map<Long, Double> employers = new LinkedHashMap<>();
        Instant latest = feedback.get(0).at();
        double[] ratingSum = new double[3];
        int[] ratingCount = new int[3];
        int hireYes = 0, hireNo = 0;
        for (Feedback f : feedback) {
            Double rated = satisfaction(f);
            boolean isUnrated = rated == null;
            double s = isUnrated ? p.unratedSatisfaction() : clamp(rated - leniency.getOrDefault(f.employerId(), 0.0), 0, 1);
            double cred = employerCredibility(standing.getOrDefault(f.employerId(), EmployerStanding.unknown()));
            double w = cred * recency(f.at(), now, p) * engagement(f.durationDays()) * (1.0 - 0.5 * clamp(f.anomaly(), 0, 1)) * (isUnrated ? p.unratedWeightFactor() : 1.0);
            sumW += w;
            sumWS += w * s;
            sumCred += cred;
            satisfactions.add(s);
            employers.merge(f.employerId(), cred, Math::max);
            if (isUnrated) unrated++;
            if (f.at().isAfter(latest)) latest = f.at();
            if (f.quality() != null) { ratingSum[0] += f.quality(); ratingCount[0]++; }
            if (f.punctuality() != null) { ratingSum[1] += f.punctuality(); ratingCount[1]++; }
            if (f.conduct() != null) { ratingSum[2] += f.conduct(); ratingCount[2]++; }
            if (f.wouldHireAgain() != null) { if (f.wouldHireAgain()) hireYes++; else hireNo++; }
        }
        double reputation = (p.priorWeight() * p.priorMean() + sumWS) / (p.priorWeight() + sumW);
        double confidence = Math.min(1.0, employers.size() / (double) p.fullConfidenceEmployers());
        double raw = 100.0 * (confidence * reputation + (1.0 - confidence) * p.lowPrior());
        double penalty = Math.min(p.integrityPenaltyCap(), p.integrityPenalty() * integrityFlags);
        BigDecimal score = BigDecimal.valueOf(clamp(raw - penalty, 0, 100)).setScale(2, RoundingMode.HALF_UP);
        // Sybil defence: any number of fake employer accounts can look like "three different employers", so the top band needs a trust anchor an attacker
        // cannot create by registering: at least one confirming employer that a moderator has vetted.
        boolean anyVetted = employers.keySet().stream().anyMatch(id -> standing.getOrDefault(id, EmployerStanding.unknown()).verified());
        boolean heldBack = p.excellentNeedsVettedEmployer() && !anyVetted && score.doubleValue() > HELD_BELOW_EXCELLENT;
        if (heldBack) score = BigDecimal.valueOf(HELD_BELOW_EXCELLENT).setScale(2, RoundingMode.HALF_UP);

        Map<String, Double> dimensions = new LinkedHashMap<>();
        String[] names = {"quality", "punctuality", "conduct"};
        for (int i = 0; i < 3; i++) if (ratingCount[i] > 0) dimensions.put(names[i], round1(ratingSum[i] / ratingCount[i]));
        Double hireRate = hireYes + hireNo == 0 ? null : hireYes / (double) (hireYes + hireNo);
        double months = Math.max(0, Duration.between(latest, now).toSeconds() / (86_400.0 * 30.4));
        Double consistency = null;
        if (satisfactions.size() >= 2) {
            double mean = satisfactions.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double sd = Math.sqrt(satisfactions.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
            consistency = round1(100.0 * (1.0 - Math.min(1.0, 2.0 * sd)));
        }
        double averageCredibility = sumCred / feedback.size();
        double unratedShare = unrated / feedback.size();

        List<String> tips = new ArrayList<>();
        if (heldBack) tips.add("Your score is held just below Excellent until one of the employers who confirmed your work has been verified by Kaarigar Setu.");
        if (employers.size() < p.fullConfidenceEmployers()) {
            int more = p.fullConfidenceEmployers() - employers.size();
            tips.add("Ask " + more + " more different employer" + (more == 1 ? "" : "s") + " to confirm your work. Confirmations from " + p.fullConfidenceEmployers()
                    + " or more different employers count fully.");
        }
        if (months > 9) tips.add("Your latest confirmation is about " + Math.round(months) + " months old. Recent work counts more, so ask a recent employer to confirm.");
        if (unratedShare > 0.5) tips.add("Ask employers to rate the work (quality, punctuality, conduct) so that it counts fully.");
        if (dimensions.size() >= 2) {
            Map.Entry<String, Double> lowest = dimensions.entrySet().stream().min(Map.Entry.comparingByValue()).get();
            if (lowest.getValue() <= 3.5) tips.add("Employers rated your " + label(lowest.getKey()) + " lowest (" + lowest.getValue() + " out of 5). Improving it raises your score.");
        }
        if (hireRate != null && hireRate < 0.8) tips.add("Some employers said they would not hire you again.");
        if (averageCredibility < 0.6) tips.add("Confirmations from employers with a longer track record count for more.");
        if (integrityFlags > 0) tips.add("A moderator found " + integrityFlags + " confirmation" + (integrityFlags == 1 ? "" : "s") + " about you to be fraudulent, which lowers your score.");
        if (tips.size() > 4) tips = new ArrayList<>(tips.subList(0, 4));

        return new Report(score, TrustBand.of(score, true), round2(confidence), feedback.size(), employers.size(), round3(sumWS / sumW), dimensions, hireRate == null ? null : round3(hireRate),
                round3(averageCredibility), round1(months), consistency, integrityFlags, List.copyOf(tips));
    }

    private static String label(String dimension) {
        return switch (dimension) {
            case "quality" -> "quality of work";
            case "punctuality" -> "punctuality and reliability";
            default -> "conduct and honesty";
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
