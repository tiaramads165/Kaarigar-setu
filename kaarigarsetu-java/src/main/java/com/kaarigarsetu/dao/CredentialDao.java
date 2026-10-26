package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Credential;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Credentials. The signed payload holds the worker's scores, so it is stored sealed (DataCipher), tied to its own credential id; the signature, key id,
 * reference and revocation fields stay plain (they hold no score, and the revocation list and public verification are built from them). The payload is
 * opened here, so everything above the DAO, including signature verification, sees exactly the text that was signed.
 */
public class CredentialDao extends JdbcDaoSupport {
    private static final String PAYLOAD = "credentials.signed_payload";

    private final DataCipher cipher;

    public CredentialDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public Credential insert(Credential credential) {
        String sql = "INSERT INTO credentials (credential_id, worker_id, signed_payload, signature) VALUES (?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "credentials", "credential_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, credential.workerId());
                statement.setString(3, cipher.seal(PAYLOAD, id, credential.signedPayload()));
                statement.setString(4, credential.signature());
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert credential", exception);
        }
    }

    public Optional<Credential> findById(long credentialId) {
        String sql = "SELECT * FROM credentials WHERE credential_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, credentialId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find credential", exception);
        }
    }

    private Credential map(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("credential_id");
        return new Credential(
                id,
                resultSet.getLong("worker_id"),
                cipher.open(PAYLOAD, id, resultSet.getString("signed_payload")),
                resultSet.getString("signature"),
                resultSet.getTimestamp("issued_at").toLocalDateTime()
        );
    }

    public com.kaarigarsetu.model.CredentialRecord insertIssued(long workerId, String signedPayload, String signature,
                                                               java.time.LocalDateTime expiresAt, String keyId, java.util.UUID ref) {
        String sql = "INSERT INTO credentials (credential_id, worker_id, signed_payload, signature, expires_at, key_id, credential_ref) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "credentials", "credential_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, workerId);
                statement.setString(3, cipher.seal(PAYLOAD, id, signedPayload));
                statement.setString(4, signature);
                statement.setTimestamp(5, java.sql.Timestamp.valueOf(expiresAt));
                statement.setString(6, keyId);
                statement.setObject(7, ref);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw dataAccessException("issue credential", exception);
        }
        return findByRef(ref).orElseThrow();
    }

    public Optional<com.kaarigarsetu.model.CredentialRecord> findByRef(java.util.UUID ref) {
        return query("SELECT * FROM credentials WHERE credential_ref = ?", ps -> ps.setObject(1, ref)).stream().findFirst();
    }

    public java.util.List<com.kaarigarsetu.model.CredentialRecord> findByWorker(long workerId) {
        return query("SELECT * FROM credentials WHERE worker_id = ? ORDER BY issued_at DESC, credential_id DESC", ps -> ps.setLong(1, workerId));
    }

    /** Newest revocations first; feeds the signed revocation list that third parties download. */
    public java.util.List<com.kaarigarsetu.model.CredentialRecord> findRevoked(int limit) {
        return query("SELECT * FROM credentials WHERE revoked_at IS NOT NULL ORDER BY revoked_at DESC, credential_id DESC LIMIT ?", ps -> ps.setInt(1, limit));
    }

    /** Revokes one credential. Idempotent: an already revoked credential keeps its first reason. */
    public boolean revoke(java.util.UUID ref, String reason) {
        return execute("UPDATE credentials SET revoked_at = CURRENT_TIMESTAMP, revocation_reason = ? WHERE credential_ref = ? AND revoked_at IS NULL",
                ps -> { ps.setString(1, reason); ps.setObject(2, ref); });
    }

    /** A new issuance supersedes every earlier live credential of the worker, so a stale score cannot be replayed. */
    public int revokeAllLiveForWorker(long workerId, String reason) {
        String sql = "UPDATE credentials SET revoked_at = CURRENT_TIMESTAMP, revocation_reason = ? WHERE worker_id = ? AND revoked_at IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reason);
            statement.setLong(2, workerId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("supersede credentials", exception);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private boolean execute(String sql, Binder binder) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw dataAccessException("update credential", exception);
        }
    }

    private java.util.List<com.kaarigarsetu.model.CredentialRecord> query(String sql, Binder binder) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<com.kaarigarsetu.model.CredentialRecord> rows = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    java.sql.Timestamp expires = resultSet.getTimestamp("expires_at");
                    java.sql.Timestamp revoked = resultSet.getTimestamp("revoked_at");
                    long id = resultSet.getLong("credential_id");
                    rows.add(new com.kaarigarsetu.model.CredentialRecord(id,
                            resultSet.getObject("credential_ref", java.util.UUID.class), resultSet.getLong("worker_id"),
                            cipher.open(PAYLOAD, id, resultSet.getString("signed_payload")), resultSet.getString("signature"),
                            resultSet.getTimestamp("issued_at").toLocalDateTime(), expires == null ? null : expires.toLocalDateTime(),
                            revoked == null ? null : revoked.toLocalDateTime(), resultSet.getString("revocation_reason"),
                            resultSet.getString("key_id")));
                }
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("query credentials", exception);
        }
    }
}
