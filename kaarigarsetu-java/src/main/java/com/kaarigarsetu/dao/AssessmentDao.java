package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Assessment;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Voice assessments. What the worker said (transcript), what the scorer said about it (feedback, the reason it needs a human) and the score are stored
 * sealed (DataCipher), each tied to its own row id, so a copy of the database holds none of it. Because every sealing is different, a row can no longer be
 * found by comparing a transcript; rows are found by worker and attempt.
 */
public class AssessmentDao extends JdbcDaoSupport {
    private static final String TRANSCRIPT = "assessments.transcript";
    private static final String FEEDBACK = "assessments.feedback";
    private static final String REASON = "assessments.review_reason";
    private static final String SCORE = "assessments.score";

    private final DataCipher cipher;

    public AssessmentDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public Assessment insert(Assessment assessment) {
        requireBetween("score", assessment.score(), 0, 100);
        String sql = "INSERT INTO assessments (assessment_id, worker_id, trade, transcript, score, feedback) VALUES (?, ?, ?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "assessments", "assessment_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, assessment.workerId());
                statement.setString(3, assessment.trade());
                statement.setString(4, cipher.seal(TRANSCRIPT, id, assessment.transcript()));
                statement.setString(5, cipher.sealDecimal(SCORE, id, assessment.score()));
                statement.setString(6, cipher.seal(FEEDBACK, id, assessment.feedback()));
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert assessment", exception);
        }
    }

    public Optional<Assessment> findById(long assessmentId) {
        String sql = "SELECT * FROM assessments WHERE assessment_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, assessmentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find assessment", exception);
        }
    }

    private Assessment map(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("assessment_id");
        return new Assessment(
                id,
                resultSet.getLong("worker_id"),
                resultSet.getString("trade"),
                cipher.open(TRANSCRIPT, id, resultSet.getString("transcript")),
                cipher.openDecimal(SCORE, id, resultSet.getString("score")),
                cipher.open(FEEDBACK, id, resultSet.getString("feedback")),
                resultSet.getTimestamp("created_at").toLocalDateTime()
        );
    }

    /** What the replay checks concluded about an answer (stored beside it; see migration 011). */
    public record Checks(String replayStatus, String challengeStatus, int fingerprintCount, int shingleCount) {
        public static final Checks NONE = new Checks("NOT_CHECKED", "NOT_ASKED", 0, 0);
    }

    public long insertAnswer(com.kaarigarsetu.model.AssessmentAnswer answer) {
        return insertAnswer(answer, Checks.NONE);
    }

    /** @return the new answer's id, so its fingerprints can be stored against it */
    public long insertAnswer(com.kaarigarsetu.model.AssessmentAnswer answer, Checks checks) {
        requireBetween("score", answer.score(), 0, 100);
        String sql = """
                INSERT INTO assessments (assessment_id, worker_id, trade, transcript, score, feedback, attempt_id, question_no, language,
                                         needs_review, review_reason, provider, replay_status, challenge_status, fingerprint_count, shingle_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "assessments", "assessment_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, answer.workerId());
                statement.setString(3, answer.trade());
                statement.setString(4, cipher.seal(TRANSCRIPT, id, answer.transcript()));
                statement.setString(5, cipher.sealDecimal(SCORE, id, answer.score()));
                statement.setString(6, cipher.seal(FEEDBACK, id, answer.feedback()));
                statement.setObject(7, answer.attemptId());
                statement.setInt(8, answer.questionNo());
                statement.setString(9, answer.language());
                statement.setBoolean(10, answer.needsReview());
                statement.setString(11, cipher.seal(REASON, id, answer.reviewReason()));
                statement.setString(12, answer.provider());
                statement.setString(13, checks.replayStatus());
                statement.setString(14, checks.challengeStatus());
                statement.setInt(15, checks.fingerprintCount());
                statement.setInt(16, checks.shingleCount());
                statement.executeUpdate();
            }
            return id;
        } catch (SQLException exception) {
            throw dataAccessException("insert assessment answer", exception);
        }
    }

    public java.util.List<com.kaarigarsetu.model.AssessmentAnswer> findAttempt(long workerId, java.util.UUID attemptId) {
        String sql = "SELECT * FROM assessments WHERE worker_id = ? AND attempt_id = ? ORDER BY question_no";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            statement.setObject(2, attemptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<com.kaarigarsetu.model.AssessmentAnswer> answers = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    long id = resultSet.getLong("assessment_id");
                    answers.add(new com.kaarigarsetu.model.AssessmentAnswer(id,
                            resultSet.getLong("worker_id"), resultSet.getString("trade"), resultSet.getObject("attempt_id", java.util.UUID.class),
                            resultSet.getInt("question_no"), resultSet.getString("language"), cipher.open(TRANSCRIPT, id, resultSet.getString("transcript")),
                            cipher.openDecimal(SCORE, id, resultSet.getString("score")), cipher.open(FEEDBACK, id, resultSet.getString("feedback")), resultSet.getBoolean("needs_review"),
                            cipher.open(REASON, id, resultSet.getString("review_reason")), resultSet.getString("provider"),
                            resultSet.getTimestamp("created_at").toLocalDateTime()));
                }
                return answers;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find assessment attempt", exception);
        }
    }

    /** The worker's most recent attempt that has at least one answer, newest first. */
    public java.util.Optional<java.util.UUID> findLatestAttemptId(long workerId) {
        String sql = "SELECT attempt_id FROM assessments WHERE worker_id = ? AND attempt_id IS NOT NULL ORDER BY created_at DESC, assessment_id DESC LIMIT 1";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? java.util.Optional.of(resultSet.getObject(1, java.util.UUID.class)) : java.util.Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find latest attempt", exception);
        }
    }
}
