package com.kaarigarsetu.admin;

import com.kaarigarsetu.assessment.SkillProfile;
import com.kaarigarsetu.assessment.SkillScoring;
import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.DatabaseConfig;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.ml.MlServiceClient;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.util.Json;
import com.kaarigarsetu.web.AppContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Development tool: fills the database with a handful of made-up workers and employers, with assessments, employer feedback, credentials and trust scores,
 * so the dashboards have something to show without registering and answering everything by hand.
 *
 * <p>Everything goes through the same code as real use (phone encryption and lookup keys, consent record, the skill profile rules, signed credentials,
 * the trust engine); only the voice step is skipped, and each demo answer is plainly labelled. Demo accounts carry the consent version {@code DEMO-SEED},
 * which is what the dev-only demo sign-in recognises and what {@code --reset} removes. It refuses to run unless APP_ENV=dev.
 *
 * <p>Run with {@code scripts/demo-seed.sh [--reset]}. Phone numbers 90000 00101 to 00299 are made up and never contacted.
 */
public final class DemoSeed {
    static final String CONSENT = "DEMO-SEED";

    public record Feedback(String employer, int quality, int punctuality, int conduct, Boolean wouldHire, int days, int daysAgo, String role) {}

    public record DemoWorker(String name, String phone, String trade, String language, int[] questionScores, List<Feedback> feedback, boolean issueCredential) {}

    public record DemoEmployer(String key, String name, String phone) {}

    public static final List<DemoEmployer> EMPLOYERS = List.of(
            new DemoEmployer("sharma", "Sharma Constructions", "9000000201"),
            new DemoEmployer("gupta", "Gupta Electricals", "9000000202"),
            new DemoEmployer("verma", "Verma & Sons Builders", "9000000203"),
            new DemoEmployer("singh", "Singh Interiors", "9000000204"),
            new DemoEmployer("patel", "Patel Hardware and Repairs", "9000000205"),
            new DemoEmployer("khan", "Khan Contractors", "9000000206"),          // rates everyone 5 stars: the leniency calibration discounts this habit
            new DemoEmployer("raju", "Raju Fabricators", "9000000207"));         // marks hard

    public static final List<DemoWorker> WORKERS = List.of(
            new DemoWorker("Ramesh Kumar", "9000000101", "Electrician", "hi", new int[]{90, 85, 80, 88, 70, 78, 82, 75, 80, 85}, List.of(
                    new Feedback("sharma", 5, 5, 5, true, 45, 20, "House wiring"), new Feedback("gupta", 5, 4, 5, true, 30, 60, "Shop rewiring"),
                    new Feedback("verma", 4, 5, 5, true, 10, 120, "Panel board work"), new Feedback("khan", 5, 5, 5, true, 5, 40, "Fan and light fitting")), true),
            new DemoWorker("Sunita Devi", "9000000102", "Plumber", "hi", new int[]{80, 78, 75, 70, 72, 66, 80, 70, 74, 76}, List.of(
                    new Feedback("verma", 4, 4, 5, true, 20, 30, "Bathroom plumbing"), new Feedback("singh", 5, 4, 4, true, 14, 75, "Kitchen pipework"),
                    new Feedback("patel", 4, 5, 4, true, 7, 150, "Tap and valve repair"), new Feedback("khan", 5, 5, 5, true, 3, 90, "Tank connection")), true),
            new DemoWorker("Mohammed Irfan", "9000000103", "Carpenter", "hi", new int[]{70, 62, 68, 60, 64, 58, 72, 55, 66, 70}, List.of(
                    new Feedback("singh", 4, 3, 4, true, 15, 45, "Wardrobe and shelves"), new Feedback("raju", 3, 3, 3, false, 6, 200, "Door fitting")), true),
            new DemoWorker("Suresh Yadav", "9000000104", "Mason", "hi", new int[]{60, 55, 62, 50, 48, 56, 60, 52, 58, 54}, List.of(
                    new Feedback("sharma", 3, 4, 4, true, 25, 100, "Boundary wall"), new Feedback("verma", 2, 3, 3, false, 12, 160, "Plastering"),
                    new Feedback("raju", 3, 2, 3, false, 8, 300, "Brick work")), true),
            new DemoWorker("Anil Verma", "9000000105", "Welder", "en", new int[]{80, 74, 78, 76, 72, 80, 70, 78, 75, 82}, List.of(
                    new Feedback("khan", 5, 5, 5, true, 10, 25, "Gate fabrication")), true),                         // one glowing employer is not enough: shows the confidence rule
            new DemoWorker("Pooja Sharma", "9000000106", "Painter", "hi", new int[]{95, 92, 90, 94, 88, 90, 92, 90, 93, 96}, List.of(
                    new Feedback("singh", 5, 5, 5, true, 20, 15, "Full flat repaint"), new Feedback("sharma", 5, 5, 4, true, 30, 50, "Office painting"),
                    new Feedback("verma", 5, 4, 5, true, 12, 80, "Exterior walls"), new Feedback("patel", 4, 5, 5, true, 9, 110, "Shop painting"),
                    new Feedback("raju", 3, 4, 4, true, 10, 200, "Grill painting"),
                    new Feedback("khan", 5, 5, 5, true, 4, 30, "Touch-up work")), true),
            new DemoWorker("Imran Ansari", "9000000107", "Electrician", "en", new int[]{40, 50, 35, 45, 30, 40, 50, 35, 45, 30}, List.of(), true),   // assessed, but nobody has confirmed his work yet
            new DemoWorker("Lakshmi Naidu", "9000000108", "Plumber", "hi", new int[]{72, 70, 68, 74, 66, 70, 64, 72, 68, 70}, List.of(
                    new Feedback("patel", 4, 4, 4, true, 10, 40, "Water line repair"), new Feedback("gupta", 4, 4, 5, true, 15, 70, "Drain clearing")), true),
            new DemoWorker("Deepak Rathore", "9000000109", "Carpenter", "hi", new int[]{}, List.of(), false));         // just registered: no assessment, no history

    private DemoSeed() {}

    public static void main(String[] args) throws Exception {
        AppSettings settings = AppSettings.fromEnvironment();
        if (!settings.appEnv().equals("dev")) {
            System.err.println("Refusing to run: the demo seeder only runs when APP_ENV=dev (it is " + settings.appEnv() + ").");
            System.exit(2);
        }
        boolean reset = List.of(args).contains("--reset");
        try (AppContext app = AppContext.create(settings, new DatabaseConfig())) {
            Seeder seeder = new Seeder(app, settings);
            if (reset) {
                int removed = seeder.reset();
                System.out.println("Removed the earlier demo accounts (" + removed + ").");
            } else if (seeder.alreadySeeded()) {
                System.out.println("Demo accounts already exist. Use --reset to rebuild them.");
                return;
            }
            seeder.seed();
        }
    }

    /** The work, separated from main so a test can run it against a database. */
    public static final class Seeder {
        private final AppContext app;
        private final AppSettings settings;
        private final PhoneProtector phones;
        private final Map<String, Long> employerIds = new LinkedHashMap<>();

        public Seeder(AppContext app, AppSettings settings) {
            this.app = app;
            this.settings = settings;
            this.phones = app.phones;
        }

        boolean alreadySeeded() {
            return !app.daos.workers().findDemoAccounts().isEmpty() || !app.daos.employers().findDemoAccounts().isEmpty();
        }

        /** Deletes every demo account and what hangs off it. Needs a database role that may DELETE from employers (the owner). */
        public int reset() throws SQLException {
            List<String> exports = new ArrayList<>();
            int removed;
            try (Connection c = app.pool.getConnection()) {
                try (PreparedStatement s = c.prepareStatement("SELECT credential_ref FROM credentials WHERE worker_id IN (SELECT worker_id FROM workers WHERE consent_version = 'DEMO-SEED')");
                     ResultSet r = s.executeQuery()) {
                    while (r.next()) exports.add(r.getString(1));
                }
                run(c, "DELETE FROM trust_edges WHERE target_id IN (SELECT worker_id FROM workers WHERE consent_version = 'DEMO-SEED') "
                        + "OR (source_kind = 'WORKER' AND source_id IN (SELECT worker_id FROM workers WHERE consent_version = 'DEMO-SEED'))");
                run(c, "DELETE FROM trust_edges WHERE source_kind = 'EMPLOYER' AND source_id IN (SELECT employer_id FROM employers WHERE consent_version = 'DEMO-SEED')");
                removed = run(c, "DELETE FROM workers WHERE consent_version = 'DEMO-SEED'");
                removed += run(c, "DELETE FROM employers WHERE consent_version = 'DEMO-SEED'");
            }
            for (String ref : exports) {
                try {
                    Files.deleteIfExists(Path.of("data", "credentials", ref + ".json"));
                } catch (java.io.IOException ignored) {
                    // a leftover demo export file is harmless
                }
            }
            return removed;
        }

        private static int run(Connection c, String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql)) {
                return s.executeUpdate();
            }
        }

        public void seed() throws SQLException {
            MlGateway ml = new MlServiceClient(settings.mlServiceUrl(), settings.mlInternalToken());
            for (DemoEmployer e : EMPLOYERS) {
                String normalized = PhoneProtector.normalize(e.phone());
                Employer created = app.daos.employers().insertProtected(new Employer(null, e.name(), phones.lookupKey(normalized), false, null), phones.encrypt(normalized), CONSENT);
                employerIds.put(e.key(), created.employerId());
            }
            int attestations = 0;
            for (DemoWorker w : WORKERS) {
                String normalized = PhoneProtector.normalize(w.phone());
                Worker created = app.daos.workers().insertProtected(new Worker(null, w.name(), phones.lookupKey(normalized), w.trade(), w.language(), BigDecimal.ZERO, BigDecimal.ZERO, null),
                        phones.encrypt(normalized), CONSENT);
                if (w.questionScores().length > 0) assess(created, w, ml);
                for (Feedback f : w.feedback()) {
                    attest(created.workerId(), f, 0.05 + (Math.floorMod(f.role().hashCode(), 20) / 100.0));
                    attestations++;
                }
            }
            fraudExample("Lakshmi Naidu", "raju");
            app.trust.recompute();
            int credentials = 0;
            for (Worker w : app.daos.workers().findDemoAccounts()) {
                DemoWorker spec = WORKERS.stream().filter(x -> x.name().equals(w.name())).findFirst().orElseThrow();
                if (!spec.issueCredential()) continue;
                try {
                    app.credentials.issue(w.workerId(), "demo-seed");
                    credentials++;
                } catch (RuntimeException notEligible) {
                    System.out.println("  no credential for " + w.name() + ": " + notEligible.getMessage());
                }
            }
            app.audit.log("DEMO_SEED", "system", "workers=" + WORKERS.size() + " employers=" + EMPLOYERS.size() + " attestations=" + attestations);
            System.out.println("Seeded " + WORKERS.size() + " workers, " + EMPLOYERS.size() + " employers, " + attestations + " employer confirmations and " + credentials + " credentials.");
            System.out.println("Open http://localhost:" + (System.getenv().getOrDefault("APP_PORT", "8090")) + "/demo to sign in as any of them.");
        }

        private void assess(Worker worker, DemoWorker spec, MlGateway ml) {
            Map<Integer, String> areaOf = new LinkedHashMap<>();
            try {
                ml.rubric(spec.trade(), spec.language()).questions().forEach(q -> areaOf.put(q.id(), q.area()));
            } catch (RuntimeException unavailable) {
                String[] fallback = {"tools", "materials", "experience", "knowledge", "customer", "knowledge", "troubleshooting", "troubleshooting", "planning", "planning"};
                for (int i = 0; i < 10; i++) areaOf.put(i + 1, fallback[i]);
            }
            UUID attempt = UUID.randomUUID();
            List<SkillScoring.QuestionScore> scored = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < spec.questionScores().length; i++) {
                BigDecimal score = BigDecimal.valueOf(spec.questionScores()[i]).setScale(2);
                app.daos.assessments().insertAnswer(new AssessmentAnswer(null, worker.workerId(), spec.trade(), attempt, i + 1, spec.language(),
                        "(demo answer " + (i + 1) + ", no recording)", score, "Demo data", false, null, "demo-seed", null));
                scored.add(new SkillScoring.QuestionScore(areaOf.getOrDefault(i + 1, "knowledge"), score));
                total = total.add(score);
            }
            BigDecimal overall = total.divide(BigDecimal.valueOf(scored.size()), 2, RoundingMode.HALF_UP);
            SkillProfile profile = SkillScoring.profile(overall, scored, BigDecimal.valueOf(settings.passThreshold()), BigDecimal.valueOf(settings.borderlineThreshold()),
                    BigDecimal.valueOf(settings.boundaryMargin()));
            app.daos.workers().updateSkillProfile(worker.workerId(), overall, profile.band().name(), Json.toJson(profile.areas()), profile.nearBoundary());
        }

        /** An approved confirmation with the ratings, dated in the past so recency is visible in the scores. */
        private void attest(long workerId, Feedback f, double anomaly) throws SQLException {
            long employerId = employerIds.get(f.employer());
            AttestationDetail row = app.daos.attestations().insertWorkflow(workerId, employerId, "demo", f.role(), f.days(), f.wouldHire(), LocalDate.now().minusDays(f.daysAgo()),
                    "demo-ip", "demo-device", f.quality(), f.punctuality(), f.conduct());
            app.daos.attestations().decide(row.attestationId(), "APPROVED", BigDecimal.valueOf(anomaly).setScale(4, RoundingMode.HALF_UP));
            app.daos.employers().vet(employerId, "demo-seed");              // demo employers stand in for employers a moderator has vetted
            app.daos.trustEdges().insertAttestationEdge(employerId, workerId, BigDecimal.ONE, row.attestationId());
            LocalDateTime when = LocalDateTime.now().minusDays(f.daysAgo());
            try (Connection c = app.pool.getConnection();
                 PreparedStatement s = c.prepareStatement("UPDATE attestations SET created_at = ?, decided_at = ? WHERE attestation_id = ?")) {
                s.setTimestamp(1, Timestamp.valueOf(when));
                s.setTimestamp(2, Timestamp.valueOf(when));
                s.setLong(3, row.attestationId());
                s.executeUpdate();
            }
        }

        /** One confirmation a moderator ruled fraudulent: it is rejected, and it counts against the worker (and the employer) in the trust score. */
        private void fraudExample(String workerName, String employerKey) {
            Worker worker = app.daos.workers().findDemoAccounts().stream().filter(w -> w.name().equals(workerName)).findFirst().orElseThrow();
            long employerId = employerIds.get(employerKey);
            AttestationDetail row = app.daos.attestations().insertWorkflow(worker.workerId(), employerId, "demo", "Pipe fitting", 2, true, LocalDate.now().minusDays(12),
                    "demo-ip", "demo-device", 5, 5, 5);
            app.daos.attestations().decide(row.attestationId(), "PENDING_REVIEW", new BigDecimal("0.9100"));
            var flag = app.daos.fraudFlags().insertWithFeatures(row.attestationId(), new BigDecimal("0.9100"), "{\"note\":\"demo: ruled fraudulent by a moderator\"}");
            app.daos.fraudFlags().decideIfPending(flag.flagId(), "FRAUD", "demo-seed");
            app.daos.attestations().moderate(row.attestationId(), "REJECTED");
        }
    }
}
