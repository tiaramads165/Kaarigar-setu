package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.security.OtpChallengeStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** PostgreSQL-backed OTP challenges (table otp_challenges). Every statement is a PreparedStatement. */
public class OtpChallengeDao extends JdbcDaoSupport implements OtpChallengeStore {
    public OtpChallengeDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    @Override
    public long save(String key, String hash, Instant expiresAt, String ipHash) {
        String retire = "UPDATE otp_challenges SET consumed_at = CURRENT_TIMESTAMP WHERE challenge_key = ? AND consumed_at IS NULL";
        String insert = "INSERT INTO otp_challenges (challenge_key, otp_hash, expires_at, ip_hash) VALUES (?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(retire)) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, key);
                statement.setString(2, hash);
                statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
                statement.setString(4, ipHash);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong("challenge_id");
                }
            }
        } catch (SQLException exception) {
            throw dataAccessException("save otp challenge", exception);
        }
    }

    @Override
    public Optional<Challenge> findOpen(String key) {
        // FOR UPDATE: inside a transaction this row-locks the challenge so concurrent guesses cannot skip the counter.
        String sql = """
                SELECT challenge_id, otp_hash, expires_at, attempts FROM otp_challenges
                WHERE challenge_key = ? AND consumed_at IS NULL
                ORDER BY created_at DESC, challenge_id DESC LIMIT 1 FOR UPDATE
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                Instant expires = resultSet.getTimestamp("expires_at").toLocalDateTime().toInstant(ZoneOffset.UTC);
                return Optional.of(new Challenge(resultSet.getLong("challenge_id"), resultSet.getString("otp_hash"),
                        expires, resultSet.getInt("attempts")));
            }
        } catch (SQLException exception) {
            throw dataAccessException("find otp challenge", exception);
        }
    }

    @Override
    public void recordFailedAttempt(long id) {
        run("UPDATE otp_challenges SET attempts = attempts + 1 WHERE challenge_id = ?", id, "record otp attempt");
    }

    @Override
    public void consume(long id) {
        run("UPDATE otp_challenges SET consumed_at = CURRENT_TIMESTAMP WHERE challenge_id = ?", id, "consume otp challenge");
    }

    private void run(String sql, long id, String action) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException(action, exception);
        }
    }

    /**
     * DPDP erasure: login challenges are keyed by a phone-derived value (plus a trusted-device or binding suffix), so every one that starts with the
     * account's key goes. starts_with, not LIKE: the key may contain underscores, which LIKE treats as wildcards.
     */
    public int deleteByKeyPrefix(String prefix) {
        String sql = "DELETE FROM otp_challenges WHERE starts_with(challenge_key, ?)";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, prefix);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("delete otp challenges", exception);
        }
    }
}
