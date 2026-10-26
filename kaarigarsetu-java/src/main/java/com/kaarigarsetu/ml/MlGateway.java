package com.kaarigarsetu.ml;

import java.util.List;
import java.util.Map;

/** The Java side's view of the Python ML service. Services depend on this interface so tests can mock it. */
public interface MlGateway {
    record Question(int id, String text, int maxMarks, String level, String area) {
        public Question(int id, String text, int maxMarks) {
            this(id, text, maxMarks, "basic", "knowledge");
        }
    }

    record Rubric(String trade, String version, boolean testOnly, List<Question> questions) {}

    record Transcript(String transcript, String language) {}

    /** The landmarks of one recording: numbers and the frame each was found at, never audio (see the ML service's audio_fingerprint). */
    record Fingerprint(int[] hashes, int[] times, double seconds) {}

    record Evaluation(String trade, double score, String feedback, boolean needsReview, String reviewReason, String provider) {}

    record FraudFeatures(double ipFrequency, double deviceFrequency, double minutesSincePrevious, double reciprocityRatio) {}

    record FraudResult(double anomalyScore, boolean flagged) {}

    record TrustEdge(String source, String target, double weight) {}

    Rubric rubric(String trade, String language);

    Transcript transcribe(byte[] audio, String filename, String language);

    /** @return the recording's landmarks. Throws if the ML service cannot be reached or answers something unusable. */
    Fingerprint fingerprint(byte[] audio, String filename);

    Evaluation evaluate(String trade, String transcript, int questionId, String language);

    FraudResult fraudCheck(FraudFeatures features);

    /** @return trust scaled to 0-100 per node id (the most trusted node is 100). */
    Map<String, Double> trustScores(List<String> nodes, List<TrustEdge> edges);
}
