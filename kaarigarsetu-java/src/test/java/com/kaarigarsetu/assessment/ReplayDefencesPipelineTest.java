package com.kaarigarsetu.assessment;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.ReplayDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ChallengeException;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.RateLimitExceededException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.security.RateLimiter;
import com.kaarigarsetu.support.MutableClock;
import com.kaarigarsetu.support.WavFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The replay defences as the pipeline applies them: the spoken code, the retries, the verdicts, what is stored and what is audited. */
class ReplayDefencesPipelineTest {
    private static final long WORKER = 7L;
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"));
    private final WorkerDao workers = mock(WorkerDao.class);
    private final AssessmentDao assessments = mock(AssessmentDao.class);
    private final MlGateway ml = mock(MlGateway.class);
    private final ReplayDao replayDao = mock(ReplayDao.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PhoneProtector phones = new PhoneProtector(KEY, KEY, new SecureRandom());
    private final List<AssessmentAnswer> saved = new ArrayList<>();
    private final List<AssessmentDao.Checks> checksSaved = new ArrayList<>();
    private AssessmentPipelineService service;
    private String attempt;

    private static MlGateway.Fingerprint audio(int n) {
        int[] hashes = IntStream.range(0, n).map(i -> ((i % 256) << 15) | (((i * 7) % 256) << 7) | (1 + i % 60)).toArray();
        return new MlGateway.Fingerprint(hashes, IntStream.range(0, n).map(i -> i * 3).toArray(), n / 100.0);
    }

    private AssessmentPipelineService build(Map<String, String> env) {
        AppSettings settings = AppSettings.of(env);
        return new AssessmentPipelineService(workers, assessments, ml, new AssessmentScoringDispatcher(List.of(new ElectricianAssessmentService())), audit, settings, KEY, clock,
                Transactions.none(), new ReplayDetector(replayDao, phones, settings), new ReplayChallenge(KEY, clock, Duration.ofMinutes(settings.replayChallengeMinutes())), replayDao, new RateLimiter(clock));
    }

    @BeforeEach
    void setUp() {
        service = build(Map.of());
        when(workers.findById(WORKER)).thenReturn(Optional.of(new Worker(WORKER, "W", "k", "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(ml.rubric(anyString(), anyString())).thenReturn(new MlGateway.Rubric("electrician", "v1", true,
                IntStream.rangeClosed(1, 10).mapToObj(n -> new MlGateway.Question(n, "q" + n, 5, "basic", "knowledge")).toList()));
        when(ml.evaluate(anyString(), anyString(), anyInt(), anyString())).thenReturn(new MlGateway.Evaluation("electrician", 80.0, "good", false, null, "stub"));
        when(ml.fingerprint(any(), anyString())).thenReturn(audio(300));
        when(assessments.insertAnswer(any(), any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            checksSaved.add(i.getArgument(1));
            return (long) saved.size();
        });
        when(assessments.findAttempt(anyLong(), any())).thenAnswer(i -> List.copyOf(saved));
        attempt = service.start(WORKER).attemptToken();
    }

    private static byte[] wav() {
        return WavFixtures.wav(2000, 16000, 1, 16);
    }

    private void transcriptIs(String text) {
        when(ml.transcribe(any(), anyString(), anyString())).thenReturn(new MlGateway.Transcript(text, "hi"));
    }

    private AssessmentPipelineService.Challenge asked(int question) {
        return service.challenge(WORKER, attempt, question);
    }

    private String sayingTheCode(AssessmentPipelineService.Challenge challenge) {
        return "मैं पहले मुख्य स्विच बंद करता हूं " + String.join(" ", challenge.code().split(""));
    }

    // ------------------------------------------------------------------------------------------------------------------- the challenge

    @Test
    void theCodeIsOnlyHandedOutForTheNextQuestionAndAFewTimesAnHour() {
        assertTrue(asked(1).required());
        assertTrue(asked(1).code().matches("[1-9]{3}"));
        assertThrows(ConflictException.class, () -> asked(2), "the later questions' codes cannot be collected in advance");
        assertThrows(ConflictException.class, () -> asked(0));
        for (int i = 0; i < 4; i++) asked(1);                       // six in all
        assertThrows(RateLimitExceededException.class, () -> asked(1));
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertDoesNotThrow(() -> asked(1));
    }

    @Test
    void anotherWorkersAttemptTokenGetsNoCode() {
        assertThrows(com.kaarigarsetu.exception.ValidationException.class, () -> service.challenge(8L, attempt, 1));
    }

    @Test
    void whenTheChallengeIsOffNoCodeIsIssuedAndNoneIsNeeded() {
        AssessmentPipelineService off = build(Map.of("REPLAY_CHALLENGE", "false"));
        String token = off.start(WORKER).attemptToken();
        assertFalse(off.challenge(WORKER, token, 1).required());
        transcriptIs("जवाब");
        assertEquals("NOT_ASKED", submitWith(off, token, 1, null).status);
    }

    private record Submitted(String status) {
    }

    private Submitted submitWith(AssessmentPipelineService s, String token, int q, String challenge) {
        s.submitAnswer(WORKER, token, q, wav(), challenge);
        return new Submitted(checksSaved.get(checksSaved.size() - 1).challengeStatus());
    }

    @Test
    void anAnswerWithoutAFreshCodeIsRefusedBeforeAnyWorkIsDone() {
        asked(1);
        for (String token : new String[]{null, "", "garbage", "1.AAAA"}) {
            ChallengeException refused = assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 1, wav(), token));
            assertEquals(ChallengeException.Kind.REQUIRED, refused.kind());
        }
        verify(ml, never()).transcribe(any(), anyString(), anyString());
        verify(assessments, never()).insertAnswer(any(), any());
    }

    @Test
    void aCodeFromAnotherQuestionOrAfterItsLifetimeIsRefused() {
        AssessmentPipelineService.Challenge first = asked(1);
        String forQuestionOne = first.token();
        transcriptIs(sayingTheCode(first));
        service.submitAnswer(WORKER, attempt, 1, wav(), forQuestionOne);
        assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 2, wav(), forQuestionOne), "question 1's code on question 2");
        AssessmentPipelineService.Challenge second = asked(2);
        clock.advance(Duration.ofMinutes(16));
        assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 2, wav(), second.token()), "stale");
    }

    @Test
    void anAnswerThatContainsTheCodeIsStoredAsHeard() {
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs(sayingTheCode(challenge));
        AssessmentPipelineService.AnswerOutcome outcome = service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertFalse(outcome.needsReview());
        assertEquals("HEARD", checksSaved.get(0).challengeStatus());
        assertEquals("CLEAR", checksSaved.get(0).replayStatus());
        assertEquals(300, checksSaved.get(0).fingerprintCount());
        verify(replayDao).store(eq(1L), argThat(k -> k.length == 300), argThat(t -> t.length == 300), any());
        verify(audit, never()).log(eq("REPLAY_SUSPECTED"), anyString(), anyString());
    }

    @Test
    void aMissedCodeIsRefusedTwiceAndThenTheAnswerIsStoredAndHeldForReview() {
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs("मैं पहले मुख्य स्विच बंद करता हूं और फिर काम शुरू करता हूं");
        for (int tries = 1; tries <= 2; tries++) {
            ChallengeException missed = assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token()));
            assertEquals(ChallengeException.Kind.NOT_HEARD, missed.kind());
        }
        verify(assessments, never()).insertAnswer(any(), any());
        AssessmentPipelineService.AnswerOutcome third = service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertTrue(third.needsReview());
        assertEquals("NOT_HEARD", checksSaved.get(0).challengeStatus());
        assertTrue(saved.get(0).reviewReason().contains("spoken code was not heard after 3 tries"));
        assertEquals(0, new BigDecimal("80.00").compareTo(saved.get(0).score()), "not evidence of replay: the score stands, the answer is held");
        verify(audit).log(eq("CHALLENGE_NOT_HEARD"), eq("worker:7"), contains("question=1"));
    }

    @Test
    void aWorkerWhoSaysTheCodeOnTheSecondTryIsNotHeldAtAll() {
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs("मैं स्विच बंद करता हूं");
        assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token()));
        transcriptIs(sayingTheCode(challenge));
        assertFalse(service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token()).needsReview());
        assertEquals("HEARD", checksSaved.get(0).challengeStatus());
    }

    @Test
    void theNumberOfTriesIsConfigurableAndCountedPerQuestion() {
        AssessmentPipelineService strict = build(Map.of("REPLAY_CHALLENGE_TRIES", "1"));
        String token = strict.start(WORKER).attemptToken();
        AssessmentPipelineService.Challenge challenge = strict.challenge(WORKER, token, 1);
        transcriptIs("मैं स्विच बंद करता हूं");
        assertTrue(strict.submitAnswer(WORKER, token, 1, wav(), challenge.token()).needsReview(), "one try: held at once");
        AssessmentPipelineService.Challenge next = strict.challenge(WORKER, token, 2);
        assertTrue(strict.submitAnswer(WORKER, token, 2, wav(), next.token()).needsReview());
    }

    @Test
    void theAudioIsZeroedEvenWhenTheCodeIsRefused() {
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs("कोई कोड नहीं");
        byte[] audio = wav();
        assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 1, audio, challenge.token()));
        for (byte b : audio) assertEquals(0, b);
        byte[] early = wav();
        assertThrows(ChallengeException.class, () -> service.submitAnswer(WORKER, attempt, 1, early, null));
        for (byte b : early) assertEquals(0, b);
    }

    // ------------------------------------------------------------------------------------------------------------------- the verdicts

    @Test
    void aRecordingSeenBeforeIsStoredFlaggedWithNoScoreAndAuditedWithoutNamingAnyone() {
        when(replayDao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 12, 140)));
        when(replayDao.findMeta(any())).thenReturn(Map.of(900L, new ReplayDao.Meta(900, 8, java.util.UUID.randomUUID(), 1, 500, 20)));
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs(sayingTheCode(challenge));
        AssessmentPipelineService.AnswerOutcome outcome = service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertTrue(outcome.needsReview());
        assertEquals(0, BigDecimal.ZERO.compareTo(outcome.score()));
        assertEquals("REPLAY_AUDIO", checksSaved.get(0).replayStatus());
        assertTrue(saved.get(0).reviewReason().contains("possible replay"));
        verify(replayDao).store(eq(1L), any(), any(), any());                      // its fingerprint is kept, so a third use is found too
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq("REPLAY_SUSPECTED"), eq("worker:7"), details.capture());
        assertTrue(details.getValue().contains("status=REPLAY_AUDIO") && details.getValue().contains("match=900") && details.getValue().contains("sameWorker=false"));
        assertFalse(details.getValue().matches(".*\\b[6-9]\\d{9}\\b.*"));
    }

    @Test
    void theSameWordsAsSomeoneElseAreFlaggedToo() {
        String long30 = IntStream.range(0, 30).mapToObj(i -> "word" + (char) ('a' + i % 26) + (char) ('a' + i / 26)).reduce("", (a, b) -> a + " " + b).replaceAll("\\d", "");
        when(replayDao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 24)));
        when(replayDao.findMeta(any())).thenReturn(Map.of(500L, new ReplayDao.Meta(500, 8, java.util.UUID.randomUUID(), 1, 500, 28)));
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs(long30 + " " + String.join(" ", challenge.code().split("")));
        AssessmentPipelineService.AnswerOutcome outcome = service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertTrue(outcome.needsReview());
        assertEquals("REPLAY_TEXT", checksSaved.get(0).replayStatus());
    }

    @Test
    void ifTheFingerprintCannotBeTakenTheAnswerIsHeldNotWavedThrough() {
        when(ml.fingerprint(any(), anyString())).thenThrow(new com.kaarigarsetu.exception.ExternalServiceException("down"));
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs(sayingTheCode(challenge));
        AssessmentPipelineService.AnswerOutcome outcome = service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertTrue(outcome.needsReview());
        assertEquals("CHECK_FAILED", checksSaved.get(0).replayStatus());
        assertEquals(0, new BigDecimal("80.00").compareTo(outcome.score()));
        verify(audit).log(eq("REPLAY_CHECK_FAILED"), eq("worker:7"), anyString());
    }

    @Test
    void withDetectionOffNothingIsFingerprintedSearchedOrStored() {
        AssessmentPipelineService off = build(Map.of("REPLAY_DETECTION", "false", "REPLAY_CHALLENGE", "false"));
        String token = off.start(WORKER).attemptToken();
        transcriptIs("जवाब");
        off.submitAnswer(WORKER, token, 1, wav(), null);
        verify(ml, never()).fingerprint(any(), anyString());
        verify(replayDao).store(eq(1L), argThat(k -> k.length == 0), argThat(t -> t.length == 0), argThat(h -> h.length == 0));   // nothing to keep
        verify(replayDao, never()).findAudioVotes(any(), any(), anyInt());
        verify(replayDao, never()).findShingleOverlap(any(), anyInt());
        assertEquals("NOT_CHECKED", checksSaved.get(0).replayStatus());
    }

    @Test
    void aFlaggedAnswerIsStillCountedInTheFinishedAttemptSoTheWorkerSeesItUnderReview() {
        when(replayDao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 0, 200)));
        when(replayDao.findMeta(any())).thenReturn(Map.of(900L, new ReplayDao.Meta(900, 8, java.util.UUID.randomUUID(), 1, 500, 20)));
        AssessmentPipelineService.Challenge challenge = asked(1);
        transcriptIs(sayingTheCode(challenge));
        service.submitAnswer(WORKER, attempt, 1, wav(), challenge.token());
        assertTrue(saved.get(0).needsReview());
        assertEquals(1, service.result(WORKER, attempt).size());
    }
}
