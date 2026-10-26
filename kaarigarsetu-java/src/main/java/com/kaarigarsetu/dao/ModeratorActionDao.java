package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.ModeratorAction;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proposals that need a second moderator. Every state change is one UPDATE whose WHERE clause carries the rule it enforces (still pending, still inside
 * its lifetime, not the proposer), so two simultaneous approvals cannot both win; the table's CHECK constraint is the last line of defence for the
 * four-eyes rule.
 */
public class ModeratorActionDao extends JdbcDaoSupport {
    public ModeratorActionDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    private static ModeratorAction map(ResultSet r) throws SQLException {
        long decidedBy = r.getLong("decided_by");
        Long decider = r.wasNull() ? null : decidedBy;
        var decidedAt = r.getTimestamp("decided_at");
        return new ModeratorAction(r.getLong("action_id"), r.getString("action_type"), r.getLong("target_id"), r.getString("verdict"), r.getLong("proposed_by"),
                r.getString("proposer"), r.getTimestamp("proposed_at").toLocalDateTime(), r.getString("status"), decider, r.getString("decider"),
                decidedAt == null ? null : decidedAt.toLocalDateTime());
    }

    /** @return the new proposal's id, or empty if a proposal of this kind for this target is already waiting */
    public Optional<Long> propose(String type, long targetId, String verdict, long proposer) {
        String sql = "INSERT INTO moderator_actions (action_type, target_id, verdict, proposed_by) VALUES (?, ?, ?, ?) RETURNING action_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setLong(2, targetId);
            statement.setString(3, verdict);
            statement.setLong(4, proposer);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Optional.of(resultSet.getLong(1));
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) return Optional.empty();   // unique violation: one open proposal per target and kind
            throw dataAccessException("propose moderator action", exception);
        }
    }

    public Optional<ModeratorAction> findById(long actionId) {
        String sql = """
            SELECT a.action_id, a.action_type, a.target_id, a.verdict, a.proposed_by, p.username AS proposer, a.proposed_at, a.status,
                   a.decided_by, d.username AS decider, a.decided_at
            FROM moderator_actions a JOIN moderators p ON p.moderator_id = a.proposed_by LEFT JOIN moderators d ON d.moderator_id = a.decided_by
            WHERE a.action_id = ?
            """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, actionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find moderator action", exception);
        }
    }

    /** Proposals still waiting and still inside their lifetime, oldest first. */
    public List<ModeratorAction> findPending(int lifetimeHours) {
        String sql = """
            SELECT a.action_id, a.action_type, a.target_id, a.verdict, a.proposed_by, p.username AS proposer, a.proposed_at, a.status,
                   a.decided_by, d.username AS decider, a.decided_at
            FROM moderator_actions a JOIN moderators p ON p.moderator_id = a.proposed_by LEFT JOIN moderators d ON d.moderator_id = a.decided_by
            WHERE a.status = 'PENDING' AND a.proposed_at >= CURRENT_TIMESTAMP - (? * INTERVAL '1 hour')
            ORDER BY a.proposed_at, a.action_id
            """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, lifetimeHours);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ModeratorAction> out = new ArrayList<>();
                while (resultSet.next()) out.add(map(resultSet));
                return out;
            }
        } catch (SQLException exception) {
            throw dataAccessException("list pending moderator actions", exception);
        }
    }

    /** Marks proposals past their lifetime as EXPIRED (nobody decided them). @return how many */
    public int expireOlderThan(int lifetimeHours) {
        String sql = "UPDATE moderator_actions SET status = 'EXPIRED', decided_at = CURRENT_TIMESTAMP "
                + "WHERE status = 'PENDING' AND proposed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 hour')";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, lifetimeHours);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("expire moderator actions", exception);
        }
    }

    /** Approval: only a different moderator, only while pending and inside the lifetime. @return false if any of that does not hold */
    public boolean approve(long actionId, long approver, int lifetimeHours) {
        String sql = "UPDATE moderator_actions SET status = 'EXECUTED', decided_by = ?, decided_at = CURRENT_TIMESTAMP "
                + "WHERE action_id = ? AND status = 'PENDING' AND proposed_by <> ? AND proposed_at >= CURRENT_TIMESTAMP - (? * INTERVAL '1 hour')";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, approver);
            statement.setLong(2, actionId);
            statement.setLong(3, approver);
            statement.setInt(4, lifetimeHours);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("approve moderator action", exception);
        }
    }

    /** A different moderator declines the proposal. */
    public boolean reject(long actionId, long moderator) {
        String sql = "UPDATE moderator_actions SET status = 'REJECTED', decided_by = ?, decided_at = CURRENT_TIMESTAMP "
                + "WHERE action_id = ? AND status = 'PENDING' AND proposed_by <> ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moderator);
            statement.setLong(2, actionId);
            statement.setLong(3, moderator);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("reject moderator action", exception);
        }
    }

    /** The proposer takes their own proposal back. */
    public boolean withdraw(long actionId, long moderator) {
        String sql = "UPDATE moderator_actions SET status = 'WITHDRAWN', decided_by = ?, decided_at = CURRENT_TIMESTAMP "
                + "WHERE action_id = ? AND status = 'PENDING' AND proposed_by = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, moderator);
            statement.setLong(2, actionId);
            statement.setLong(3, moderator);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("withdraw moderator action", exception);
        }
    }
}
