package com.kaarigarsetu.assessment;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.UnintelligibleAudioException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.support.MutableClock;
import com.kaarigarsetu.support.WavFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssessmentPipelineServiceTest {
    private static final long WORKER = 7L;
    private static final List<String> AREAS = List.of("tools", "materials", "experience", "knowledge", "customer", "knowledge", "troubleshooting", "troubleshooting", "planning", "planning");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final WorkerDao workers = mock(WorkerDao.class);
    private final AssessmentDao assessments = mock(AssessmentDao.class);
    private final MlGateway ml = mock(MlGateway.class);
    private final com.kaarigarsetu.dao.ReplayDao replayDao = mock(com.kaarigarsetu.dao.ReplayDao.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final List<AssessmentAnswer> saved = new ArrayList<>();
    private AssessmentPipelineService service;

    @BeforeEach
    void setUp() {
        AppSettings settings = AppSettings.of(Map.of("REPLAY_CHALLENGE", "false", "REPLAY_DETECTION", "false"));          // these tests are about scoring; the replay defences have their own tests
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes();
        com.kaarigarsetu.security.PhoneProtector phones = new com.kaarigarsetu.security.PhoneProtector(key, key, new java.security.SecureRandom());
        service = new AssessmentPipelineService(workers, assessments, ml,
                new AssessmentScoringDispatcher(List.of(new ElectricianAssessmentService(), new PlumberAssessmentService())), audit,
                settings, key, clock, Transactions.none(), new ReplayDetector(replayDao, phones, settings), new ReplayChallenge(key, clock, Duration.ofMinutes(15)), replayDao,
                new com.kaarigarsetu.security.RateLimiter(clock));
        when(workers.findById(WORKER)).thenReturn(Optional.of(new Worker(WORKER, "W", "k", "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(workers.findById(8L)).thenReturn(Optional.of(new Worker(8L, "X", "k2", "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(ml.rubric(anyString(), anyString())).thenReturn(new MlGateway.Rubric("electrician", "v1", true,
                java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(n -> new MlGateway.Question(n, "q" + n, 5, "basic", AREAS.get(n - 1))).toList()));
        when(ml.transcribe(any(), anyString(), anyString())).thenReturn(new MlGateway.Transcript("jawab", "hi"));
        when(ml.evaluate(anyString(), anyString(), anyInt(), anyString())).thenReturn(new MlGateway.Evaluation("electrician", 80.0, "good", false, null, "stub"));
        when(assessments.insertAnswer(any(), any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return (long) saved.size();
        });
        when(assessments.findAttempt(anyLong(), any())).thenAnswer(i -> List.copyOf(saved));
    }

    private static byte[] wav() {
        return WavFixtures.wav(2000, 16000, 1, 16);
    }

    @Test
    void aFullAttemptIsScoredInOrderAndTheSkillScoreIsStored() {
        String token = service.start(WORKER).attemptToken();
        for (int q = 1; q <= 9; q++) assertFalse(service.submitAnswer(WORKER, token, q, wav(), null).finished());
        AssessmentPipelineService.AnswerOutcome last = service.submitAnswer(WORKER, token, 10, wav(), null);
        assertTrue(last.finished());
        assertEquals(0, new BigDecimal("80").compareTo(last.skillScore()));
        verify(workers).updateSkillProfile(eq(WORKER), argThat(s -> s.compareTo(new BigDecimal("80")) == 0), eq("PASS"),
                argThat(json -> json.contains("\"tools\":80") && json.contains("\"planning\":80")), eq(false));
        assertEquals("PASS", last.skillBand());
        assertEquals(List.of("tools", "materials", "experience", "knowledge", "troubleshooting", "planning", "customer"), List.copyOf(last.skillAreas().keySet()));
        assertFalse(last.nearBoundary());
        verify(audit).log(eq("ASSESSMENT_COMPLETED"), eq("worker:7"), contains("band=PASS"));
        verify(audit).log(eq("ASSESSMENT_COMPLETED"), eq("worker:7"), contains("flaggedAnswers=0"));
    }

    @Test
    void theBandAndTheAreaScoresReflectWhichQuestionsWereAnsweredWell() {
        // strong on tools/materials/experience (questions 1-3), weak elsewhere
        when(ml.evaluate(anyString(), anyString(), anyInt(), anyString())).thenAnswer(i ->
                new MlGateway.Evaluation("electrician", ((int) i.getArgument(2)) <= 3 ? 100.0 : 30.0, "f", false, null, "stub"));
        String token = service.start(WORKER).attemptToken();
        AssessmentPipelineService.AnswerOutcome last = null;
        for (int q = 1; q <= 10; q++) last = service.submitAnswer(WORKER, token, q, wav(), null);
        assertEquals(0, new BigDecimal("51.00").compareTo(last.skillScore()));          // (3 x 100 + 7 x 30) / 10
        assertEquals("BORDERLINE", last.skillBand());
        assertTrue(last.nearBoundary(), "51 is within 5 of the 50 boundary");
        assertEquals(0, new BigDecimal("100.00").compareTo(last.skillAreas().get("tools")));
        assertEquals(0, new BigDecimal("30.00").compareTo(last.skillAreas().get("troubleshooting")));
    }

    @Test
    void questionsMustComeInOrderAndOnlyOnceAndNotAfterCompletion() {
        String token = service.start(WORKER).attemptToken();
        assertThrows(ConflictException.class, () -> service.submitAnswer(WORKER, token, 2, wav(), null));
        service.submitAnswer(WORKER, token, 1, wav(), null);
        assertThrows(ConflictException.class, () -> service.submitAnswer(WORKER, token, 1, wav(), null));
        for (int q = 2; q <= 10; q++) service.submitAnswer(WORKER, token, q, wav(), null);
        assertThrows(ConflictException.class, () -> service.submitAnswer(WORKER, token, 11, wav(), null));
    }

    @Test
    void anAttemptTokenIsBoundToItsWorkerAndCannotBeForgedOrAltered() {
        String token = service.start(WORKER).attemptToken();
        assertThrows(ValidationException.class, () -> service.submitAnswer(8L, token, 1, wav(), null), "another worker's token");
        String[] parts = token.split("\\.");
        assertThrows(ValidationException.class, () -> service.submitAnswer(WORKER, UUID.randomUUID() + "." + parts[1] + "." + parts[2], 1, wav(), null));
        assertThrows(ValidationException.class, () -> service.submitAnswer(WORKER, parts[0] + "." + parts[1] + ".AAAA", 1, wav(), null));
        for (String junk : new String[]{"", "a.b", "a.b.c.d", "not-a-token", "' OR 1=1 --"}) assertThrows(ValidationException.class, () -> service.submitAnswer(WORKER, junk, 1, wav(), null));
        assertThrows(ValidationException.class, () -> service.result(WORKER, "garbage"));
    }

    @Test
    void tokensExpireAfterThreeHours() {
        String token = service.start(WORKER).attemptToken();
        clock.advance(Duration.ofHours(2));
        assertDoesNotThrow(() -> service.submitAnswer(WORKER, token, 1, wav(), null));
        clock.advance(Duration.ofHours(2));
        assertThrows(ValidationException.class, () -> service.submitAnswer(WORKER, token, 2, wav(), null));
    }

    @Test
    void invalidAudioIsRejectedBeforeAnyTranscriptionAndRawAudioIsZeroedAfterwards() {
        String token = service.start(WORKER).attemptToken();
        assertThrows(ValidationException.class, () -> service.submitAnswer(WORKER, token, 1, new byte[]{1, 2, 3}, null));
        verify(ml, never()).transcribe(any(), anyString(), anyString());
        byte[] audio = wav();
        service.submitAnswer(WORKER, token, 1, audio, null);
        for (byte b : audio) assertEquals(0, b, "raw voice data must not be retained");
    }

    @Test
    void silenceIsUnintelligibleNotScored() {
        String token = service.start(WORKER).attemptToken();
        when(ml.transcribe(any(), anyString(), anyString())).thenReturn(new MlGateway.Transcript("   ", "hi"));
        assertThrows(UnintelligibleAudioException.class, () -> service.submitAnswer(WORKER, token, 1, wav(), null));
        verify(ml, never()).evaluate(anyString(), anyString(), anyInt(), anyString());
        assertTrue(saved.isEmpty());
    }

    @Test
    void aDuplicateInsertRaceBecomesAConflictAndOtherDbErrorsPropagate() {
        String token = service.start(WORKER).attemptToken();
        doThrow(new DataAccessException("dup", new SQLException("dup", "23505"))).when(assessments).insertAnswer(any(), any());
        assertThrows(ConflictException.class, () -> service.submitAnswer(WORKER, token, 1, wav(), null));
        doThrow(new DataAccessException("down", new SQLException("down", "08006"))).when(assessments).insertAnswer(any(), any());
        assertThrows(DataAccessException.class, () -> service.submitAnswer(WORKER, token, 1, wav(), null));
    }

    @Test
    void reviewFlagsFromTheScorerArePersistedAndCounted() {
        String token = service.start(WORKER).attemptToken();
        when(ml.evaluate(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new MlGateway.Evaluation("electrician", 60.0, "check", true, "possible prompt injection", "llm"));
        for (int q = 1; q <= 10; q++) service.submitAnswer(WORKER, token, q, wav(), null);
        verify(audit).log(eq("ASSESSMENT_COMPLETED"), anyString(), contains("flaggedAnswers=10"));
        assertTrue(saved.get(0).needsReview());
        assertEquals("possible prompt injection", saved.get(0).reviewReason());
    }

    @Test
    void theOutcomeReportsWhichScorerProducedTheScoreSoTheScreenCanSayIfItIsOnlyKeywordMatching() {
        String token = service.start(WORKER).attemptToken();
        when(ml.evaluate(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new MlGateway.Evaluation("electrician", 80.0, "ok", false, null, "keyword"));
        assertEquals("keyword", service.submitAnswer(WORKER, token, 1, wav(), null).provider());
    }

    @Test
    void theRubricIsCachedBetweenCalls() {
        service.start(WORKER);
        service.start(WORKER);
        verify(ml, times(1)).rubric("electrician", "hi");
        clock.advance(Duration.ofMinutes(11));
        service.start(WORKER);
        verify(ml, times(2)).rubric("electrician", "hi");
    }
}
