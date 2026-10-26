package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.FraudFlag;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fraud flags. The features the model saw (how often an address appeared, timing, reciprocity) are behavioural data about a person, so they are stored sealed (DataCipher), tied to their flag id. */
public class FraudFlagDao extends JdbcDaoSupport {
    private static final String FEATURES = "fraud_flags.features";

    private final DataCipher cipher;

    public FraudFlagDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public FraudFlag insert(FraudFlag flag) {
        String sql = """
                INSERT INTO fraud_flags (attestation_id, anomaly_score, reviewer_decision, reviewed_by, reviewed_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, flag.attestationId());
            statement.setBigDecimal(2, flag.anomalyScore());
            statement.setString(3, flag.reviewerDecision());
            statement.setString(4, flag.reviewedBy());
            if (flag.reviewedAt() == null) {
                statement.setTimestamp(5, null);
            } else {
                statement.setTimestamp(5, Timestamp.valueOf(flag.reviewedAt()));
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException exception) {
            throw dataAccessException("insert fraud flag", exception);
        }
    }

    public Optional<FraudFlag> findById(long flagId) {
        String sql = "SELECT * FROM fraud_flags WHERE flag_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, flagId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find fraud flag", exception);
        }
    }

    public List<FraudFlag> findFlaggedRecords() {
        String sql = "SELECT * FROM fraud_flags ORDER BY flag_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<FraudFlag> flags = new ArrayList<>();
            while (resultSet.next()) {
                flags.add(map(resultSet));
            }
            return flags;
        } catch (SQLException exception) {
            throw dataAccessException("find fraud flags", exception);
        }
    }

    public List<FraudFlag> findByReviewerDecision(String reviewerDecision) {
        String sql = "SELECT * FROM fraud_flags WHERE reviewer_decision = ? ORDER BY flag_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewerDecision);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FraudFlag> flags = new ArrayList<>();
                while (resultSet.next()) {
                    flags.add(map(resultSet));
                }
                return flags;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find fraud flags by reviewer decision", exception);
        }
    }

    public boolean updateReviewerDecision(long flagId, String decision, String reviewedBy, LocalDateTime reviewedAt) {
        String sql = "UPDATE fraud_flags SET reviewer_decision = ?, reviewed_by = ?, reviewed_at = ? WHERE flag_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, decision);
            statement.setString(2, reviewedBy);
            statement.setTimestamp(3, Timestamp.valueOf(reviewedAt));
            statement.setLong(4, flagId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update fraud flag", exception);
        }
    }

    private FraudFlag map(ResultSet resultSet) throws SQLException {
        return new FraudFlag(
                resultSet.getLong("flag_id"),
                resultSet.getLong("attestation_id"),
                resultSet.getBigDecimal("anomaly_score"),
                resultSet.getString("reviewer_decision"),
                resultSet.getString("reviewed_by"),
                optionalDateTime(resultSet, "reviewed_at").orElse(null)
        );
    }

    public FraudFlag insertWithFeatures(long attestationId, java.math.BigDecimal anomalyScore, String features) {
        String sql = "INSERT INTO fraud_flags (flag_id, attestation_id, anomaly_score, reviewer_decision, features) VALUES (?, ?, ?, 'PENDING', ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "fraud_flags", "flag_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, attestationId);
                statement.setBigDecimal(3, anomalyScore);
                statement.setString(4, cipher.seal(FEATURES, id, features));
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert fraud flag", exception);
        }
    }

    /** The moderator queue: flags still awaiting a decision, oldest first. */
    public java.util.List<com.kaarigarsetu.model.FlaggedAttestation> findPendingQueue() {
        String sql = """
                SELECT f.flag_id, f.attestation_id, f.anomaly_score, f.features, a.worker_id, a.employer_id, a.job_role,
                       f.reviewer_decision, f.created_at
                FROM fraud_flags f JOIN attestations a ON a.attestation_id = f.attestation_id
                WHERE f.reviewer_decision = 'PENDING' ORDER BY f.flag_id
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<com.kaarigarsetu.model.FlaggedAttestation> rows = new java.util.ArrayList<>();
            while (resultSet.next()) {
                long flagId = resultSet.getLong("flag_id");
                rows.add(new com.kaarigarsetu.model.FlaggedAttestation(flagId, resultSet.getLong("attestation_id"),
                        resultSet.getBigDecimal("anomaly_score"), cipher.open(FEATURES, flagId, resultSet.getString("features")), resultSet.getLong("worker_id"),
                        resultSet.getLong("employer_id"), cipher.open("attestations.job_role", resultSet.getLong("attestation_id"), resultSet.getString("job_role")),
                        resultSet.getString("reviewer_decision"),
                        resultSet.getTimestamp("created_at").toLocalDateTime()));
            }
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list moderator queue", exception);
        }
    }

    /** Records a decision only while the flag is still PENDING, so a flag cannot be decided twice. */
    public boolean decideIfPending(long flagId, String decision, String reviewedBy) {
        String sql = "UPDATE fraud_flags SET reviewer_decision = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP WHERE flag_id = ? AND reviewer_decision = 'PENDING'";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, decision);
            statement.setString(2, reviewedBy);
            statement.setLong(3, flagId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("decide fraud flag", exception);
        }
    }
}
