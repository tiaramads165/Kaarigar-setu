package com.kaarigarsetu.admin;

import java.util.List;

/**
 * Every database column whose values are stored sealed (see security/DataCipher), with the exact label and row id each value is tied to. The SQL texts are
 * fixed literals, never built from input. The purpose labels here must match the ones the data-access classes use; a test writes through the real classes
 * and reads every value back with THESE labels, so a mismatch fails the build.
 */
public final class SealedColumns {
    /** @param purpose the label sealed into the value; @param select every non-null (id, value) pair; @param update compare-and-set: the value is replaced only if it is still the one that was read */
    public record Column(String purpose, String select, String update) {
    }

    private SealedColumns() {
    }

    public static final List<Column> ALL = List.of(
            new Column("workers.name", "SELECT worker_id, name FROM workers WHERE name IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET name = ? WHERE worker_id = ? AND name = ?"),
            new Column("workers.skill_score", "SELECT worker_id, skill_score FROM workers WHERE skill_score IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET skill_score = ? WHERE worker_id = ? AND skill_score = ?"),
            new Column("workers.trust_score", "SELECT worker_id, trust_score FROM workers WHERE trust_score IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET trust_score = ? WHERE worker_id = ? AND trust_score = ?"),
            new Column("workers.skill_band", "SELECT worker_id, skill_band FROM workers WHERE skill_band IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET skill_band = ? WHERE worker_id = ? AND skill_band = ?"),
            new Column("workers.skill_breakdown", "SELECT worker_id, skill_breakdown FROM workers WHERE skill_breakdown IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET skill_breakdown = ? WHERE worker_id = ? AND skill_breakdown = ?"),
            new Column("workers.trust_band", "SELECT worker_id, trust_band FROM workers WHERE trust_band IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET trust_band = ? WHERE worker_id = ? AND trust_band = ?"),
            new Column("workers.trust_report", "SELECT worker_id, trust_report FROM workers WHERE trust_report IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET trust_report = ? WHERE worker_id = ? AND trust_report = ?"),
            new Column("workers.band_near_boundary", "SELECT worker_id, band_near_boundary FROM workers WHERE band_near_boundary IS NOT NULL ORDER BY worker_id",
                    "UPDATE workers SET band_near_boundary = ? WHERE worker_id = ? AND band_near_boundary = ?"),
            new Column("employers.name", "SELECT employer_id, name FROM employers WHERE name IS NOT NULL ORDER BY employer_id",
                    "UPDATE employers SET name = ? WHERE employer_id = ? AND name = ?"),
            new Column("assessments.transcript", "SELECT assessment_id, transcript FROM assessments WHERE transcript IS NOT NULL ORDER BY assessment_id",
                    "UPDATE assessments SET transcript = ? WHERE assessment_id = ? AND transcript = ?"),
            new Column("assessments.score", "SELECT assessment_id, score FROM assessments WHERE score IS NOT NULL ORDER BY assessment_id",
                    "UPDATE assessments SET score = ? WHERE assessment_id = ? AND score = ?"),
            new Column("assessments.feedback", "SELECT assessment_id, feedback FROM assessments WHERE feedback IS NOT NULL ORDER BY assessment_id",
                    "UPDATE assessments SET feedback = ? WHERE assessment_id = ? AND feedback = ?"),
            new Column("assessments.review_reason", "SELECT assessment_id, review_reason FROM assessments WHERE review_reason IS NOT NULL ORDER BY assessment_id",
                    "UPDATE assessments SET review_reason = ? WHERE assessment_id = ? AND review_reason = ?"),
            new Column("attestations.job_role", "SELECT attestation_id, job_role FROM attestations WHERE job_role IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET job_role = ? WHERE attestation_id = ? AND job_role = ?"),
            new Column("attestations.duration_days", "SELECT attestation_id, duration_days FROM attestations WHERE duration_days IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET duration_days = ? WHERE attestation_id = ? AND duration_days = ?"),
            new Column("attestations.would_hire_again", "SELECT attestation_id, would_hire_again FROM attestations WHERE would_hire_again IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET would_hire_again = ? WHERE attestation_id = ? AND would_hire_again = ?"),
            new Column("attestations.work_date", "SELECT attestation_id, work_date FROM attestations WHERE work_date IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET work_date = ? WHERE attestation_id = ? AND work_date = ?"),
            new Column("attestations.rating_quality", "SELECT attestation_id, rating_quality FROM attestations WHERE rating_quality IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET rating_quality = ? WHERE attestation_id = ? AND rating_quality = ?"),
            new Column("attestations.rating_punctuality", "SELECT attestation_id, rating_punctuality FROM attestations WHERE rating_punctuality IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET rating_punctuality = ? WHERE attestation_id = ? AND rating_punctuality = ?"),
            new Column("attestations.rating_conduct", "SELECT attestation_id, rating_conduct FROM attestations WHERE rating_conduct IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET rating_conduct = ? WHERE attestation_id = ? AND rating_conduct = ?"),
            new Column("attestations.dispute_reason", "SELECT attestation_id, dispute_reason FROM attestations WHERE dispute_reason IS NOT NULL ORDER BY attestation_id",
                    "UPDATE attestations SET dispute_reason = ? WHERE attestation_id = ? AND dispute_reason = ?"),
            new Column("fraud_flags.features", "SELECT flag_id, features FROM fraud_flags WHERE features IS NOT NULL ORDER BY flag_id",
                    "UPDATE fraud_flags SET features = ? WHERE flag_id = ? AND features = ?"),
            new Column("credentials.signed_payload", "SELECT credential_id, signed_payload FROM credentials WHERE signed_payload IS NOT NULL ORDER BY credential_id",
                    "UPDATE credentials SET signed_payload = ? WHERE credential_id = ? AND signed_payload = ?"));
}
