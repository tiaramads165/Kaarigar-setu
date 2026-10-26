package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.TrustedDevice;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Trusted devices (table trusted_devices): only a keyed hash of the browser's random token is stored. Every statement is a PreparedStatement. */
public class TrustedDeviceDao extends JdbcDaoSupport {
    public TrustedDeviceDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    private static TrustedDevice map(ResultSet r) throws SQLException {
        return new TrustedDevice(r.getLong("device_id"), r.getString("role"), r.getLong("account_id"), r.getTimestamp("created_at").toLocalDateTime(),
                r.getTimestamp("last_used_at").toLocalDateTime());
    }

    /** The device with this token hash, only if it belongs to THIS account and has been used within {@code maxIdleDays}. */
    public Optional<TrustedDevice> find(String role, long accountId, String tokenHash, int maxIdleDays) {
        String sql = "SELECT device_id, role, account_id, created_at, last_used_at FROM trusted_devices "
                + "WHERE token_hash = ? AND role = ? AND account_id = ? AND last_used_at >= CURRENT_TIMESTAMP - (? * INTERVAL '1 day')";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            statement.setString(2, role);
            statement.setLong(3, accountId);
            statement.setInt(4, maxIdleDays);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find trusted device", exception);
        }
    }

    /** Remembers a new device and drops the least recently used ones beyond {@code keepAtMost} for the account. */
    public TrustedDevice insert(String role, long accountId, String tokenHash, int keepAtMost) {
        String insert = "INSERT INTO trusted_devices (role, account_id, token_hash) VALUES (?, ?, ?) RETURNING device_id, role, account_id, created_at, last_used_at";
        try (var connection = connectionProvider.getConnection()) {
            TrustedDevice created;
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, role);
                statement.setLong(2, accountId);
                statement.setString(3, tokenHash);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    created = map(resultSet);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM trusted_devices WHERE role = ? AND account_id = ? AND device_id NOT IN "
                    + "(SELECT device_id FROM trusted_devices WHERE role = ? AND account_id = ? ORDER BY last_used_at DESC, device_id DESC LIMIT ?)")) {
                statement.setString(1, role);
                statement.setLong(2, accountId);
                statement.setString(3, role);
                statement.setLong(4, accountId);
                statement.setInt(5, keepAtMost);
                statement.executeUpdate();
            }
            return created;
        } catch (SQLException exception) {
            throw dataAccessException("remember trusted device", exception);
        }
    }

    /** Marks the device as used now (at most once an hour, so a busy account does not write on every code request). */
    public void touch(long deviceId) {
        String sql = "UPDATE trusted_devices SET last_used_at = CURRENT_TIMESTAMP WHERE device_id = ? AND last_used_at < CURRENT_TIMESTAMP - INTERVAL '1 hour'";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deviceId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("touch trusted device", exception);
        }
    }

    /** DPDP erasure: the account's devices go with it. */
    public int deleteForAccount(String role, long accountId) {
        String sql = "DELETE FROM trusted_devices WHERE role = ? AND account_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role);
            statement.setLong(2, accountId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("delete trusted devices", exception);
        }
    }

    /** Housekeeping: devices unused for longer than the trust period. */
    public int deleteIdleFor(int days) {
        String sql = "DELETE FROM trusted_devices WHERE last_used_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, days);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("expire trusted devices", exception);
        }
    }
}
