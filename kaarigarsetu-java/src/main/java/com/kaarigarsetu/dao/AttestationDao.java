package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.model.Attestation;
import com.kaarigarsetu.security.DataCipher;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Attestations (employers' confirmations of a worker's job). What the employer wrote about the worker (job, dates, the three ratings, would-hire-again) and
 * what the worker said back (a dispute reason) are stored sealed (DataCipher), each tied to its own attestation id. Status, the two ids, timestamps, the
 * anomaly score and the dispute decision stay plain: the workflow, the fraud model and the "counts" view filter on them.
 */
public class AttestationDao extends JdbcDaoSupport {
    private static final String JOB = "attestations.job_role";
    private static final String DURATION = "attestations.duration_days";
    private static final String HIRE = "attestations.would_hire_again";
    private static final String WORK_DATE = "attestations.work_date";
    private static final String QUALITY = "attestations.rating_quality";
    private static final String PUNCTUALITY = "attestations.rating_punctuality";
    private static final String CONDUCT = "attestations.rating_conduct";
    private static final String DISPUTE = "attestations.dispute_reason";

    private final DataCipher cipher;

    public AttestationDao(ConnectionProvider connectionProvider, DataCipher cipher) {
        super(connectionProvider);
        this.cipher = cipher;
    }

    public Attestation insert(Attestation attestation) {
        String sql = """
                INSERT INTO attestations (worker_id, employer_id, otp_hash, status, anomaly_score)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, attestation.workerId());
            statement.setLong(2, attestation.employerId());
            statement.setString(3, attestation.otpHash());
            statement.setString(4, attestation.status());
            statement.setBigDecimal(5, attestation.anomalyScore());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException exception) {
            throw dataAccessException("insert attestation", exception);
        }
    }

    public Optional<Attestation> findById(long attestationId) {
        String sql = "SELECT * FROM attestations WHERE attestation_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attestationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find attestation", exception);
        }
    }

    public Optional<Attestation> findByOtpHash(String otpHash) {
        String sql = "SELECT * FROM attestations WHERE otp_hash = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, otpHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find attestation by hash", exception);
        }
    }

    public boolean updateStatus(long attestationId, String status) {
        String sql = "UPDATE attestations SET status = ? WHERE attestation_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, attestationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update attestation status", exception);
        }
    }

    private Attestation map(ResultSet resultSet) throws SQLException {
        return new Attestation(
                resultSet.getLong("attestation_id"),
                resultSet.getLong("worker_id"),
                resultSet.getLong("employer_id"),
                resultSet.getString("otp_hash"),
                resultSet.getString("status"),
                resultSet.getBigDecimal("anomaly_score"),
                resultSet.getTimestamp("created_at").toLocalDateTime()
        );
    }

    public com.kaarigarsetu.model.AttestationDetail insertWorkflow(long workerId, long employerId, String otpHash, String jobRole,
                                                                  Integer durationDays, Boolean wouldHireAgain,
                                                                  java.time.LocalDate workDate, String ipHash, String deviceHash) {
        return insertWorkflow(workerId, employerId, otpHash, jobRole, durationDays, wouldHireAgain, workDate, ipHash, deviceHash, null, null, null);
    }

    public com.kaarigarsetu.model.AttestationDetail insertWorkflow(long workerId, long employerId, String otpHash, String jobRole,
                                                                  Integer durationDays, Boolean wouldHireAgain,
                                                                  java.time.LocalDate workDate, String ipHash, String deviceHash,
                                                                  Integer ratingQuality, Integer ratingPunctuality, Integer ratingConduct) {
        requireBetween("quality rating", ratingQuality, 1, 5);
        requireBetween("punctuality rating", ratingPunctuality, 1, 5);
        requireBetween("conduct rating", ratingConduct, 1, 5);
        requireBetween("duration in days", durationDays, 0, 3650);
        String sql = """
                INSERT INTO attestations (attestation_id, worker_id, employer_id, otp_hash, status, job_role, duration_days, would_hire_again,
                                          work_date, source_ip_hash, device_hash, rating_quality, rating_punctuality, rating_conduct)
                VALUES (?, ?, ?, ?, 'OTP_PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = connectionProvider.getConnection()) {
            long id = nextId(connection, "attestations", "attestation_id");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.setLong(2, workerId);
                statement.setLong(3, employerId);
                statement.setString(4, otpHash);
                statement.setString(5, cipher.seal(JOB, id, jobRole));
                statement.setString(6, cipher.sealInt(DURATION, id, durationDays));
                statement.setString(7, cipher.sealBool(HIRE, id, wouldHireAgain));
                statement.setString(8, cipher.sealDate(WORK_DATE, id, workDate));
                statement.setString(9, ipHash);
                statement.setString(10, deviceHash);
                statement.setString(11, cipher.sealInt(QUALITY, id, ratingQuality));
                statement.setString(12, cipher.sealInt(PUNCTUALITY, id, ratingPunctuality));
                statement.setString(13, cipher.sealInt(CONDUCT, id, ratingConduct));
                statement.executeUpdate();
            }
            return findDetail(id).orElseThrow();
        } catch (SQLException exception) {
            throw dataAccessException("insert attestation", exception);
        }
    }

    public Optional<com.kaarigarsetu.model.AttestationDetail> findDetail(long attestationId) {
        return queryDetails("SELECT * FROM attestations WHERE attestation_id = ?", attestationId).stream().findFirst();
    }

    public java.util.List<com.kaarigarsetu.model.AttestationDetail> findByWorker(long workerId) {
        return queryDetails("SELECT * FROM attestations WHERE worker_id = ? ORDER BY created_at DESC, attestation_id DESC", workerId);
    }

    public java.util.List<com.kaarigarsetu.model.AttestationDetail> findByEmployer(long employerId) {
        return queryDetails("SELECT * FROM attestations WHERE employer_id = ? ORDER BY created_at DESC, attestation_id DESC", employerId);
    }

    /** True when a live (not rejected/expired) attestation already links this employer and worker. */
    public boolean existsLive(long workerId, long employerId) {
        String sql = "SELECT 1 FROM attestations WHERE worker_id = ? AND employer_id = ? AND status NOT IN ('REJECTED', 'EXPIRED') LIMIT 1";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            statement.setLong(2, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw dataAccessException("check duplicate attestation", exception);
        }
    }

    /** Moves an attestation out of OTP_PENDING exactly once. @return false if it was already decided. */
    public boolean decide(long attestationId, String newStatus, java.math.BigDecimal anomalyScore) {
        String sql = "UPDATE attestations SET status = ?, anomaly_score = ?, decided_at = CURRENT_TIMESTAMP WHERE attestation_id = ? AND status = 'OTP_PENDING'";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newStatus);
            statement.setBigDecimal(2, anomalyScore);
            statement.setLong(3, attestationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("decide attestation", exception);
        }
    }

    /** Moderator decision on a flagged attestation (PENDING_REVIEW -> APPROVED | REJECTED). */
    public boolean moderate(long attestationId, String newStatus) {
        String sql = "UPDATE attestations SET status = ?, decided_at = CURRENT_TIMESTAMP WHERE attestation_id = ? AND status = 'PENDING_REVIEW'";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newStatus);
            statement.setLong(2, attestationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("moderate attestation", exception);
        }
    }

    public int countFromIpSince(String ipHash, java.time.LocalDateTime since) {
        return count("SELECT count(*) FROM attestations WHERE source_ip_hash = ? AND created_at >= ?", ipHash, since, "count attestations by ip");
    }

    public int countFromDeviceSince(String deviceHash, java.time.LocalDateTime since) {
        return count("SELECT count(*) FROM attestations WHERE device_hash = ? AND created_at >= ?", deviceHash, since, "count attestations by device");
    }

    /** Minutes since this employer's previous attestation before the given one, or empty if it is their first. */
    public Optional<Double> minutesSincePreviousByEmployer(long employerId, long beforeAttestationId) {
        String sql = """
                SELECT EXTRACT(EPOCH FROM (cur.created_at - prev.created_at)) / 60.0
                FROM attestations cur JOIN attestations prev ON prev.employer_id = cur.employer_id AND prev.attestation_id < cur.attestation_id
                WHERE cur.attestation_id = ? AND cur.employer_id = ?
                ORDER BY prev.attestation_id DESC LIMIT 1
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, beforeAttestationId);
            statement.setLong(2, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getDouble(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find previous attestation gap", exception);
        }
    }

    /**
     * Share of this employer's attestations that are mutual: the attested worker is the same person as an employer
     * (same phone key) who in turn attested this employer (also a worker). A vouching ring scores close to 1.
     */
    public double reciprocityRatio(long employerId) {
        String sql = """
                SELECT COALESCE(AVG(CASE WHEN EXISTS (
                         SELECT 1 FROM employers e2 JOIN workers w2 ON w2.phone = e2.phone
                         JOIN attestations a2 ON a2.employer_id = e2.employer_id AND a2.status IN ('APPROVED', 'PENDING_REVIEW')
                         WHERE w2.worker_id = a.worker_id
                           AND a2.worker_id IN (SELECT w3.worker_id FROM workers w3 JOIN employers e3 ON e3.phone = w3.phone WHERE e3.employer_id = a.employer_id)
                       ) THEN 1.0 ELSE 0.0 END), 0.0)
                FROM attestations a WHERE a.employer_id = ?
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Math.min(1.0, Math.max(0.0, resultSet.getDouble(1)));
            }
        } catch (SQLException exception) {
            throw dataAccessException("compute reciprocity", exception);
        }
    }

    public int countApprovedForWorker(long workerId) {
        String sql = "SELECT count(*) FROM counted_attestations WHERE worker_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException exception) {
            throw dataAccessException("count approved attestations", exception);
        }
    }

    private int count(String sql, String key, java.time.LocalDateTime since, String action) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setTimestamp(2, java.sql.Timestamp.valueOf(since));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException exception) {
            throw dataAccessException(action, exception);
        }
    }

    private java.util.List<com.kaarigarsetu.model.AttestationDetail> queryDetails(String sql, long id) {
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<com.kaarigarsetu.model.AttestationDetail> rows = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    long rowId = resultSet.getLong("attestation_id");
                    java.sql.Timestamp decided = resultSet.getTimestamp("decided_at");
                    rows.add(new com.kaarigarsetu.model.AttestationDetail(rowId, resultSet.getLong("worker_id"),
                            resultSet.getLong("employer_id"), resultSet.getString("status"), resultSet.getBigDecimal("anomaly_score"),
                            cipher.open(JOB, rowId, resultSet.getString("job_role")), cipher.openInt(DURATION, rowId, resultSet.getString("duration_days")),
                            cipher.openBool(HIRE, rowId, resultSet.getString("would_hire_again")), cipher.openDate(WORK_DATE, rowId, resultSet.getString("work_date")),
                            resultSet.getTimestamp("created_at").toLocalDateTime(), decided == null ? null : decided.toLocalDateTime(),
                            cipher.openInt(QUALITY, rowId, resultSet.getString("rating_quality")), cipher.openInt(PUNCTUALITY, rowId, resultSet.getString("rating_punctuality")),
                            cipher.openInt(CONDUCT, rowId, resultSet.getString("rating_conduct"))));
                }
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("query attestations", exception);
        }
    }

    // ------------------------------------------------------------------------------------------------------ the worker's say (migration 006)

    private com.kaarigarsetu.model.WorkerConfirmation mapConfirmation(ResultSet r) throws SQLException {
        long id = r.getLong("attestation_id");
        long employerId = r.getLong("employer_id");
        java.sql.Timestamp decided = r.getTimestamp("decided_at"), responded = r.getTimestamp("responded_at");
        return new com.kaarigarsetu.model.WorkerConfirmation(id, employerId, cipher.open(EmployerDao.NAME, employerId, r.getString("employer_name")),
                cipher.open(JOB, id, r.getString("job_role")), cipher.openDate(WORK_DATE, id, r.getString("work_date")),
                cipher.openInt(QUALITY, id, r.getString("rating_quality")), cipher.openInt(PUNCTUALITY, id, r.getString("rating_punctuality")),
                cipher.openInt(CONDUCT, id, r.getString("rating_conduct")), cipher.openBool(HIRE, id, r.getString("would_hire_again")),
                decided == null ? null : decided.toLocalDateTime(), r.getString("worker_response"), responded == null ? null : responded.toLocalDateTime(),
                cipher.open(DISPUTE, id, r.getString("dispute_reason")), r.getString("dispute_decision"));
    }

    /** Approved confirmations about this worker, newest first, with what the worker did about each. */
    public java.util.List<com.kaarigarsetu.model.WorkerConfirmation> findConfirmationsForWorker(long workerId) {
        String sql = """
            SELECT a.attestation_id, a.employer_id, e.name AS employer_name, a.job_role, a.work_date, a.rating_quality, a.rating_punctuality, a.rating_conduct,
                   a.would_hire_again, a.decided_at, a.worker_response, a.responded_at, a.dispute_reason, a.dispute_decision, a.worker_id
            FROM attestations a JOIN employers e ON e.employer_id = a.employer_id
            WHERE a.worker_id = ? AND a.status = 'APPROVED' ORDER BY COALESCE(a.decided_at, a.created_at) DESC, a.attestation_id DESC
            """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<com.kaarigarsetu.model.WorkerConfirmation> rows = new java.util.ArrayList<>();
                while (resultSet.next()) rows.add(mapConfirmation(resultSet));
                return rows;
            }
        } catch (SQLException exception) {
            throw dataAccessException("list confirmations for worker", exception);
        }
    }

    /** One approved confirmation, only if it is about this worker (anything else is "not found", never "not yours"). */
    public Optional<com.kaarigarsetu.model.WorkerConfirmation> findConfirmationForWorker(long workerId, long attestationId) {
        return findConfirmationsForWorker(workerId).stream().filter(c -> c.attestationId() == attestationId).findFirst();
    }

    /** Number of this worker's disputes a moderator has not ruled on yet. */
    public int countOpenDisputes(long workerId) {
        String sql = "SELECT count(*) FROM attestations WHERE worker_id = ? AND worker_response = 'DISPUTED' AND dispute_decision IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException exception) {
            throw dataAccessException("count open disputes", exception);
        }
    }

    /** Records that the worker accepted. Exactly once: false if it was not theirs, not approved, or already answered. */
    public boolean acceptByWorker(long attestationId, long workerId) {
        String sql = "UPDATE attestations SET worker_response = 'ACCEPTED', responded_at = CURRENT_TIMESTAMP "
                + "WHERE attestation_id = ? AND worker_id = ? AND status = 'APPROVED' AND worker_response IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attestationId);
            statement.setLong(2, workerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("accept confirmation", exception);
        }
    }

    /**
     * Records a dispute exactly once, only inside the time window and only while the worker has fewer than {@code maxOpen} unresolved disputes.
     * The count is part of the UPDATE, but under READ COMMITTED two simultaneous UPDATEs on DIFFERENT rows each count only committed rows and both
     * slip under the limit (the attack harness showed 4 of 6 concurrent disputes succeeding against a limit of 3). So the worker's row is locked first:
     * disputes by one worker run one after another, and each sees the previous one.
     */
    public boolean disputeByWorker(long attestationId, long workerId, String reason, int windowDays, int maxOpen) {
        String sql = "UPDATE attestations SET worker_response = 'DISPUTED', responded_at = CURRENT_TIMESTAMP, dispute_reason = ? "
                + "WHERE attestation_id = ? AND worker_id = ? AND status = 'APPROVED' AND worker_response IS NULL "
                + "AND COALESCE(decided_at, created_at) >= CURRENT_TIMESTAMP - (? * INTERVAL '1 day') "
                + "AND (SELECT count(*) FROM attestations o WHERE o.worker_id = ? AND o.worker_response = 'DISPUTED' AND o.dispute_decision IS NULL) < ?";
        try (var connection = connectionProvider.getConnection()) {
            boolean ownTransaction = connection.getAutoCommit();      // inside a thread-bound transaction the caller commits
            if (ownTransaction) connection.setAutoCommit(false);
            try (PreparedStatement locking = connection.prepareStatement("SELECT 1 FROM workers WHERE worker_id = ? FOR UPDATE"); PreparedStatement statement = connection.prepareStatement(sql)) {
                locking.setLong(1, workerId);
                locking.execute();
                statement.setString(1, cipher.seal(DISPUTE, attestationId, reason));
                statement.setLong(2, attestationId);
                statement.setLong(3, workerId);
                statement.setInt(4, windowDays);
                statement.setLong(5, workerId);
                statement.setInt(6, maxOpen);
                boolean recorded = statement.executeUpdate() == 1;
                if (ownTransaction) connection.commit();
                return recorded;
            } catch (SQLException failure) {
                if (ownTransaction) connection.rollback();
                throw failure;
            } finally {
                if (ownTransaction) connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw dataAccessException("dispute confirmation", exception);
        }
    }

    /** Is this confirmation currently disputed and not yet ruled on? */
    public boolean hasOpenDispute(long attestationId) {
        String sql = "SELECT 1 FROM attestations WHERE attestation_id = ? AND worker_response = 'DISPUTED' AND dispute_decision IS NULL";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attestationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw dataAccessException("check for an open dispute", exception);
        }
    }

    /** Disputes waiting for a moderator, oldest first. */
    public java.util.List<com.kaarigarsetu.model.DisputeCase> findOpenDisputes() {
        String sql = """
            SELECT a.attestation_id, a.employer_id, e.name AS employer_name, a.job_role, a.work_date, a.rating_quality, a.rating_punctuality, a.rating_conduct,
                   a.would_hire_again, a.decided_at, a.worker_response, a.responded_at, a.dispute_reason, a.dispute_decision, a.worker_id
            FROM attestations a JOIN employers e ON e.employer_id = a.employer_id
            WHERE a.worker_response = 'DISPUTED' AND a.dispute_decision IS NULL ORDER BY a.responded_at, a.attestation_id
            """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<com.kaarigarsetu.model.DisputeCase> rows = new java.util.ArrayList<>();
            while (resultSet.next()) {
                com.kaarigarsetu.model.WorkerConfirmation c = mapConfirmation(resultSet);
                rows.add(new com.kaarigarsetu.model.DisputeCase(c.attestationId(), resultSet.getLong("worker_id"), c.employerId(), c.employerName(), c.jobRole(), c.workDate(),
                        c.ratingQuality(), c.ratingPunctuality(), c.ratingConduct(), c.wouldHireAgain(), c.disputeReason(), c.respondedAt()));
            }
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list open disputes", exception);
        }
    }

    /** A moderator's ruling, exactly once. @return the worker's id, or empty if there was no open dispute with this id. */
    public Optional<Long> decideDispute(long attestationId, String decision, String moderator) {
        String sql = "UPDATE attestations SET dispute_decision = ?, dispute_decided_at = CURRENT_TIMESTAMP, dispute_decided_by = ? "
                + "WHERE attestation_id = ? AND worker_response = 'DISPUTED' AND dispute_decision IS NULL RETURNING worker_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, decision);
            statement.setString(2, moderator);
            statement.setLong(3, attestationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("decide dispute", exception);
        }
    }

    /** One approved attestation as the reputation model sees it. */
    public record FeedbackRow(long attestationId, long workerId, long employerId, Integer quality, Integer punctuality, Integer conduct,
                              Boolean wouldHireAgain, Integer durationDays, java.time.LocalDateTime at, double anomaly) {}

    /** Everything the trust score is computed from: every APPROVED attestation, oldest first. */
    public java.util.List<FeedbackRow> findApprovedFeedback() {
        String sql = """
                SELECT attestation_id, worker_id, employer_id, rating_quality, rating_punctuality, rating_conduct, would_hire_again, duration_days,
                       COALESCE(decided_at, created_at) AS at, COALESCE(anomaly_score, 0) AS anomaly
                FROM counted_attestations ORDER BY COALESCE(decided_at, created_at), attestation_id
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<FeedbackRow> rows = new java.util.ArrayList<>();
            while (resultSet.next()) {
                long id = resultSet.getLong("attestation_id");
                rows.add(new FeedbackRow(id, resultSet.getLong("worker_id"), resultSet.getLong("employer_id"),
                        cipher.openInt(QUALITY, id, resultSet.getString("rating_quality")), cipher.openInt(PUNCTUALITY, id, resultSet.getString("rating_punctuality")),
                        cipher.openInt(CONDUCT, id, resultSet.getString("rating_conduct")), cipher.openBool(HIRE, id, resultSet.getString("would_hire_again")),
                        cipher.openInt(DURATION, id, resultSet.getString("duration_days")), resultSet.getTimestamp("at").toLocalDateTime(), resultSet.getDouble("anomaly")));
            }
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list approved feedback", exception);
        }
    }

    /** Per employer: approved attestations given, and how many of their attestations a moderator ruled fraudulent. */
    public record EmployerActivity(long employerId, boolean verified, int approvedGiven, int fraudUpheld) {}

    /** Workers with at least one approved confirmation from this employer. */
    public java.util.List<Long> findApprovedWorkerIds(long employerId) {
        String sql = "SELECT DISTINCT worker_id FROM attestations WHERE employer_id = ? AND status = 'APPROVED' ORDER BY worker_id";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                while (resultSet.next()) ids.add(resultSet.getLong(1));
                return ids;
            }
        } catch (SQLException exception) {
            throw dataAccessException("find workers confirmed by employer", exception);
        }
    }

    public java.util.List<EmployerActivity> findEmployerActivity() {
        String sql = """
                SELECT e.employer_id, e.verified,
                       COUNT(c.attestation_id) AS approved,
                       COUNT(*) FILTER (WHERE f.reviewer_decision = 'FRAUD') AS fraud
                FROM employers e LEFT JOIN attestations a ON a.employer_id = e.employer_id
                                 LEFT JOIN counted_attestations c ON c.attestation_id = a.attestation_id
                                 LEFT JOIN fraud_flags f ON f.attestation_id = a.attestation_id
                WHERE e.erased_at IS NULL OR a.attestation_id IS NOT NULL
                GROUP BY e.employer_id, e.verified
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.List<EmployerActivity> rows = new java.util.ArrayList<>();
            while (resultSet.next()) {
                rows.add(new EmployerActivity(resultSet.getLong(1), resultSet.getBoolean(2), resultSet.getInt(3), resultSet.getInt(4)));
            }
            return rows;
        } catch (SQLException exception) {
            throw dataAccessException("list employer activity", exception);
        }
    }

    /** Per worker: how many attestations about them a moderator ruled fraudulent. */
    public java.util.Map<Long, Integer> findFraudUpheldByWorker() {
        String sql = """
                SELECT a.worker_id, COUNT(*) FROM fraud_flags f JOIN attestations a ON a.attestation_id = f.attestation_id
                WHERE f.reviewer_decision = 'FRAUD' GROUP BY a.worker_id
                """;
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
            while (resultSet.next()) counts.put(resultSet.getLong(1), resultSet.getInt(2));
            return counts;
        } catch (SQLException exception) {
            throw dataAccessException("count upheld fraud flags", exception);
        }
    }

    public boolean updateOtpHash(long attestationId, String otpHash) {
        String sql = "UPDATE attestations SET otp_hash = ? WHERE attestation_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, otpHash);
            statement.setLong(2, attestationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw dataAccessException("update attestation otp hash", exception);
        }
    }

    /** The hashed network identifiers recorded when the attestation was requested (never raw addresses). */
    public record NetworkHashes(String ipHash, String deviceHash) {}

    public Optional<NetworkHashes> findNetworkHashes(long attestationId) {
        String sql = "SELECT source_ip_hash, device_hash FROM attestations WHERE attestation_id = ?";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, attestationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(new NetworkHashes(resultSet.getString(1), resultSet.getString(2))) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw dataAccessException("find attestation network hashes", exception);
        }
    }

    /** Closes attestation requests that are still waiting for a code from this employer (used when the employer is erased). */
    public int expireOpenForEmployer(long employerId) {
        String sql = "UPDATE attestations SET status = 'EXPIRED', decided_at = CURRENT_TIMESTAMP WHERE employer_id = ? AND status = 'OTP_PENDING'";
        try (var connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, employerId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw dataAccessException("expire open attestations", exception);
        }
    }
}
