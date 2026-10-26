package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.TrustEdge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TrustEdgeDao extends JdbcDaoSupport {
    public TrustEdgeDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    public TrustEdge insert(TrustEdge edge) {
        String sql = "INSERT INTO trust_edges (source_id, target_id, weight, verified) VALUES (?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, edge.sourceId());
            statement.setLong(2, edge.targetId());
            statement.setBigDecimal(3, edge.weight());
            statement.setBoolean(4, edge.verified());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException exception) {
            throw dataAccessException("insert trust edge", exception);
        }
    }

    public Optional<TrustEdge> findById(long edgeId) {
        String sql = "SELECT * FROM trust_edges WHERE edge_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, edgeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find trust edge", exception);
        }
    }

    public List<TrustEdge> findBySourceId(long sourceId) {
        String sql = "SELECT * FROM trust_edges WHERE source_id = ? ORDER BY edge_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sourceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TrustEdge> edges = new ArrayList<>();
                while (resultSet.next()) {
                    edges.add(map(resultSet));
                }
                return edges;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find trust edges", exception);
        }
    }

    public List<TrustEdge> findBySourceIdText(String sourceIdText) {
        String sql = "SELECT * FROM trust_edges WHERE source_id::text = ? ORDER BY edge_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceIdText);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TrustEdge> edges = new ArrayList<>();
                while (resultSet.next()) {
                    edges.add(map(resultSet));
                }
                return edges;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find trust edges by text input", exception);
        }
    }

    private TrustEdge map(ResultSet resultSet) throws SQLException {
        return new TrustEdge(
                resultSet.getLong("edge_id"),
                resultSet.getLong("source_id"),
                resultSet.getLong("target_id"),
                resultSet.getBigDecimal("weight"),
                resultSet.getBoolean("verified")
        );
    }

    /** An employer -> worker edge created by an approved attestation. One edge per attestation. */
    public void insertAttestationEdge(long employerId, long workerId, java.math.BigDecimal weight, long attestationId) {
        String sql = """
                INSERT INTO trust_edges (source_id, target_id, weight, verified, source_kind, attestation_id)
                VALUES (?, ?, ?, TRUE, 'EMPLOYER', ?)
                ON CONFLICT (attestation_id) WHERE attestation_id IS NOT NULL DO NOTHING
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            statement.setLong(2, workerId);
            statement.setBigDecimal(3, weight);
            statement.setLong(4, attestationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("insert attestation trust edge", exception);
        }
    }

    /** Every verified edge with the anomaly score of the attestation behind it, for the trust recomputation. */
    public java.util.List<com.kaarigarsetu.model.TrustEdgeRow> findAllVerified() {
        String sql = """
                SELECT t.edge_id, t.source_kind, t.source_id, t.target_id, t.weight, t.verified, t.attestation_id, t.created_at,
                       COALESCE(a.anomaly_score, 0) AS anomaly_score
                FROM trust_edges t LEFT JOIN attestations a ON a.attestation_id = t.attestation_id
                WHERE t.verified = TRUE ORDER BY t.edge_id
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<com.kaarigarsetu.model.TrustEdgeRow> rows = new java.util.ArrayList<>();
            while (resultSet.next()) {
                long attestation = resultSet.getLong("attestation_id");
                rows.add(new com.kaarigarsetu.model.TrustEdgeRow(resultSet.getLong("edge_id"), resultSet.getString("source_kind"),
                        resultSet.getLong("source_id"), resultSet.getLong("target_id"), resultSet.getBigDecimal("weight"),
                        resultSet.getBoolean("verified"), resultSet.wasNull() ? null : attestation,
                        resultSet.getTimestamp("created_at").toLocalDateTime(), resultSet.getBigDecimal("anomaly_score")));
            }
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list trust edges", exception);
        }
    }

    /** DPDP erasure: every edge into or out of this worker. (The source foreign key was dropped in migration 002, so nothing cascades.) */
    public int deleteForWorker(long workerId) {
        String sql = "DELETE FROM trust_edges WHERE target_id = ? OR (source_kind = 'WORKER' AND source_id = ?)";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            statement.setLong(2, workerId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("delete worker trust edges", exception);
        }
    }
}
