package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.support.TestCiphers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Migration 011 and ReplayDao against real PostgreSQL: what is stored, what a search finds, and that it all goes with the answer. */
class ReplayDaoIntegrationTest {
    private static final String URL = System.getenv("TEST_DB_URL");
    private final ConnectionProvider provider = () -> DriverManager.getConnection(URL,
            System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    private WorkerDao workers;
    private AssessmentDao assessments;
    private ReplayDao replay;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && URL.endsWith("_test"), "TEST_DB_URL must name a *_test database");
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");
            s.execute("CREATE SCHEMA public");
            Path dir = Path.of("..", "database");
            s.execute(Files.readString(dir.resolve("schema.sql")));
            try (Stream<Path> migrations = Files.list(dir.resolve("migrations"))) {
                for (Path m : migrations.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) s.execute(Files.readString(m));
            }
        }
        workers = new WorkerDao(provider, TestCiphers.data());
        assessments = new AssessmentDao(provider, TestCiphers.data());
        replay = new ReplayDao(provider);
    }

    private Worker worker(String key) {
        return workers.insertProtected(new Worker(null, "W", key, "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null), new byte[]{1, 2, 3}, "dpdp-v1");
    }

    private long answer(Worker w, UUID attempt, int question, AssessmentDao.Checks checks) {
        return assessments.insertAnswer(new AssessmentAnswer(null, w.workerId(), "electrician", attempt, question, "hi", "t", new BigDecimal("70"), "f", false, null, "stub", null), checks);
    }

    private long count(String sql) throws Exception {
        try (Connection c = provider.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void theChecksAreStoredBesideTheAnswerAndDefaultToNotChecked() throws Exception {
        Worker w = worker("h:1");
        long plain = assessments.insertAnswer(new AssessmentAnswer(null, w.workerId(), "electrician", UUID.randomUUID(), 1, "hi", "t", BigDecimal.TEN, "f", false, null, "stub", null));
        long checked = answer(w, UUID.randomUUID(), 1, new AssessmentDao.Checks("REPLAY_AUDIO", "NOT_HEARD", 120, 30));
        Map<Long, ReplayDao.Meta> meta = replay.findMeta(List.of(plain, checked, 999_999L));
        assertEquals(2, meta.size(), "an unknown id is simply absent");
        assertEquals(0, meta.get(plain).fingerprintCount());
        assertEquals(120, meta.get(checked).fingerprintCount());
        assertEquals(30, meta.get(checked).shingleCount());
        assertEquals(w.workerId(), meta.get(checked).workerId());
        assertEquals(1, meta.get(checked).questionNo());
        assertEquals("NOT_CHECKED", string("SELECT replay_status FROM assessments WHERE assessment_id = " + plain));
        assertEquals("REPLAY_AUDIO", string("SELECT replay_status FROM assessments WHERE assessment_id = " + checked));
        assertEquals("NOT_HEARD", string("SELECT challenge_status FROM assessments WHERE assessment_id = " + checked));
        assertTrue(replay.findMeta(List.of()).isEmpty());
    }

    private String string(String sql) throws Exception {
        try (Connection c = provider.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    @Test
    void anUnknownStatusIsRefusedByTheDatabaseItself() {
        Worker w = worker("h:2");
        assertThrows(com.kaarigarsetu.exception.DataAccessException.class, () -> answer(w, UUID.randomUUID(), 1, new AssessmentDao.Checks("SOMETHING_ELSE", "HEARD", 0, 0)));
        assertThrows(com.kaarigarsetu.exception.DataAccessException.class, () -> answer(w, UUID.randomUUID(), 1, new AssessmentDao.Checks("CLEAR", "MAYBE", 0, 0)));
    }

    @Test
    void landmarksAreFoundByAnotherAnswerAtOneTimeShift() {
        Worker a = worker("h:a");
        long first = answer(a, UUID.randomUUID(), 1, AssessmentDao.Checks.NONE);
        long[] keys = {10, 20, 30, 40, 50, 60};
        replay.store(first, keys, new int[]{0, 5, 9, 20, 31, 40}, new long[0]);
        long other = answer(worker("h:b"), UUID.randomUUID(), 1, AssessmentDao.Checks.NONE);
        replay.store(other, new long[]{10, 20}, new int[]{3, 100}, new long[0]);        // two landmarks in common with 'first', at unrelated times

        // the same recording trimmed by 7 frames at the start: every landmark is 7 frames earlier
        List<ReplayDao.Vote> votes = replay.findAudioVotes(keys, new int[]{-7, -2, 2, 13, 24, 33}, 3);
        assertEquals(1, votes.size());
        assertEquals(first, votes.get(0).assessmentId());
        assertEquals(7, votes.get(0).delta());
        assertEquals(6, votes.get(0).votes());
        List<ReplayDao.Vote> all = replay.findAudioVotes(keys, new int[]{-7, -2, 2, 13, 24, 33}, 1);
        assertTrue(all.stream().anyMatch(v -> v.assessmentId() == other), "a lower threshold also shows the coincidences, which is why the caller applies the real one");
        assertTrue(all.stream().filter(v -> v.assessmentId() == other).allMatch(v -> v.votes() == 1));
        assertTrue(replay.findAudioVotes(new long[]{999}, new int[]{0}, 1).isEmpty());
        assertTrue(replay.findAudioVotes(new long[0], new int[0], 1).isEmpty());
    }

    @Test
    void wordTrigramsAreCountedPerAnswerAndDuplicatesCountOnce() throws Exception {
        Worker a = worker("h:c");
        long first = answer(a, UUID.randomUUID(), 1, AssessmentDao.Checks.NONE);
        replay.store(first, new long[0], new int[0], new long[]{1, 2, 3, 3, 3, 4});
        assertEquals(4, count("SELECT count(*) FROM answer_shingles WHERE assessment_id = " + first), "a repeated trigram is stored once");
        long second = answer(worker("h:d"), UUID.randomUUID(), 1, AssessmentDao.Checks.NONE);
        replay.store(second, new long[0], new int[0], new long[]{3, 4, 5});
        List<ReplayDao.Overlap> overlaps = replay.findShingleOverlap(new long[]{2, 3, 4, 9}, 2);
        assertEquals(2, overlaps.size());
        assertEquals(3, overlaps.stream().filter(o -> o.assessmentId() == first).findFirst().orElseThrow().common());
        assertEquals(2, overlaps.stream().filter(o -> o.assessmentId() == second).findFirst().orElseThrow().common());
        assertEquals(1, replay.findShingleOverlap(new long[]{2, 3, 4, 9}, 3).size(), "minCommon trims");
        assertTrue(replay.findShingleOverlap(new long[0], 1).isEmpty());
    }

    @Test
    void storingNothingIsHarmlessAndTheFingerprintsGoWhenTheAnswerDoes() throws Exception {
        Worker w = worker("h:e");
        long id = answer(w, UUID.randomUUID(), 1, AssessmentDao.Checks.NONE);
        replay.store(id, new long[0], new int[0], new long[0]);
        assertEquals(0, count("SELECT count(*) FROM answer_fingerprints"));
        replay.store(id, new long[]{1, 2}, new int[]{0, 1}, new long[]{7, 8});
        assertEquals(2, count("SELECT count(*) FROM answer_fingerprints"));
        assertEquals(2, count("SELECT count(*) FROM answer_shingles"));
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("DELETE FROM assessments WHERE assessment_id = " + id);
        }
        assertEquals(0, count("SELECT count(*) FROM answer_fingerprints"), "cascade");
        assertEquals(0, count("SELECT count(*) FROM answer_shingles"), "cascade");
    }

    @Test
    void searchingWithAnInjectionLookingValueCannotChangeTheStatement() {
        // keys are numbers bound as an array: there is no text to inject into, and the search stays a plain lookup
        assertTrue(replay.findAudioVotes(new long[]{Long.MAX_VALUE, Long.MIN_VALUE}, new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, 1).isEmpty());
        assertTrue(replay.findShingleOverlap(new long[]{Long.MAX_VALUE, -1}, 1).isEmpty());
    }

    @Test
    void twoUploadsOfTheSameRecordingArrivingTogetherAreComparedOneAfterTheOther() throws Exception {
        String rest = URL.substring("jdbc:postgresql://".length());
        String host = rest.substring(0, rest.indexOf(':'));
        String port = rest.substring(rest.indexOf(':') + 1, rest.indexOf('/'));
        String database = rest.substring(rest.indexOf('/') + 1);
        try (var pool = new com.kaarigarsetu.config.PooledConnectionProvider(new com.kaarigarsetu.config.DatabaseConfig(host, port, database,
                System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", "")), 4)) {
            ReplayDao dao = new ReplayDao(pool);
            AssessmentDao answers = new AssessmentDao(pool, TestCiphers.data());
            Worker first = worker("h:race1"), second = worker("h:race2");
            long[] keys = {11, 22, 33, 44};
            int[] times = {0, 4, 9, 15};
            var found = new java.util.concurrent.atomic.AtomicReference<List<ReplayDao.Vote>>();
            var firstHasLock = new java.util.concurrent.CountDownLatch(1);
            Thread a = new Thread(() -> pool.inTransaction(() -> {
                dao.lockAnswerChecks();
                firstHasLock.countDown();
                try {
                    Thread.sleep(400);                        // the first upload is still being compared when the second arrives
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                long id = answers.insertAnswer(new AssessmentAnswer(null, first.workerId(), "electrician", UUID.randomUUID(), 1, "hi", "t", BigDecimal.TEN, "f", false, null, "stub", null));
                dao.store(id, keys, times, new long[0]);
                return id;
            }));
            Thread b = new Thread(() -> pool.inTransaction(() -> {
                dao.lockAnswerChecks();                       // waits for the first transaction to finish
                found.set(dao.findAudioVotes(keys, times, 1));
                return null;
            }));
            a.start();
            assertTrue(firstHasLock.await(5, java.util.concurrent.TimeUnit.SECONDS));
            b.start();
            a.join(10_000);
            b.join(10_000);
            assertNotNull(found.get(), "the second upload ran");
            assertEquals(1, found.get().size(), "...and, because it waited for the lock, found the first one's fingerprint");
            assertEquals(4, found.get().get(0).votes());
        }
    }
}
