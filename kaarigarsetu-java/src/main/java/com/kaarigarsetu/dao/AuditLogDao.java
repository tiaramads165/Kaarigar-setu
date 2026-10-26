package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * The audit table. The DETAILS of an event are stored sealed (DataCipher, audit domain), tied to the event's type and actor; the event type, actor and
 * time stay plain so the Security tab can count and alert, and the table's own hash chain (computed by the database over what is stored) keeps working
 * over the ciphertext without any key. Rows written before sealing was switched on are plain and, the table being append-only, stay that way: they are
 * read as they are. Both kinds are handled by {@code open}, which is deliberately not strict for this table.
 */
public class AuditLogDao extends JdbcDaoSupport {
    private static final String DETAILS = "audit_log.details";

    private final DataCipher cipher;

    public AuditLogDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    private static String context(String eventType, String actor) {
        return eventType + "|" + actor;
    }

    public AuditLog insert(AuditLog auditLog) {
        String sql = "INSERT INTO audit_log (event_type, actor, details) VALUES (?, ?, ?)";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, auditLog.eventType());
            statement.setString(2, auditLog.actor());
            statement.setString(3, cipher.seal(DETAILS, context(auditLog.eventType(), auditLog.actor()), auditLog.details()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException exception) {
            throw dataAccessException("insert audit log", exception);
        }
    }

    public Optional<AuditLog> findById(long logId) {
        String sql = "SELECT * FROM audit_log WHERE log_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, logId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find audit log", exception);
        }
    }

    /** Newest first (by chain position, which cannot be re-ordered). */
    public java.util.List<AuditLog> findByType(String eventType, int limit) {
        String sql = "SELECT * FROM audit_log WHERE event_type = ? ORDER BY chain_seq DESC LIMIT ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<AuditLog> rows = new java.util.ArrayList<>();
                while (resultSet.next()) rows.add(map(resultSet));
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find audit log by type", exception);
        }
    }

    public java.util.Map<String, Long> countByType(int minutes, java.util.Collection<String> eventTypes) {
        String sql = "SELECT event_type, count(*) AS n FROM audit_log WHERE \"timestamp\" >= CURRENT_TIMESTAMP - (? * INTERVAL '1 minute') AND event_type = ANY (?) GROUP BY event_type";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, minutes);
            statement.setArray(2, connection.createArrayOf("varchar", eventTypes.toArray()));
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
                while (resultSet.next()) counts.put(resultSet.getString("event_type"), resultSet.getLong("n"));
                return counts;
            }
        } catch (SQLException exception) {
            throw dataAccessException("count audit events", exception);
        }
    }

    public java.util.Optional<Long> newestAgeMinutes(String eventType) {
        String sql = "SELECT floor(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - max(\"timestamp\"))) / 60) AS minutes FROM audit_log WHERE event_type = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return java.util.Optional.empty();
                long minutes = resultSet.getLong("minutes");
                return resultSet.wasNull() ? java.util.Optional.empty() : java.util.Optional.of(minutes);
            }
        } catch (SQLException exception) {
            throw dataAccessException("age of newest audit event", exception);
        }
    }

    public java.util.List<AuditLog> recent(java.util.Collection<String> eventTypes, int limit) {
        String sql = "SELECT * FROM audit_log WHERE event_type = ANY (?) ORDER BY chain_seq DESC LIMIT ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("varchar", eventTypes.toArray()));
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<AuditLog> rows = new java.util.ArrayList<>();
                while (resultSet.next()) rows.add(map(resultSet));
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("list recent audit events", exception);
        }
    }

    /** Runs the database's own hash-chain check (verify_audit_chain(), migration 002). */
    public com.kaarigarsetu.security.AuditLogRepository.ChainStatus verifyChain() {
        String sql = "SELECT valid, first_bad_seq, checked FROM verify_audit_chain()";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new com.kaarigarsetu.security.AuditLogRepository.ChainStatus(resultSet.getBoolean("valid"), resultSet.getLong("first_bad_seq"), resultSet.getLong("checked"));
        } catch (SQLException exception) {
            throw dataAccessException("verify audit chain", exception);
        }
    }

    public Optional<AuditLog> findByActor(String actor) {
        String sql = "SELECT * FROM audit_log WHERE actor = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actor);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find audit log by actor", exception);
        }
    }

    private AuditLog map(ResultSet resultSet) throws SQLException {
        String eventType = resultSet.getString("event_type");
        String actor = resultSet.getString("actor");
        return new AuditLog(
                resultSet.getLong("log_id"),
                eventType,
                actor,
                cipher.open(DETAILS, context(eventType, actor), resultSet.getString("details")),
                resultSet.getTimestamp("timestamp").toLocalDateTime()
        );
    }
}
