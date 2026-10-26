package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

abstract class JdbcDaoSupport {
    protected final ConnectionProvider connectionProvider;

    protected JdbcDaoSupport(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * Reserves the next id of a table before the row is inserted, so a sealed value can be tied to its own row (the id is part of what the encryption
     * authenticates) in the same statement that stores it. The id comes from the table's own sequence, exactly as the default would have.
     */
    protected static long nextId(Connection connection, String table, String idColumn) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT nextval(pg_get_serial_sequence(?, ?))")) {
            statement.setString(1, table);
            statement.setString(2, idColumn);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // The database used to refuse out-of-range scores, ratings and unknown bands with CHECK constraints. Those values are now stored as ciphertext, which a
    // constraint cannot look inside (migration 010), so the same rules are applied here, before anything is sealed and written.

    protected static void requireBetween(String what, java.math.BigDecimal value, int min, int max) {
        if (value != null && (value.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || value.compareTo(java.math.BigDecimal.valueOf(max)) > 0)) {
            throw new DataAccessException("Rejected " + what + ": it must be between " + min + " and " + max, null);
        }
    }

    protected static void requireBetween(String what, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) throw new DataAccessException("Rejected " + what + ": it must be between " + min + " and " + max, null);
    }

    protected static void requireOneOf(String what, String value, java.util.Set<String> allowed) {
        if (value != null && !allowed.contains(value)) throw new DataAccessException("Rejected " + what + ": not one of " + allowed, null);
    }

    protected DataAccessException dataAccessException(String action, SQLException exception) {
        return new DataAccessException("Failed to " + action, exception);
    }

    protected Optional<LocalDateTime> optionalDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toLocalDateTime());
    }
}
