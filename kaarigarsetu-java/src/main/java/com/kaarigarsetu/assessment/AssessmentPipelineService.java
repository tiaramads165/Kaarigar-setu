package com.kaarigarsetu.assessment;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.dao.ReplayDao;
import com.kaarigarsetu.exception.ChallengeException;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.UnintelligibleAudioException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.RateLimiter;
import com.kaarigarsetu.util.Json;
import com.kaarigarsetu.util.WavValidator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker answers -> ML transcribes + scores -> score persisted. One attempt = ten ordered questions (AssessmentService.QUESTIONS_PER_ASSESSMENT). An attempt is
 * identified by a server-signed token (so a client cannot invent attempt ids or replay one for another worker),
 * and raw audio is held only in memory: it is zeroed as soon as it has been transcribed.
 *
 * <p>Replay defences (docs/REPLAY_DETECTION.md): each question is asked with a random spoken code that the answer must contain (a recording made before the
 * question cannot), and each stored answer is compared, by acoustic landmarks and by word trigrams, with every earlier answer of every worker. A recording or text
 * that has been used before is stored but flagged for review with no score; an answer that does not contain the code is refused twice (so a recogniser's mistake
 * costs a worker a second try, not the assessment) and then stored and flagged.
 */
public class AssessmentPipelineService {
    public record Started(String attemptToken, String trade, String language, boolean testOnly, List<MlGateway.Question> questions) {}

    public record AnswerOutcome(int questionNo, String transcript, BigDecimal score, String feedback, boolean needsReview,
                                boolean finished, BigDecimal skillScore, String skillFeedback, String provider,
                                String skillBand, Map<String, BigDecimal> skillAreas, boolean nearBoundary) {}

    /** What the transaction that checks and stores an answer decided. */
    private record Stored(ReplayDetector.Verdict verdict, BigDecimal score, boolean needsReview) {}

    /** The spoken code for one question: {@code required} is false when the challenge is switched off. */
    public record Challenge(boolean required, String code, String token, int minutes) {}

    private record CachedRubric(MlGateway.Rubric rubric, Instant expires) {}

    private static final Duration TOKEN_LIFETIME = Duration.ofHours(3);
    private static final Duration RUBRIC_TTL = Duration.ofMinutes(10);

    private final WorkerDao workerDao;
    private final AssessmentDao assessmentDao;
    private final MlGateway ml;
    private final AssessmentScoringDispatcher dispatcher;
    private final AuditLogger audit;
    private final AppSettings settings;
    private final byte[] tokenKey;
    private final Clock clock;
    private final Transactions transactions;
    private final Map<String, CachedRubric> rubrics = new ConcurrentHashMap<>();
    private final ReplayDetector replay;
    private final ReplayChallenge challenges;
    private final ReplayDao replayDao;
    private final RateLimiter limiter;
    /** Misses of the spoken code per (attempt, question), so a recogniser's mistake costs a retry. Bounded; a restart forgets it (the worker simply gets the tries again). */
    private final Map<String, Integer> codeMisses = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 20_000;
        }
    };

    public AssessmentPipelineService(WorkerDao workerDao, AssessmentDao assessmentDao, MlGateway ml,
                                     AssessmentScoringDispatcher dispatcher, AuditLogger audit, AppSettings settings,
                                     byte[] tokenKey, Clock clock, Transactions transactions,
                                     ReplayDetector replay, ReplayChallenge challenges, ReplayDao replayDao, RateLimiter limiter) {
        this.workerDao = workerDao;
        this.assessmentDao = assessmentDao;
        this.ml = ml;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.settings = settings;
        this.tokenKey = tokenKey.clone();
        this.clock = clock;
        this.transactions = transactions;
        this.replay = replay;
        this.challenges = challenges;
        this.replayDao = replayDao;
        this.limiter = limiter;
    }

    /**
     * The spoken code for the NEXT unanswered question, made now (so no recording can pre-date it). Only the next question gets one, and a few times an hour at
     * most, so the codes of the later questions cannot be collected in advance.
     */
    public Challenge challenge(long workerId, String attemptToken, int questionNo) {
        UUID attemptId = verify(workerId, attemptToken);
        if (!settings.replayChallengeEnabled()) return new Challenge(false, null, null, 0);
        if (questionNo != assessmentDao.findAttempt(workerId, attemptId).size() + 1) throw new ConflictException("That is not the next question");
        limiter.check("assess:challenge:" + attemptId + ":" + questionNo, 6, Duration.ofHours(1));
        ReplayChallenge.Issued issued = challenges.issue(workerId, attemptId, questionNo);
        return new Challenge(true, issued.code(), issued.token(), issued.minutes());
    }

    public Started start(long workerId) {
        Worker worker = workerDao.findById(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
        MlGateway.Rubric rubric = rubric(worker.trade(), worker.language());
        UUID attemptId = UUID.randomUUID();
        audit.log("ASSESSMENT_STARTED", "worker:" + workerId, "trade=" + worker.trade() + " attempt=" + attemptId);
        return new Started(sign(workerId, attemptId, clock.instant()), worker.trade(), worker.language(), rubric.testOnly(), rubric.questions());
    }

    public AnswerOutcome submitAnswer(long workerId, String attemptToken, int questionNo, byte[] audio, String challengeToken) {
        try {
            UUID attemptId = verify(workerId, attemptToken);
            Worker worker = workerDao.findById(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
            MlGateway.Rubric rubric = rubric(worker.trade(), worker.language());
            int total = Math.min(rubric.questions().size(), settings.questionsPerAssessment());
            List<AssessmentAnswer> existing = assessmentDao.findAttempt(workerId, attemptId);
            if (existing.size() >= total) throw new ConflictException("Assessment already complete");
            if (questionNo != existing.size() + 1) throw new ConflictException("Questions must be answered in order");
            WavValidator.validate(audio, settings.maxAudioBytes());

            String code = settings.replayChallengeEnabled() ? challenges.codeFor(workerId, attemptId, questionNo, challengeToken) : null;   // a stale or foreign code is refused before any work is done

            MlGateway.Transcript transcript = ml.transcribe(audio, "answer.wav", worker.language());
            MlGateway.Fingerprint fingerprint = fingerprintOf(audio);
            Arrays.fill(audio, (byte) 0);   // raw voice data is never retained
            if (transcript.transcript() == null || transcript.transcript().isBlank()) {
                throw new UnintelligibleAudioException("No speech recognised");
            }
            String challengeStatus = "NOT_ASKED";
            if (code != null) {
                if (SpokenCode.heard(transcript.transcript(), code)) {
                    challengeStatus = "HEARD";
                } else if (recordMiss(attemptId, questionNo) < settings.replayChallengeTries()) {
                    throw new ChallengeException(ChallengeException.Kind.NOT_HEARD, "The spoken code was not heard");
                } else {
                    challengeStatus = "NOT_HEARD";
                }
            }
            MlGateway.Evaluation evaluation = ml.evaluate(worker.trade(), transcript.transcript(), questionNo, worker.language());
            boolean notHeard = challengeStatus.equals("NOT_HEARD");
            final String challengeResult = challengeStatus;
            final String spoken = transcript.transcript();
            Stored stored;
            try {
                stored = transactions.inTransaction(() -> {
                    // One answer at a time is compared and stored: two uploads of the same recording arriving together would otherwise each look for the other
                    // before either is stored, and both would pass. The lock is held until this transaction ends, so the second one finds the first.
                    replayDao.lockAnswerChecks();
                    ReplayDetector.Checked checked = replay.check(workerId, attemptId, fingerprint, spoken);
                    ReplayDetector.Verdict verdict = checked.verdict();
                    BigDecimal score = verdict.zeroScore() ? BigDecimal.ZERO.setScale(2) : BigDecimal.valueOf(evaluation.score()).setScale(2, RoundingMode.HALF_UP);
                    boolean needsReview = evaluation.needsReview() || verdict.needsReview() || notHeard;
                    String reviewReason = joinReasons(evaluation.reviewReason(), verdict.reason(), notHeard ? "the spoken code was not heard after " + settings.replayChallengeTries() + " tries" : null);
                    AssessmentDao.Checks checks = new AssessmentDao.Checks(verdict.status(), challengeResult, checked.landmarkKeys().length, checked.shingleKeys().length);
                    long answerId = assessmentDao.insertAnswer(new AssessmentAnswer(null, workerId, worker.trade(), attemptId, questionNo, worker.language(),
                            spoken, score, evaluation.feedback(), needsReview, reviewReason, evaluation.provider(), null), checks);
                    replayDao.store(answerId, checked.landmarkKeys(), checked.landmarkTimes(), checked.shingleKeys());
                    return new Stored(verdict, score, needsReview);
                });
            } catch (DataAccessException exception) {
                if (exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new ConflictException("Question already answered");
                }
                throw exception;
            }
            ReplayDetector.Verdict verdict = stored.verdict();
            BigDecimal score = stored.score();
            boolean needsReview = stored.needsReview();
            auditReplay(workerId, attemptId, questionNo, verdict, notHeard);

            boolean finished = existing.size() + 1 == total;
            BigDecimal skill = null;
            String skillFeedback = null;
            SkillProfile profile = null;
            if (finished) {
                List<AssessmentAnswer> all = assessmentDao.findAttempt(workerId, attemptId);
                AssessmentResult result = dispatcher.summarize(worker.trade(), all.stream().map(AssessmentAnswer::score).toList());
                Map<Integer, String> areaOf = new java.util.HashMap<>();
                rubric.questions().forEach(q -> areaOf.put(q.id(), q.area()));
                profile = SkillScoring.profile(result.score(),
                        all.stream().map(a -> new SkillScoring.QuestionScore(areaOf.getOrDefault(a.questionNo(), "knowledge"), a.score())).toList(),
                        BigDecimal.valueOf(settings.passThreshold()), BigDecimal.valueOf(settings.borderlineThreshold()), BigDecimal.valueOf(settings.boundaryMargin()));
                workerDao.updateSkillProfile(workerId, result.score(), profile.band().name(), Json.toJson(profile.areas()), profile.nearBoundary());
                skill = result.score();
                skillFeedback = result.feedback();
                audit.log("ASSESSMENT_COMPLETED", "worker:" + workerId, "attempt=" + attemptId + " skill=" + skill + " band=" + profile.band()
                        + " flaggedAnswers=" + all.stream().filter(AssessmentAnswer::needsReview).count());
            }
            return new AnswerOutcome(questionNo, transcript.transcript(), score, evaluation.feedback(), needsReview,
                    finished, skill, skillFeedback, evaluation.provider(),
                    profile == null ? null : profile.band().name(), profile == null ? null : profile.areas(), profile != null && profile.nearBoundary());
        } finally {
            if (audio != null) Arrays.fill(audio, (byte) 0);
        }
    }

    /** The recording's landmarks, or null if they could not be had (the answer is then sent to review: a check that cannot run does not pass). */
    private MlGateway.Fingerprint fingerprintOf(byte[] audio) {
        if (!settings.replayDetectionEnabled()) return null;
        try {
            return ml.fingerprint(audio, "answer.wav");
        } catch (RuntimeException failure) {
            System.getLogger("ks.assessment").log(System.Logger.Level.WARNING, "audio fingerprint unavailable: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private int recordMiss(UUID attemptId, int questionNo) {
        synchronized (codeMisses) {
            return codeMisses.merge(attemptId + ":" + questionNo, 1, Integer::sum);
        }
    }

    private static String joinReasons(String... reasons) {
        List<String> present = Arrays.stream(reasons).filter(r -> r != null && !r.isBlank()).toList();
        return present.isEmpty() ? null : String.join("; ", present);
    }

    private void auditReplay(long workerId, UUID attemptId, int questionNo, ReplayDetector.Verdict verdict, boolean notHeard) {
        String who = "worker:" + workerId;
        String where = "attempt=" + attemptId + " question=" + questionNo;
        if (verdict.status().startsWith("REPLAY")) {
            audit.log("REPLAY_SUSPECTED", who, where + " status=" + verdict.status() + " match=" + verdict.matchedAssessmentId() + " sameWorker=" + verdict.sameWorker()
                    + " landmarks=" + verdict.audioMatches() + " textShare=" + String.format(java.util.Locale.ROOT, "%.2f", verdict.textShare()));
        } else if (verdict.status().equals("CHECK_FAILED")) {
            audit.log("REPLAY_CHECK_FAILED", who, where);
        }
        if (notHeard) audit.log("CHALLENGE_NOT_HEARD", who, where + " tries=" + settings.replayChallengeTries());
    }

    public List<AssessmentAnswer> result(long workerId, String attemptToken) {
        return assessmentDao.findAttempt(workerId, verify(workerId, attemptToken));
    }

    private MlGateway.Rubric rubric(String trade, String language) {
        String key = trade + "|" + language;
        CachedRubric cached = rubrics.get(key);
        if (cached != null && cached.expires().isAfter(clock.instant())) return cached.rubric();
        MlGateway.Rubric fresh = ml.rubric(trade, language);
        rubrics.put(key, new CachedRubric(fresh, clock.instant().plus(RUBRIC_TTL)));
        return fresh;
    }

    // ---- stateless signed attempt token:  <attemptId>.<epochSecond>.<HMAC(workerId|attemptId|epoch)> ----

    private String sign(long workerId, UUID attemptId, Instant issued) {
        long epoch = issued.getEpochSecond();
        return attemptId + "." + epoch + "." + mac(workerId, attemptId, epoch);
    }

    private UUID verify(long workerId, String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new ValidationException("Invalid attempt");
            UUID attemptId = UUID.fromString(parts[0]);
            long epoch = Long.parseLong(parts[1]);
            boolean macOk = MessageDigest.isEqual(mac(workerId, attemptId, epoch).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8));
            if (!macOk || Instant.ofEpochSecond(epoch).plus(TOKEN_LIFETIME).isBefore(clock.instant())) throw new ValidationException("Invalid attempt");
            return attemptId;
        } catch (RuntimeException exception) {
            if (exception instanceof ValidationException validation) throw validation;
            throw new ValidationException("Invalid attempt");
        }
    }

    private String mac(long workerId, UUID attemptId, long epoch) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(("attempt:" + workerId + "|" + attemptId + "|" + epoch).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
