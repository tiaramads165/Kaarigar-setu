package com.kaarigarsetu.assessment;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.ReplayDao;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.security.PhoneProtector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplayDetectorTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private final PhoneProtector phones = new PhoneProtector(KEY, KEY, new SecureRandom());
    private final ReplayDao dao = mock(ReplayDao.class);
    private final AppSettings settings = AppSettings.of(Map.of());      // 25 landmarks, 100 minimum, 14 shingles, share 0.5, 8 in common
    private final ReplayDetector detector = new ReplayDetector(dao, phones, settings);
    private final UUID attempt = UUID.randomUUID();

    /** n distinct landmarks with a time gap between 1 and 60 and increasing times. */
    private static MlGateway.Fingerprint audio(int n) {
        int[] hashes = IntStream.range(0, n).map(i -> ((i % 256) << 15) | (((i * 7) % 256) << 7) | (1 + i % 60)).toArray();
        int[] times = IntStream.range(0, n).map(i -> i * 3).toArray();
        return new MlGateway.Fingerprint(hashes, times, n / 100.0);
    }

    private static String words(int n, String stem) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) text.append(stem).append(letters(i)).append(' ');
        return text.toString().trim();
    }

    private static String letters(int n) {
        StringBuilder out = new StringBuilder();
        do {
            out.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return out.toString();
    }

    private static ReplayDao.Meta meta(long id, long worker, UUID attemptId, int shingles) {
        return new ReplayDao.Meta(id, worker, attemptId, 1, 500, shingles);
    }

    @Test
    void theSameRecordingPlayedAgainIsFlaggedAndGetsNoScore() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 12, 140)));
        when(dao.findMeta(any())).thenReturn(Map.of(900L, meta(900, 8, UUID.randomUUID(), 20)));
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), "short answer").verdict();
        assertEquals("REPLAY_AUDIO", v.status());
        assertTrue(v.needsReview() && v.zeroScore());
        assertEquals(900L, v.matchedAssessmentId());
        assertFalse(v.sameWorker());
        assertEquals(140, v.audioMatches());
        assertTrue(v.reason().contains("recording"));
    }

    @Test
    void aMatchByTheSameWorkersEarlierAnswerIsAlsoAReplay() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 0, 300)));
        when(dao.findMeta(any())).thenReturn(Map.of(900L, meta(900, 7, UUID.randomUUID(), 0)));
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), "").verdict();
        assertEquals("REPLAY_AUDIO", v.status());
        assertTrue(v.sameWorker(), "re-using your own earlier recording is still replay");
    }

    @Test
    void fewerSharedLandmarksThanTheThresholdIsClear() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 12, 24), new ReplayDao.Vote(901, 3, 6)));
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), "").verdict();
        assertEquals("CLEAR", v.status());
        assertFalse(v.needsReview() || v.zeroScore());
    }

    @Test
    void votesForNeighbouringShiftsArePooledBecauseACopyStartsAtAnArbitraryInstant() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 11, 10), new ReplayDao.Vote(900, 12, 10), new ReplayDao.Vote(900, 13, 8)));
        when(dao.findMeta(any())).thenReturn(Map.of(900L, meta(900, 8, UUID.randomUUID(), 0)));
        assertEquals("REPLAY_AUDIO", detector.check(7, attempt, audio(300), "").verdict().status(), "10 + 10 + 8 = 28 >= 25");
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 5, 20), new ReplayDao.Vote(900, 40, 20)));
        assertEquals("CLEAR", detector.check(7, attempt, audio(300), "").verdict().status(), "shifts far apart are not the same copy");
    }

    @Test
    void aMatchIsNeverMoreThanTheNumberOfLandmarksOfTheSearchedRecording() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 1, 9999)));
        when(dao.findMeta(any())).thenReturn(Map.of(900L, meta(900, 8, UUID.randomUUID(), 0)));
        assertEquals(120, detector.check(7, attempt, audio(120), "").verdict().audioMatches());
    }

    @Test
    void everyLandmarkIsSearchedAsItselfAndWithItsTimeGapOneFrameShorterAndLonger() {
        MlGateway.Fingerprint fingerprint = audio(150);
        detector.check(7, attempt, fingerprint, "");
        ArgumentCaptor<long[]> keys = ArgumentCaptor.forClass(long[].class);
        ArgumentCaptor<int[]> times = ArgumentCaptor.forClass(int[].class);
        verify(dao).findAudioVotes(keys.capture(), times.capture(), anyInt());
        assertEquals(keys.getValue().length, times.getValue().length);
        Set<Long> searched = new HashSet<>();
        for (long key : keys.getValue()) searched.add(key);
        for (int i = 0; i < 150; i++) {
            int value = fingerprint.hashes()[i];
            for (int gap = (value & 0x7F) - 1; gap <= (value & 0x7F) + 1; gap++) {
                if (gap >= 1) assertTrue(searched.contains(phones.landmarkKey((value & ~0x7F) | gap)), "landmark " + i + " with gap " + gap);
            }
        }
        assertTrue(keys.getValue().length <= 450 && keys.getValue().length >= 440);
    }

    @Test
    void whatIsStoredAboutAnAnswerIsOnlyKeyedHashesOfItsLandmarksAndWords() {
        MlGateway.Fingerprint fingerprint = audio(150);
        ReplayDetector.Checked checked = detector.check(7, attempt, fingerprint, words(30, "a"));
        assertEquals(150, checked.landmarkKeys().length);
        assertEquals(phones.landmarkKey(fingerprint.hashes()[5]), checked.landmarkKeys()[5]);
        assertArrayEquals(fingerprint.times(), checked.landmarkTimes());
        assertEquals(28, checked.shingleKeys().length);
        for (long key : checked.landmarkKeys()) assertTrue(key >= 0);
        assertNotEquals(fingerprint.hashes()[5], (int) checked.landmarkKeys()[5], "not the landmark itself");
    }

    @Test
    void tooLittleAudioAndTooFewWordsDecideNothing() {
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(40), "just a few words").verdict();
        assertEquals("TOO_SHORT", v.status());
        assertFalse(v.needsReview());
        verify(dao, never()).findAudioVotes(any(), any(), anyInt());
        verify(dao, never()).findShingleOverlap(any(), anyInt());
    }

    @Test
    void anAnswerThatCouldNotBeFingerprintedIsSentToReviewBecauseACheckThatCannotRunDoesNotPass() {
        ReplayDetector.Verdict v = detector.check(7, attempt, null, words(30, "a")).verdict();
        assertEquals("CHECK_FAILED", v.status());
        assertTrue(v.needsReview());
        assertFalse(v.zeroScore(), "not evidence of anything: the score stays, the answer is held");
    }

    @Test
    void theSameWordsFromAnotherWorkerAreFlaggedEvenWhenTheAudioDoesNotMatch() {
        String answer = words(30, "x");
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 24)));
        when(dao.findMeta(any())).thenReturn(Map.of(500L, meta(500, 8, UUID.randomUUID(), 28)));
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), answer).verdict();
        assertEquals("REPLAY_TEXT", v.status());
        assertTrue(v.needsReview() && v.zeroScore());
        assertEquals(500L, v.matchedAssessmentId());
        assertEquals(24 / 28.0, v.textShare(), 1e-9);
    }

    @Test
    void yourOwnEarlierAttemptIsNotACopyButAnotherQuestionOfThisAttemptIs() {
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 27)));
        when(dao.findMeta(any())).thenReturn(Map.of(500L, meta(500, 7, UUID.randomUUID(), 28)));
        assertEquals("CLEAR", detector.check(7, attempt, audio(300), words(30, "x")).verdict().status(), "the same worker, an earlier attempt: people repeat themselves");
        when(dao.findMeta(any())).thenReturn(Map.of(500L, meta(500, 7, attempt, 28)));
        assertEquals("REPLAY_TEXT", detector.check(7, attempt, audio(300), words(30, "x")).verdict().status(), "the same answer given to two questions of one attempt");
    }

    @Test
    void aSmallOverlapOrASmallShareIsNotAMatch() {
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 8), new ReplayDao.Overlap(501, 14)));
        when(dao.findMeta(any())).thenReturn(Map.of(500L, meta(500, 8, UUID.randomUUID(), 28), 501L, meta(501, 9, UUID.randomUUID(), 80)));
        // 8 of 28 is 0.29; 14 of min(28, 80) = 28 is 0.50 exactly: at the line it counts
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), words(30, "x")).verdict();
        assertEquals("REPLAY_TEXT", v.status());
        assertEquals(501L, v.matchedAssessmentId());
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 8)));
        assertEquals("CLEAR", detector.check(7, attempt, audio(300), words(30, "x")).verdict().status());
    }

    @Test
    void aMatchedAnswerTooShortToJudgeIsIgnored() {
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(500, 9)));
        when(dao.findMeta(any())).thenReturn(Map.of(500L, meta(500, 8, UUID.randomUUID(), 9)));
        assertEquals("CLEAR", detector.check(7, attempt, audio(300), words(30, "x")).verdict().status());
    }

    @Test
    void bothSignalsTogetherAreReportedAsBoth() {
        when(dao.findAudioVotes(any(), any(), anyInt())).thenReturn(List.of(new ReplayDao.Vote(900, 12, 140)));
        when(dao.findShingleOverlap(any(), anyInt())).thenReturn(List.of(new ReplayDao.Overlap(900, 26)));
        when(dao.findMeta(any())).thenReturn(Map.of(900L, meta(900, 8, UUID.randomUUID(), 28)));
        ReplayDetector.Verdict v = detector.check(7, attempt, audio(300), words(30, "x")).verdict();
        assertEquals("REPLAY_BOTH", v.status());
        assertEquals(140, v.audioMatches());
    }

    @Test
    void whenDetectionIsOffNothingIsSearchedOrStored() {
        ReplayDetector off = new ReplayDetector(dao, phones, AppSettings.of(Map.of("REPLAY_DETECTION", "false")));
        ReplayDetector.Checked checked = off.check(7, attempt, audio(300), words(30, "x"));
        assertEquals("NOT_CHECKED", checked.verdict().status());
        assertEquals(0, checked.landmarkKeys().length + checked.shingleKeys().length);
        verifyNoInteractions(dao);
    }

    @Test
    void theKeysAreKeyedSoAnotherKeyHoldersCannotMatchOrReadThem() {
        PhoneProtector other = new PhoneProtector("ffffffffffffffffffffffffffffffff".getBytes(), "ffffffffffffffffffffffffffffffff".getBytes(), new SecureRandom());
        assertNotEquals(phones.landmarkKey(12345), other.landmarkKey(12345));
        assertEquals(phones.landmarkKey(12345), phones.landmarkKey(12345));
        assertNotEquals(phones.landmarkKey(12345), phones.landmarkKey(12346));
        assertNotEquals(phones.shingleKey("a b c"), phones.shingleKey("a b d"));
        assertTrue(Arrays.stream(new long[]{phones.landmarkKey(1), phones.shingleKey("x y z")}).allMatch(k -> k >= 0));
    }
}
