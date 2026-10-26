package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Moderator;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Moderator accounts. The web application only READS them (setup_roles.sql gives its database role SELECT and nothing else on this table); {@link #insert},
 * {@link #setActive} and {@link #setPassword} are used by the operator's tool (admin.ModeratorAdminCli) running with database-owner rights.
 */
public class ModeratorDao extends JdbcDaoSupport {
    public ModeratorDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    private static Moderator map(ResultSet r) throws SQLException {
        return new Moderator(r.getLong("moderator_id"), r.getString("username"), r.getString("display_name"), r.getString("password_hash"), r.getBytes("phone_encrypted"),
                r.getBoolean("active"), r.getTimestamp("created_at").toLocalDateTime(), r.getString("created_by"));
    }

    public Optional<Moderator> findByUsername(String username) {
        String sql = "SELECT moderator_id, username, display_name, password_hash, phone_encrypted, active, created_at, created_by FROM moderators WHERE username = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find moderator by name", exception);
        }
    }

    public Optional<Moderator> findById(long moderatorId) {
        String sql = "SELECT moderator_id, username, display_name, password_hash, phone_encrypted, active, created_at, created_by FROM moderators WHERE moderator_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moderatorId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find moderator", exception);
        }
    }

    public int countActive() {
        String sql = "SELECT count(*) FROM moderators WHERE active";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException exception) {
            throw dataAccessException("count moderators", exception);
        }
    }

    public List<Moderator> findAll() {
        String sql = "SELECT moderator_id, username, display_name, password_hash, phone_encrypted, active, created_at, created_by FROM moderators ORDER BY moderator_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Moderator> out = new ArrayList<>();
            while (resultSet.next()) out.add(map(resultSet));
            return out;
        } catch (SQLException exception) {
            throw dataAccessException("list moderators", exception);
        }
    }

    // ------------------------------------------------------------------ operator-only writes

    public Moderator insert(String username, String displayName, String passwordHash, byte[] phoneEncrypted, String createdBy) {
        String sql = "INSERT INTO moderators (username, display_name, password_hash, phone_encrypted, created_by) VALUES (?, ?, ?, ?, ?) "
                + "RETURNING moderator_id, username, display_name, password_hash, phone_encrypted, active, created_at, created_by";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, displayName);
            statement.setString(3, passwordHash);
            statement.setBytes(4, phoneEncrypted);
            statement.setString(5, createdBy);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return map(resultSet);
            }
        } catch (SQLException exception) {
            throw dataAccessException("create moderator", exception);
        }
    }

    /** @return false if there is no such moderator */
    public boolean setActive(String username, boolean active) {
        String sql = "UPDATE moderators SET active = ?, disabled_at = CASE WHEN ? THEN NULL ELSE COALESCE(disabled_at, CURRENT_TIMESTAMP) END WHERE username = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, active);
            statement.setBoolean(2, active);
            statement.setString(3, username);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("change moderator status", exception);
        }
    }

    public boolean setPassword(String username, String passwordHash) {
        String sql = "UPDATE moderators SET password_hash = ? WHERE username = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passwordHash);
            statement.setString(2, username);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("change moderator password", exception);
        }
    }
}
