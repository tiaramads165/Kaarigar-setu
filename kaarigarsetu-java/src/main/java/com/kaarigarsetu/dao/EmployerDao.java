package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Employers. The business name is stored sealed (DataCipher), tied to its own employer id; the phone is protected as before. */
public class EmployerDao extends JdbcDaoSupport {
    static final String NAME = "employers.name";

    private final DataCipher cipher;

    public EmployerDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public Employer insert(Employer employer) {
        String sql = "INSERT INTO employers (employer_id, name, phone, verified) VALUES (?, ?, ?, ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "employers", "employer_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setString(2, cipher.seal(NAME, id, employer.name()));
                statement.setString(3, employer.phone());
                statement.setBoolean(4, employer.verified());
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert employer", exception);
        }
    }

    public Optional<Employer> findById(long employerId) {
        String sql = "SELECT * FROM employers WHERE employer_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find employer", exception);
        }
    }

    public Optional<Employer> findByPhone(String phone) {
        String sql = "SELECT * FROM employers WHERE phone = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, phone);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find employer by phone", exception);
        }
    }

    private Employer map(ResultSet resultSet) throws SQLException {
        long id = resultSet.getLong("employer_id");
        return new Employer(
                id,
                cipher.open(NAME, id, resultSet.getString("name")),
                resultSet.getString("phone"),
                resultSet.getBoolean("verified"),
                resultSet.getTimestamp("created_at").toLocalDateTime()
        );
    }

    public Employer insertProtected(Employer employer, byte[] phoneEncrypted, String consentVersion) {
        String sql = "INSERT INTO employers (employer_id, name, phone, verified, phone_enc, consent_at, consent_version) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)";
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "employers", "employer_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setString(2, cipher.seal(NAME, id, employer.name()));
                statement.setString(3, employer.phone());
                statement.setBoolean(4, employer.verified());
                statement.setBytes(5, phoneEncrypted);
                statement.setString(6, consentVersion);
                statement.executeUpdate();
            }
            return findById(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert employer", exception);
        }
    }

    public java.util.List<Employer> findDemoAccounts() {
        String sql = "SELECT * FROM employers WHERE consent_version = 'DEMO-SEED' AND erased_at IS NULL ORDER BY employer_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<Employer> rows = new java.util.ArrayList<>();
            while (resultSet.next()) rows.add(map(resultSet));
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list demo employers", exception);
        }
    }

    public boolean isDemoAccount(long employerId) {
        String sql = "SELECT 1 FROM employers WHERE employer_id = ? AND consent_version = 'DEMO-SEED' AND erased_at IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw dataAccessException("check demo employer", exception);
        }
    }

    public Optional<byte[]> findPhoneEncrypted(long employerId) {
        String sql = "SELECT phone_enc FROM employers WHERE employer_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(resultSet.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find employer phone", exception);
        }
    }

    /** An employer as a moderator sees it when deciding whether to vet it: no phone number, only what registration and use already show. */
    public record EmployerSummary(long employerId, String name, boolean verified, String verifiedBy, int approvedConfirmations, java.time.LocalDateTime registeredAt) {
    }

    /** Employers to review, those with the most approved confirmations first (their vouching already counts, so they matter most). */
    public java.util.List<EmployerSummary> listForVetting(boolean onlyUnvetted, int limit) {
        return listForVetting(onlyUnvetted, limit, 0);
    }

    /** One page of the same list: {@code offset} rows are skipped (most approved confirmations first, then oldest first, so the order is stable between pages). */
    public java.util.List<EmployerSummary> listForVetting(boolean onlyUnvetted, int limit, int offset) {
        String sql = """
                SELECT e.employer_id, e.name, e.verified, e.verified_by, e.created_at,
                       COUNT(a.attestation_id) FILTER (WHERE a.status = 'APPROVED') AS approved
                FROM employers e LEFT JOIN attestations a ON a.employer_id = e.employer_id
                WHERE e.erased_at IS NULL AND (? = FALSE OR e.verified = FALSE)
                GROUP BY e.employer_id
                ORDER BY approved DESC, e.created_at ASC, e.employer_id ASC
                LIMIT ? OFFSET ?
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, onlyUnvetted);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<EmployerSummary> rows = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new EmployerSummary(resultSet.getLong("employer_id"), cipher.open(NAME, resultSet.getLong("employer_id"), resultSet.getString("name")), resultSet.getBoolean("verified"),
                            resultSet.getString("verified_by"), resultSet.getInt("approved"), resultSet.getTimestamp("created_at").toLocalDateTime()));
                }
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("list employers for vetting", exception);
        }
    }

    /** Records that a moderator vetted the employer. Idempotent: vetting twice keeps the first moderator and time. */
    public boolean vet(long employerId, String moderator) {
        String sql = "UPDATE employers SET verified = TRUE, verified_by = COALESCE(verified_by, ?), verified_at = COALESCE(verified_at, CURRENT_TIMESTAMP) "
                + "WHERE employer_id = ? AND erased_at IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, moderator);
            statement.setLong(2, employerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("vet employer", exception);
        }
    }

    public boolean markVerified(long employerId) {
        String sql = "UPDATE employers SET verified = TRUE WHERE employer_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("verify employer", exception);
        }
    }

    /**
     * DPDP erasure of an employer's personal data. The row stays (workers keep the endorsements they earned) but name,
     * phone lookup key and encrypted phone are gone; the unique phone column is filled with an opaque value that can never be looked up.
     */
    public boolean anonymise(long employerId) {
        String sql = "UPDATE employers SET name = ?, phone = 'erased:' || subject_id::text, phone_enc = NULL, erased_at = CURRENT_TIMESTAMP "
                + "WHERE employer_id = ? AND erased_at IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cipher.seal(NAME, employerId, "Erased employer"));
            statement.setLong(2, employerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("anonymise employer", exception);
        }
    }
}
