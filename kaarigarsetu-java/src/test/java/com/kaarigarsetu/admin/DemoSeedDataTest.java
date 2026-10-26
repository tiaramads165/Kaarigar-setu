package com.kaarigarsetu.admin;

import com.kaarigarsetu.admin.DemoSeed.DemoEmployer;
import com.kaarigarsetu.admin.DemoSeed.DemoWorker;
import com.kaarigarsetu.admin.DemoSeed.Feedback;
import com.kaarigarsetu.assessment.TradeCatalog;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.trust.ReputationScoring;
import com.kaarigarsetu.trust.TrustBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The demo data is plain data: check it is valid and tells the story it is meant to tell, without needing a database. */
class DemoSeedDataTest {
    private static final Set<String> TRADES = TradeCatalog.load().all().stream().map(TradeCatalog.Trade::name).collect(Collectors.toSet());
    private static final Set<String> EMPLOYER_KEYS = DemoSeed.EMPLOYERS.stream().map(DemoEmployer::key).collect(Collectors.toSet());

    @Test
    void everyDemoAccountHasAValidMadeUpPhoneNumberAndNoTwoShareOne() {
        Set<String> seen = new HashSet<>();
        for (String phone : concat(DemoSeed.WORKERS.stream().map(DemoWorker::phone).toList(), DemoSeed.EMPLOYERS.stream().map(DemoEmployer::phone).toList())) {
            assertDoesNotThrow(() -> PhoneProtector.normalize(phone), phone);
            assertTrue(phone.startsWith("90000001") || phone.startsWith("90000002"), "made-up range only: " + phone);
            assertTrue(seen.add(phone), "duplicate " + phone);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Test
    void tradesLanguagesScoresAndFeedbackAreAllValid() {
        for (DemoWorker w : DemoSeed.WORKERS) {
            assertTrue(TRADES.contains(w.trade()), w.name() + " trade " + w.trade());
            assertTrue(Set.of("hi", "en").contains(w.language()));
            assertTrue(w.questionScores().length == 0 || w.questionScores().length == 10, w.name());
            for (int s : w.questionScores()) assertTrue(s >= 0 && s <= 100);
            for (Feedback f : w.feedback()) {
                assertTrue(EMPLOYER_KEYS.contains(f.employer()), f.employer());
                for (int r : new int[]{f.quality(), f.punctuality(), f.conduct()}) assertTrue(r >= 1 && r <= 5);
                assertTrue(f.days() >= 0 && f.daysAgo() >= 0 && !f.role().isBlank());
            }
            assertEquals(w.feedback().size(), w.feedback().stream().map(Feedback::employer).distinct().count(), w.name() + ": one confirmation per employer");
        }
    }

    @Test
    void theDemoCoversTheInterestingCasesOnPurpose() {
        Map<String, DemoWorker> byName = DemoSeed.WORKERS.stream().collect(Collectors.toMap(DemoWorker::name, w -> w));
        assertTrue(byName.get("Pooja Sharma").feedback().size() >= 4, "a well-established worker");
        assertEquals(1, byName.get("Anil Verma").feedback().size(), "one glowing employer only");
        assertTrue(byName.get("Imran Ansari").feedback().isEmpty() && byName.get("Imran Ansari").questionScores().length == 10, "assessed but unconfirmed");
        assertEquals(0, byName.get("Deepak Rathore").questionScores().length, "brand new");
        long khanFiveStars = DemoSeed.WORKERS.stream().flatMap(w -> w.feedback().stream()).filter(f -> f.employer().equals("khan")).filter(f -> f.quality() == 5 && f.punctuality() == 5 && f.conduct() == 5).count();
        assertTrue(khanFiveStars >= 3, "a lenient rater with enough ratings to be calibrated");
        assertTrue(DemoSeed.WORKERS.stream().flatMap(w -> w.feedback().stream()).filter(f -> f.employer().equals("raju")).count() >= 3, "a harsh rater with enough ratings to be calibrated");
    }

    @Test
    void theScoringModelTurnsTheDemoFeedbackIntoTheIntendedStory() {
        Instant now = Instant.parse("2026-10-09T09:00:00Z");
        Map<String, ReputationScoring.Report> report = new java.util.LinkedHashMap<>();
        ReputationScoring.Params params = ReputationScoring.Params.defaults();
        Map<String, Long> ids = new java.util.HashMap<>();
        long next = 1;
        for (DemoEmployer e : DemoSeed.EMPLOYERS) ids.put(e.key(), next++);
        List<ReputationScoring.Feedback> all = new ArrayList<>();
        long attestationId = 1;
        for (DemoWorker w : DemoSeed.WORKERS) {
            for (Feedback f : w.feedback()) {
                all.add(new ReputationScoring.Feedback(attestationId++, DemoSeed.WORKERS.indexOf(w) + 1, ids.get(f.employer()), f.quality(), f.punctuality(), f.conduct(),
                        f.wouldHire(), f.days(), now.minusSeconds(f.daysAgo() * 86_400L), 0.1));
            }
        }
        Map<Long, Double> leniency = ReputationScoring.calibrate(all, params);
        assertTrue(leniency.get(ids.get("khan")) > 0, "the always-five-stars employer is discounted");
        assertTrue(leniency.get(ids.get("raju")) < 0, "the harsh employer is lifted");
        Map<Long, ReputationScoring.EmployerStanding> standing = new java.util.HashMap<>();
        ids.values().forEach(id -> standing.put(id, new ReputationScoring.EmployerStanding(true, 6, 0, 0.6)));
        for (DemoWorker w : DemoSeed.WORKERS) {
            long wid = DemoSeed.WORKERS.indexOf(w) + 1;
            report.put(w.name(), ReputationScoring.score(all.stream().filter(f -> f.workerId() == wid).toList(), standing, leniency, 0, now, params));
        }
        assertEquals(TrustBand.NO_HISTORY, report.get("Imran Ansari").band());
        assertEquals(TrustBand.NO_HISTORY, report.get("Deepak Rathore").band());
        assertEquals(TrustBand.BUILDING, report.get("Anil Verma").band(), "one employer is not enough, however glowing");
        assertTrue(report.get("Pooja Sharma").score().compareTo(report.get("Suresh Yadav").score()) > 0);
        assertTrue(report.get("Pooja Sharma").score().compareTo(report.get("Anil Verma").score()) > 0);
    }
}
