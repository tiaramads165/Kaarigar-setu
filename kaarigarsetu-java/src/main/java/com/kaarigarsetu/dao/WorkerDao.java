package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Workers. The name, both scores, both bands and the two explanation texts are stored sealed (DataCipher), each tied to its own worker id; the phone is
 * protected as before (a keyed lookup hash and AES-GCM ciphertext). Trade, language and timestamps stay plain: they are filtered on and are not scores.
 */
public class WorkerDao extends JdbcDaoSupport {
    private static final String NAME = "workers.name";
    private static final String SKILL = "workers.skill_score";
    private static final String TRUST = "workers.trust_score";
    private static final String SKILL_BAND = "workers.skill_band";
    private static final String TRUST_BAND = "workers.trust_band";
    private static final String BREAKDOWN = "workers.skill_breakdown";
    private static final String REPORT = "workers.trust_report";
    private static final String NEAR = "workers.band_near_boundary";

    private static final java.util.Set<String> SKILL_BANDS = java.util.Set.of("PASS", "BORDERLINE", "NEEDS_REVIEW");
    private static final java.util.Set<String> TRUST_BANDS = java.util.Set.of("EXCELLENT", "GOOD", "FAIR", "BUILDING", "NO_HISTORY");

    private final DataCipher cipher;

    public WorkerDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public Worker insert(Worker worker) {
        requireBetween("skill score", worker.skillScore(), 0, 100);
        requireBetween("trust score", worker.trustScore(), 0, 100);
        String sql = """
                INSERT INTO workers (worker_id, name, phone, trade, language, skill_score, trust_score)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "workers", "worker_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setString(2, cipher.seal(NAME, id, worker.name()));
                statement.setString(3, worker.phone());
                statement.setString(4, worker.trade());
                statement.setString(5, worker.language());
                statement.setString(6, cipher.sealDecimal(SKILL, id, worker.skillScore()));
                statement.setString(7, cipher.sealDecimal(TRUST, id, worker.trustScore()));
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert worker", exception);
        }
    }

    public Optional<Worker> findById(long workerId) {
        String sql = "SELECT * FROM workers WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find worker", exception);
        }
    }

    public Optional<Worker> findByPhone(String phone) {
        String sql = "SELECT * FROM workers WHERE phone = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, phone);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find worker by phone", exception);
        }
    }

    public boolean updateScores(long workerId, java.math.BigDecimal skillScore, java.math.BigDecimal trustScore) {
        requireBetween("skill score", skillScore, 0, 100);
        requireBetween("trust score", trustScore, 0, 100);
        String sql = "UPDATE workers SET skill_score = ?, trust_score = ? WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cipher.sealDecimal(SKILL, workerId, skillScore));
            statement.setString(2, cipher.sealDecimal(TRUST, workerId, trustScore));
            statement.setLong(3, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update worker", exception);
        }
    }

    private Worker map(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("worker_id");
        return new Worker(
                id,
                cipher.open(NAME, id, resultSet.getString("name")),
                resultSet.getString("phone"),
                resultSet.getString("trade"),
                resultSet.getString("language"),
                cipher.openDecimal(SKILL, id, resultSet.getString("skill_score")),
                cipher.openDecimal(TRUST, id, resultSet.getString("trust_score")),
                resultSet.getTimestamp("created_at").toLocalDateTime()
        );
    }

    /** Phase 4/5 registration: stores the opaque phone lookup key, the AES-GCM phone ciphertext and the consent record. */
    public Worker insertProtected(Worker worker, byte[] phoneEncrypted, String consentVersion) {
        requireBetween("skill score", worker.skillScore(), 0, 100);
        requireBetween("trust score", worker.trustScore(), 0, 100);
        String sql = """
                INSERT INTO workers (worker_id, name, phone, trade, language, skill_score, trust_score, phone_enc, consent_at, consent_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """;
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "workers", "worker_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setString(2, cipher.seal(NAME, id, worker.name()));
                statement.setString(3, worker.phone());
                statement.setString(4, worker.trade());
                statement.setString(5, worker.language());
                statement.setString(6, cipher.sealDecimal(SKILL, id, worker.skillScore()));
                statement.setString(7, cipher.sealDecimal(TRUST, id, worker.trustScore()));
                statement.setBytes(8, phoneEncrypted);
                statement.setString(9, consentVersion);
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert worker", exception);
        }
    }

    public Optional<byte[]> findPhoneEncrypted(long workerId) {
        return singleBytes("SELECT phone_enc FROM workers WHERE worker_id = ?", workerId, "find worker phone");
    }

    public Optional<java.util.UUID> findSubjectId(long workerId) {
        String sql = "SELECT subject_id FROM workers WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getObject(1, java.util.UUID.class)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find worker subject", exception);
        }
    }

    /** Accounts created by the demo seeder (consent version DEMO-SEED), for the dev-only demo sign-in page. */
    public java.util.List<Worker> findDemoAccounts() {
        String sql = "SELECT * FROM workers WHERE consent_version = 'DEMO-SEED' AND erased_at IS NULL ORDER BY worker_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<Worker> rows = new java.util.ArrayList<>();
            while (resultSet.next()) rows.add(map(resultSet));
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list demo workers", exception);
        }
    }

    public boolean isDemoAccount(long workerId) {
        String sql = "SELECT 1 FROM workers WHERE worker_id = ? AND consent_version = 'DEMO-SEED' AND erased_at IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw dataAccessException("check demo worker", exception);
        }
    }

    public record StoredProfile(String band, String breakdownJson, boolean nearBoundary) {}

    /** Stores the skill score together with its band and per-area breakdown in one statement, so they can never disagree. */
    public boolean updateSkillProfile(long workerId, java.math.BigDecimal skillScore, String band, String breakdownJson, boolean nearBoundary) {
        requireBetween("skill score", skillScore, 0, 100);
        requireOneOf("skill band", band, SKILL_BANDS);
        String sql = "UPDATE workers SET skill_score = ?, skill_band = ?, skill_breakdown = ?, band_near_boundary = ? WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cipher.sealDecimal(SKILL, workerId, skillScore));
            statement.setString(2, cipher.seal(SKILL_BAND, workerId, band));
            statement.setString(3, cipher.seal(BREAKDOWN, workerId, breakdownJson));
            statement.setString(4, cipher.sealBool(NEAR, workerId, nearBoundary));
            statement.setLong(5, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update skill profile", exception);
        }
    }

    public Optional<StoredProfile> findSkillProfile(long workerId) {
        String sql = "SELECT skill_band, skill_breakdown, band_near_boundary FROM workers WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                Boolean near = cipher.openBool(NEAR, workerId, resultSet.getString(3));
                return Optional.of(new StoredProfile(cipher.open(SKILL_BAND, workerId, resultSet.getString(1)), cipher.open(BREAKDOWN, workerId, resultSet.getString(2)),
                        near != null && near));
            }
        } catch (SQLException exception) {
            throw dataAccessException("find skill profile", exception);
        }
    }

    public boolean updateSkillScore(long workerId, java.math.BigDecimal skillScore) {
        requireBetween("skill score", skillScore, 0, 100);
        return update("UPDATE workers SET skill_score = ? WHERE worker_id = ?", cipher.sealDecimal(SKILL, workerId, skillScore), workerId, "update skill score");
    }

    /** Stores the trust score with its band and explanation in one statement so they cannot disagree. */
    public boolean updateTrustProfile(long workerId, java.math.BigDecimal trustScore, String band, String reportJson) {
        requireBetween("trust score", trustScore, 0, 100);
        requireOneOf("trust band", band, TRUST_BANDS);
        String sql = "UPDATE workers SET trust_score = ?, trust_band = ?, trust_report = ? WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cipher.sealDecimal(TRUST, workerId, trustScore));
            statement.setString(2, cipher.seal(TRUST_BAND, workerId, band));
            statement.setString(3, cipher.seal(REPORT, workerId, reportJson));
            statement.setLong(4, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update trust profile", exception);
        }
    }

    public record StoredTrust(String band, String reportJson) {}

    public Optional<StoredTrust> findTrustProfile(long workerId) {
        String sql = "SELECT trust_band, trust_report FROM workers WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new StoredTrust(cipher.open(TRUST_BAND, workerId, resultSet.getString(1)), cipher.open(REPORT, workerId, resultSet.getString(2))))
                        : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find trust profile", exception);
        }
    }

    public boolean updateTrustScore(long workerId, java.math.BigDecimal trustScore) {
        requireBetween("trust score", trustScore, 0, 100);
        return update("UPDATE workers SET trust_score = ? WHERE worker_id = ?", cipher.sealDecimal(TRUST, workerId, trustScore), workerId, "update trust score");
    }

    public java.util.List<Long> findAllActiveIds() {
        String sql = "SELECT worker_id FROM workers WHERE erased_at IS NULL ORDER BY worker_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            while (resultSet.next()) ids.add(resultSet.getLong(1));
            return ids;
        } catch (SQLException exception) {
            throw dataAccessException("list workers", exception);
        }
    }

    private boolean update(String sql, String sealedValue, long id, String action) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sealedValue);
            statement.setLong(2, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException(action, exception);
        }
    }

    private Optional<byte[]> singleBytes(String sql, long id, String action) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(resultSet.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException(action, exception);
        }
    }

    /** DPDP erasure: removes the worker row; assessments, attestations (and their flags) and credentials go with it by cascade. */
    public boolean deleteById(long workerId) {
        String sql = "DELETE FROM workers WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("delete worker", exception);
        }
    }
}
