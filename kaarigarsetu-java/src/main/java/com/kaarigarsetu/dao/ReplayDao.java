package com.kaarigarsetu.dao;

import com.kaarigarsetu.config.ConnectionProvider;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one-way fingerprints of stored answers (migration 011) and the searches that recognise a recording or a text that has been seen before. Everything
 * here is keyed hashes and counts; no audio and no words. The searches are single set-based statements over arrays, so a search stays one round trip however
 * many landmarks an answer has.
 */
public class ReplayDao extends JdbcDaoSupport {
    /** How many landmarks of the searched recording turned up in a stored answer at one time shift (delta, in frames). */
    public record Vote(long assessmentId, int delta, int votes) {
    }

    /** How many word trigrams of the searched answer a stored answer shares. */
    public record Overlap(long assessmentId, int common) {
    }

    /** What is known about a stored answer, enough to decide whether a match is the same person's own earlier work. */
    public record Meta(long assessmentId, long workerId, UUID attemptId, Integer questionNo, int fingerprintCount, int shingleCount) {
    }

    private static final long ANSWER_CHECK_LOCK = 0x4B53_5245_504C_4159L;   // an arbitrary constant for this lock ("KSREPLAY")

    public ReplayDao(ConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    /**
     * Takes a lock, held until the surrounding transaction ends, that lets one answer at a time be compared and stored. Without it two simultaneous uploads of the
     * same recording each search before the other is stored and both are found clean. One global lock is enough at this scale (a search takes milliseconds); it is
     * released by commit or rollback, never by the application.
     */
    public void lockAnswerChecks() {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, ANSWER_CHECK_LOCK);
            statement.execute();
        } catch (SQLException exception) {
            throw dataAccessException("lock answer checks", exception);
        }
    }

    /** Stores the landmarks and word trigrams of an answer (call inside the transaction that stores the answer). */
    public void store(long assessmentId, long[] landmarkKeys, int[] landmarkTimes, long[] shingleKeys) {
        try (Connection connection = connectionProvider.getConnection()) {
            if (landmarkKeys.length > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO answer_fingerprints (fp, assessment_id, t) SELECT k, ?::bigint, t FROM unnest(?::bigint[], ?::integer[]) AS u(k, t)")) {
                    statement.setLong(1, assessmentId);
                    statement.setArray(2, longs(connection, landmarkKeys));
                    statement.setArray(3, ints(connection, landmarkTimes));
                    statement.executeUpdate();
                }
            }
            if (shingleKeys.length > 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO answer_shingles (sh, assessment_id) SELECT DISTINCT k, ?::bigint FROM unnest(?::bigint[]) AS u(k)")) {
                    statement.setLong(1, assessmentId);
                    statement.setArray(2, longs(connection, shingleKeys));
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw dataAccessException("store answer fingerprints", exception);
        }
    }

    /**
     * Groups the stored landmarks that equal a searched one by (answer, time shift). {@code minVotes} only trims the result to the interesting groups; the caller
     * pools neighbouring shifts and applies the real threshold.
     */
    public List<Vote> findAudioVotes(long[] queryKeys, int[] queryTimes, int minVotes) {
        String sql = """
                SELECT f.assessment_id, f.t - q.t AS delta, count(*) AS votes
                FROM unnest(?::bigint[], ?::integer[]) AS q(fp, t) JOIN answer_fingerprints f ON f.fp = q.fp
                GROUP BY f.assessment_id, f.t - q.t
                HAVING count(*) >= ?
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, longs(connection, queryKeys));
            statement.setArray(2, ints(connection, queryTimes));
            statement.setInt(3, minVotes);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Vote> votes = new ArrayList<>();
                while (resultSet.next()) votes.add(new Vote(resultSet.getLong(1), resultSet.getInt(2), resultSet.getInt(3)));
                return votes;
            }
        } catch (SQLException exception) {
            throw dataAccessException("search answer fingerprints", exception);
        }
    }

    public List<Overlap> findShingleOverlap(long[] shingleKeys, int minCommon) {
        String sql = "SELECT assessment_id, count(*) FROM answer_shingles WHERE sh = ANY (?::bigint[]) GROUP BY assessment_id HAVING count(*) >= ?";
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, longs(connection, shingleKeys));
            statement.setInt(2, minCommon);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Overlap> overlaps = new ArrayList<>();
                while (resultSet.next()) overlaps.add(new Overlap(resultSet.getLong(1), resultSet.getInt(2)));
                return overlaps;
            }
        } catch (SQLException exception) {
            throw dataAccessException("search answer shingles", exception);
        }
    }

    public Map<Long, Meta> findMeta(Collection<Long> assessmentIds) {
        Map<Long, Meta> out = new HashMap<>();
        if (assessmentIds.isEmpty()) return out;
        String sql = "SELECT assessment_id, worker_id, attempt_id, question_no, fingerprint_count, shingle_count FROM assessments WHERE assessment_id = ANY (?::bigint[])";
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, longs(connection, assessmentIds.stream().mapToLong(Long::longValue).toArray()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int question = resultSet.getInt(4);
                    Integer questionNo = resultSet.wasNull() ? null : question;
                    out.put(resultSet.getLong(1), new Meta(resultSet.getLong(1), resultSet.getLong(2), resultSet.getObject(3, UUID.class), questionNo, resultSet.getInt(5), resultSet.getInt(6)));
                }
            }
            return out;
        } catch (SQLException exception) {
            throw dataAccessException("read answer metadata", exception);
        }
    }

    private static Array longs(Connection connection, long[] values) throws SQLException {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return connection.createArrayOf("bigint", boxed);
    }

    private static Array ints(Connection connection, int[] values) throws SQLException {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return connection.createArrayOf("integer", boxed);
    }
}
