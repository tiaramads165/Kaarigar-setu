-- Migration 011: replay detection for voice answers.
--
-- A good recording played into the assessment gives the right words, the right transcript and a good score. What it cannot hide is that it is the same
-- recording, or the same words, as an answer given before. For each stored answer we keep:
--   * answer_fingerprints: keyed hashes of the answer's acoustic LANDMARKS (see kaarigarsetu-ml-service/app/services/audio_fingerprint.py) with the frame
--     each was found at, so the same recording played again is found whatever the volume, noise, trim or room;
--   * answer_shingles: keyed hashes of the answer's word TRIGRAMS, so the same words said again (a time-stretched replay, a re-synthesised or shared script)
--     are found even when the audio no longer matches.
-- Both are one-way keyed hashes (the key is the phone HMAC key, with a purpose label): they are not audio and the words cannot be read back from them.
-- Raw audio is still never stored. Rows go with the answer (ON DELETE CASCADE), so erasing a worker erases them.
CREATE TABLE answer_fingerprints (
    fp            BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL REFERENCES assessments (assessment_id) ON DELETE CASCADE,
    t             INTEGER NOT NULL
);
CREATE INDEX idx_answer_fingerprints_fp ON answer_fingerprints (fp);
CREATE INDEX idx_answer_fingerprints_assessment ON answer_fingerprints (assessment_id);

CREATE TABLE answer_shingles (
    sh            BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL REFERENCES assessments (assessment_id) ON DELETE CASCADE
);
CREATE INDEX idx_answer_shingles_sh ON answer_shingles (sh);
CREATE INDEX idx_answer_shingles_assessment ON answer_shingles (assessment_id);

-- What the checks concluded about each answer, kept so a flagged answer can be explained and the checks measured.
ALTER TABLE assessments
    ADD COLUMN replay_status     VARCHAR(16) NOT NULL DEFAULT 'NOT_CHECKED'
        CHECK (replay_status IN ('NOT_CHECKED', 'CLEAR', 'TOO_SHORT', 'REPLAY_AUDIO', 'REPLAY_TEXT', 'REPLAY_BOTH', 'CHECK_FAILED')),
    ADD COLUMN challenge_status  VARCHAR(16) NOT NULL DEFAULT 'NOT_ASKED'
        CHECK (challenge_status IN ('NOT_ASKED', 'HEARD', 'NOT_HEARD')),
    ADD COLUMN fingerprint_count INTEGER NOT NULL DEFAULT 0 CHECK (fingerprint_count >= 0),
    ADD COLUMN shingle_count     INTEGER NOT NULL DEFAULT 0 CHECK (shingle_count >= 0);

-- Existing databases: the application role stores and reads these (a fresh setup gets the same from setup_roles.sql).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaarigar_app') THEN
        GRANT SELECT, INSERT ON answer_fingerprints, answer_shingles TO kaarigar_app;
    END IF;
END
$$;
