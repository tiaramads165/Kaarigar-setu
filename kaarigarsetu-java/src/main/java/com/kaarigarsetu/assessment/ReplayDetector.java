package com.kaarigarsetu.assessment;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.ReplayDao;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.security.PhoneProtector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Is this answer a recording, or a set of words, that has been used before? Two independent checks against every stored answer of every worker (docs/REPLAY_DETECTION.md):
 *
 * <ul>
 *   <li><b>Audio:</b> the recording's acoustic landmarks, looked up as keyed hashes. The SAME recording shares dozens to hundreds of landmarks with its earlier copy at
 *       one consistent time shift, whatever volume, noise, trim, band or room changed; different recordings, even of one voice saying similar things, share a handful.
 *       It does not survive a change of speed.</li>
 *   <li><b>Words:</b> the answer's word trigrams, looked up as keyed hashes. The same words said again are found whatever the audio did (a changed speed, a synthetic voice
 *       reading a stolen transcript, a shared script); measured on real speech recognition, a replay keeps about half to all of its trigrams and different answers keep none. A worker's OWN earlier attempt is not a match (people repeat themselves), but a copy across workers, or across
 *       two questions of one attempt, is.</li>
 * </ul>
 *
 * Either one is enough to send the answer to review and give it no score. If the audio could not be fingerprinted the answer is sent to review too (a security check that
 * cannot run does not pass). Too little audio or too few words is "too short" and decides nothing.
 */
public class ReplayDetector {
    public record Verdict(String status, boolean needsReview, boolean zeroScore, String reason, int audioMatches, double textShare, Long matchedAssessmentId, boolean sameWorker) {
        static Verdict of(String status) {
            return new Verdict(status, false, false, null, 0, 0, null, false);
        }
    }

    /** The verdict plus what to store about this answer so later answers can be compared with it. */
    public record Checked(Verdict verdict, long[] landmarkKeys, int[] landmarkTimes, long[] shingleKeys) {
    }

    private static final int MIN_VOTES_TO_LIST = 3;
    private static final long[] NO_KEYS = new long[0];
    private static final int[] NO_TIMES = new int[0];

    private final ReplayDao dao;
    private final PhoneProtector phones;
    private final AppSettings settings;

    public ReplayDetector(ReplayDao dao, PhoneProtector phones, AppSettings settings) {
        this.dao = dao;
        this.phones = phones;
        this.settings = settings;
    }

    /** @param audio the recording's landmarks, or null when they could not be had */
    public Checked check(long workerId, UUID attemptId, MlGateway.Fingerprint audio, String transcript) {
        if (!settings.replayDetectionEnabled()) return new Checked(Verdict.of("NOT_CHECKED"), NO_KEYS, NO_TIMES, NO_KEYS);
        long[] shingles = shingleKeys(transcript);
        boolean textUsable = shingles.length >= settings.replayTextMinShingles();
        long[] landmarks = audio == null ? NO_KEYS : landmarkKeys(audio);
        boolean audioUsable = audio != null && audio.hashes().length >= settings.replayMinLandmarks();

        Best audioHit = audioUsable ? searchAudio(audio) : null;
        Best textHit = textUsable ? searchText(workerId, attemptId, shingles) : null;
        Map<Long, ReplayDao.Meta> meta = dao.findMeta(ids(audioHit, textHit));
        boolean audioMatches = audioHit != null && audioHit.score >= settings.replayMinAudioMatches();
        boolean textMatches = textHit != null;
        ReplayDao.Meta audioMeta = audioMatches ? meta.get(audioHit.assessmentId) : null;
        ReplayDao.Meta textMeta = textMatches ? meta.get(textHit.assessmentId) : null;

        if (audioMatches || textMatches) {
            String status = audioMatches && textMatches ? "REPLAY_BOTH" : audioMatches ? "REPLAY_AUDIO" : "REPLAY_TEXT";
            Long matched = audioMatches ? audioHit.assessmentId : textHit.assessmentId;
            ReplayDao.Meta shown = audioMatches ? audioMeta : textMeta;
            boolean same = shown != null && shown.workerId() == workerId;
            String reason = audioMatches
                    ? "this recording matches an earlier answer (possible replay of a recording)"
                    : "these words match an earlier answer by someone else (possible replay, or an answer copied from another person)";
            Verdict verdict = new Verdict(status, true, true, reason, audioHit == null ? 0 : (int) Math.round(audioHit.score), textHit == null ? 0 : textHit.score, matched, same);
            return new Checked(verdict, landmarks, audio == null ? NO_TIMES : audio.times(), shingles);
        }
        if (audio == null) {
            return new Checked(new Verdict("CHECK_FAILED", true, false, "the replay check could not be run on this recording", 0, 0, null, false), NO_KEYS, NO_TIMES, shingles);
        }
        Verdict verdict = Verdict.of(!audioUsable && !textUsable ? "TOO_SHORT" : "CLEAR");
        return new Checked(verdict, landmarks, audio.times(), shingles);
    }

    private record Best(long assessmentId, double score) {
    }

    private static List<Long> ids(Best... hits) {
        List<Long> ids = new ArrayList<>();
        for (Best hit : hits) if (hit != null) ids.add(hit.assessmentId);
        return ids;
    }

    private long[] landmarkKeys(MlGateway.Fingerprint audio) {
        long[] keys = new long[audio.hashes().length];
        for (int i = 0; i < keys.length; i++) keys[i] = phones.landmarkKey(audio.hashes()[i]);
        return keys;
    }

    private long[] shingleKeys(String transcript) {
        return TextShingles.of(transcript).stream().mapToLong(phones::shingleKey).toArray();
    }

    /**
     * Each landmark is searched as itself and with its time gap one frame shorter and longer: a copy that starts a fraction of a frame later (every real replay does) puts
     * its peaks one frame off. Votes for time shifts one frame apart are pooled for the same reason. This mirrors the ML service's aligned_matches().
     */
    private Best searchAudio(MlGateway.Fingerprint audio) {
        int count = audio.hashes().length;
        long[] keys = new long[count * 3];
        int[] times = new int[count * 3];
        int used = 0;
        for (int i = 0; i < count; i++) {
            int value = audio.hashes()[i];
            int gap = value & 0x7F;
            for (int g = gap - 1; g <= gap + 1; g++) {
                if (g < 1 || g > 64) continue;
                keys[used] = phones.landmarkKey((value & ~0x7F) | g);
                times[used] = audio.times()[i];
                used++;
            }
        }
        Map<Long, Map<Integer, Integer>> byAnswer = new HashMap<>();
        for (ReplayDao.Vote vote : dao.findAudioVotes(java.util.Arrays.copyOf(keys, used), java.util.Arrays.copyOf(times, used), MIN_VOTES_TO_LIST)) {
            byAnswer.computeIfAbsent(vote.assessmentId(), k -> new HashMap<>()).merge(vote.delta(), vote.votes(), Integer::sum);
        }
        Best best = null;
        for (Map.Entry<Long, Map<Integer, Integer>> entry : byAnswer.entrySet()) {
            Map<Integer, Integer> shifts = entry.getValue();
            for (Map.Entry<Integer, Integer> atShift : shifts.entrySet()) {
                int shift = atShift.getKey();
                int pooled = shifts.getOrDefault(shift - 1, 0) + atShift.getValue() + shifts.getOrDefault(shift + 1, 0);
                double score = Math.min(pooled, count);                       // a landmark cannot vote for more than itself
                if (best == null || score > best.score) best = new Best(entry.getKey(), score);
            }
        }
        return best;
    }

    /** The best match among other people's answers (and other questions of this same attempt); the share is of the smaller of the two answers. */
    private Best searchText(long workerId, UUID attemptId, long[] shingles) {
        List<ReplayDao.Overlap> overlaps = dao.findShingleOverlap(shingles, settings.replayTextMinCommon());
        if (overlaps.isEmpty()) return null;
        Set<Long> candidates = new HashSet<>();
        overlaps.forEach(o -> candidates.add(o.assessmentId()));
        Map<Long, ReplayDao.Meta> meta = dao.findMeta(candidates);
        Best best = null;
        for (ReplayDao.Overlap overlap : overlaps) {
            ReplayDao.Meta other = meta.get(overlap.assessmentId());
            if (other == null) continue;
            if (other.workerId() == workerId && !attemptId.equals(other.attemptId())) continue;      // your own earlier attempt is not a copy
            int smaller = Math.min(shingles.length, other.shingleCount());
            if (smaller < settings.replayTextMinShingles()) continue;
            double share = (double) overlap.common() / smaller;
            if (share >= settings.replayTextMinShare() && overlap.common() >= settings.replayTextMinCommon() && (best == null || share > best.score)) best = new Best(overlap.assessmentId(), share);
        }
        return best;
    }
}
