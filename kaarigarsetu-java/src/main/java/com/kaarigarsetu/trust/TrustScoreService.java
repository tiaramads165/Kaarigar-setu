package com.kaarigarsetu.trust;

import com.google.gson.JsonObject;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.util.Json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recomputes every worker's trust score from the employers' structured feedback (see {@link ReputationScoring} and docs/TRUST_SCORE.md).
 *
 * <p>Steps: load all approved feedback; calibrate each employer's leniency; build the employer-worker graph (edge weight = satisfaction x recency x
 * anomaly discount, plus a weaker reverse edge so well-connected employers earn standing) and ask the ML service for PageRank, which gives each employer a
 * network standing; score each worker; store score, band and the explanation together. If the ML service is unreachable the scores are still computed,
 * with a neutral standing for every employer, and the gap is audited: a confirmation is never lost because PageRank was down.
 */
public class TrustScoreService {
    private static final double REVERSE_EDGE_FACTOR = 0.5;

    private final AttestationDao attestationDao;
    private final WorkerDao workerDao;
    private final MlGateway ml;
    private final AuditLogger audit;
    private final Clock clock;
    private final ReputationScoring.Params params;

    public TrustScoreService(AttestationDao attestationDao, WorkerDao workerDao, MlGateway ml, AuditLogger audit, Clock clock) {
        this(attestationDao, workerDao, ml, audit, clock, ReputationScoring.Params.defaults());
    }

    public TrustScoreService(AttestationDao attestationDao, WorkerDao workerDao, MlGateway ml, AuditLogger audit, Clock clock, ReputationScoring.Params params) {
        this.attestationDao = attestationDao;
        this.workerDao = workerDao;
        this.ml = ml;
        this.audit = audit;
        this.clock = clock;
        this.params = params;
    }

    /** @return the stored 0-100 trust score per worker id. */
    public Map<Long, BigDecimal> recompute() {
        Instant now = clock.instant();
        List<ReputationScoring.Feedback> all = attestationDao.findApprovedFeedback().stream().map(r -> toFeedback(r, now)).toList();
        Map<Long, Double> leniency = ReputationScoring.calibrate(all, params);
        List<Long> activeWorkers = workerDao.findAllActiveIds();

        Map<Long, Double> networkStanding = networkStanding(all, leniency, now, activeWorkers);
        Map<Long, Double> workerStanding = workerNetworkStanding(all, now);
        Map<Long, AttestationDao.EmployerActivity> activity = attestationDao.findEmployerActivity().stream()
                .collect(Collectors.toMap(AttestationDao.EmployerActivity::employerId, a -> a, (a, b) -> a));
        Map<Long, ReputationScoring.EmployerStanding> standings = new HashMap<>();
        for (ReputationScoring.Feedback f : all) {
            AttestationDao.EmployerActivity a = activity.get(f.employerId());
            standings.putIfAbsent(f.employerId(), new ReputationScoring.EmployerStanding(a != null && a.verified(), a == null ? 0 : a.approvedGiven(),
                    a == null ? 0 : a.fraudUpheld(), networkStanding.getOrDefault(f.employerId(), 0.5)));
        }

        Map<Long, List<ReputationScoring.Feedback>> byWorker = all.stream().collect(Collectors.groupingBy(ReputationScoring.Feedback::workerId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Integer> integrity = attestationDao.findFraudUpheldByWorker();

        Map<Long, BigDecimal> stored = new LinkedHashMap<>();
        Set<Long> workers = new LinkedHashSet<>(activeWorkers);
        workers.addAll(byWorker.keySet());
        for (Long workerId : workers) {
            ReputationScoring.Report report = ReputationScoring.score(byWorker.getOrDefault(workerId, List.of()), standings, leniency, integrity.getOrDefault(workerId, 0), now, params);
            workerDao.updateTrustProfile(workerId, report.score(), report.band().name(), reportJson(report, workerStanding.get(workerId)));
            stored.put(workerId, report.score());
        }
        audit.log("TRUST_RECOMPUTED", "system", "workers=" + stored.size() + " feedback=" + all.size());
        return stored;
    }

    /** The report as JSON, plus the worker's PageRank network standing (0-100) when there is one. It is shown beside the trust score and never feeds into it. */
    private static String reportJson(ReputationScoring.Report report, Double networkStanding) {
        JsonObject json = Json.gson().toJsonTree(report).getAsJsonObject();
        if (networkStanding != null) json.addProperty("networkStanding", networkStanding);
        return Json.gson().toJson(json);
    }

    /**
     * Network standing per worker, the plain PageRank view: one edge employer -> worker per confirmation, weighted by
     * {@link ReputationScoring#endorsementWeight}; PageRank (damping 0.85) over the whole graph; scaled so the best node is 100.
     * Workers with no confirmation get no value. If the ML service is down the map is empty and the gap is audited.
     */
    private Map<Long, Double> workerNetworkStanding(List<ReputationScoring.Feedback> all, Instant now) {
        Map<Long, Double> standing = new HashMap<>();
        if (all.isEmpty()) return standing;
        Set<String> nodes = new LinkedHashSet<>();
        List<MlGateway.TrustEdge> edges = new ArrayList<>();
        for (ReputationScoring.Feedback f : all) {
            String employer = "E" + f.employerId(), worker = "W" + f.workerId();
            nodes.add(employer);
            nodes.add(worker);
            edges.add(new MlGateway.TrustEdge(employer, worker, ReputationScoring.endorsementWeight(f, now)));
        }
        try {
            Map<String, Double> scaled = ml.trustScores(new ArrayList<>(nodes), edges);
            for (ReputationScoring.Feedback f : all) {
                Double value = scaled.get("W" + f.workerId());
                if (value != null) standing.put(f.workerId(), BigDecimal.valueOf(Math.min(100.0, Math.max(0.0, value))).setScale(2, RoundingMode.HALF_UP).doubleValue());
            }
        } catch (ExternalServiceException unavailable) {
            standing.clear();
            audit.log("NETWORK_STANDING_UNAVAILABLE", "system", "PageRank network standing skipped for this run");
        }
        return standing;
    }

    private static ReputationScoring.Feedback toFeedback(AttestationDao.FeedbackRow r, Instant now) {
        Instant at = r.at().toInstant(ZoneOffset.UTC);
        return new ReputationScoring.Feedback(r.attestationId(), r.workerId(), r.employerId(), r.quality(), r.punctuality(), r.conduct(), r.wouldHireAgain(),
                r.durationDays(), at.isAfter(now) ? now : at, r.anomaly());
    }

    /** Network standing 0.3..1 per employer from PageRank, or an empty map (meaning neutral 0.5 for everyone) when the ML service cannot be reached. */
    private Map<Long, Double> networkStanding(List<ReputationScoring.Feedback> all, Map<Long, Double> leniency, Instant now, List<Long> activeWorkers) {
        Map<Long, Double> standing = new HashMap<>();
        if (all.isEmpty()) return standing;
        Set<String> nodes = new LinkedHashSet<>();
        activeWorkers.forEach(id -> nodes.add("W" + id));
        TrustGraphService adjacency = new TrustGraphService();                // the in-memory adjacency list (Collections requirement of the blueprint)
        List<MlGateway.TrustEdge> edges = new ArrayList<>();
        for (ReputationScoring.Feedback f : all) {
            Double rated = ReputationScoring.satisfaction(f);
            double s = rated == null ? params.unratedSatisfaction() : Math.min(1.0, Math.max(0.0, rated - leniency.getOrDefault(f.employerId(), 0.0)));
            double weight = Math.min(1.0, Math.max(0.05, (0.2 + 0.8 * s) * ReputationScoring.recency(f.at(), now, params) * (1.0 - 0.5 * Math.min(1.0, Math.max(0.0, f.anomaly())))));
            String employer = "E" + f.employerId(), worker = "W" + f.workerId();
            nodes.add(employer);
            nodes.add(worker);
            adjacency.addEdge(new Edge(employer, worker, BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP)));
            edges.add(new MlGateway.TrustEdge(employer, worker, weight));
            edges.add(new MlGateway.TrustEdge(worker, employer, Math.max(0.01, weight * REVERSE_EDGE_FACTOR)));
        }
        try {
            Map<String, Double> scaled = ml.trustScores(new ArrayList<>(nodes), edges);
            for (ReputationScoring.Feedback f : all) {
                double s = scaled.getOrDefault("E" + f.employerId(), 0.0) / 100.0;
                standing.put(f.employerId(), 0.3 + 0.7 * Math.min(1.0, Math.max(0.0, s)));
            }
        } catch (ExternalServiceException unavailable) {
            standing.clear();
            audit.log("TRUST_PAGERANK_UNAVAILABLE", "system", "employer standing neutral for this run");
        }
        return standing;
    }
}
