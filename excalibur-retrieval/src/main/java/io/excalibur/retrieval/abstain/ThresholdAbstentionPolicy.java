package io.excalibur.retrieval.abstain;

import java.util.ArrayList;
import java.util.List;

import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.retrieval.fusion.FusedDocument;

/**
 * Abstains on three independent grounds: the top score, how far it stands above its rival, and how
 * much the underlying evidence can be trusted.
 *
 * <p>Three, because they catch different failures and no one of them catches the others:
 * <ul>
 *   <li><b>Score.</b> Nothing matched well. The ordinary case of a question the corpus cannot
 *       answer.</li>
 *   <li><b>Separation.</b> Two candidates matched about equally. The ranking is not wrong here —
 *       it genuinely cannot tell them apart, and picking the one that happened to come first is a
 *       coin flip presented as an answer. This becomes {@link RetrievalOutcome.Ambiguous}, not a
 *       refusal, because the caller can often resolve it by asking.</li>
 *   <li><b>Provenance.</b> A confident match assembled from extractions the extractor itself was
 *       unsure of. The retrieval worked; the evidence beneath it did not. Only the provenance
 *       columns can see this, and without them a system reports its shakiest answers with exactly
 *       the same confidence as its firmest.</li>
 * </ul>
 *
 * <p>The thresholds are configuration. Serving cannot fit them itself: fitting needs to know
 * whether an answer was right, and that is exactly what a server answering a live question does
 * not have. {@link AbstentionCalibrator} fits them offline against outcomes that could actually be
 * checked, and {@link AbstentionCalibrator#toPolicy} turns a fit into a policy to configure - so
 * the numbers a deployment runs on are measured rather than guessed, but the measuring happens
 * before the deployment, not during it.
 */
public final class ThresholdAbstentionPolicy implements AbstentionPolicy {

    /**
     * Confidence reported by an answer that only just cleared the threshold.
     *
     * <p>Half, not zero: an answer that cleared the bar is being given, and reporting near-zero
     * confidence for something the system chose to say is not calibration, it is hedging.
     */
    private static final double MIN_ANSWERED_CONFIDENCE = 0.5;

    /** How much of the reportable range clearing the threshold by a wide margin can earn. */
    private static final double HEADROOM_WEIGHT = 0.4;

    /** How much each additional agreeing signal adds, multiplicatively. */
    private static final double AGREEMENT_WEIGHT = 0.05;

    /**
     * Ceiling on reported confidence. Deliberately below one.
     *
     * <p>Nothing this policy can observe justifies certainty. The score is a fused rank statistic,
     * the evidence is extracted, and the calibration behind the threshold was fitted on a finite
     * and biased sample. A system that reports 1.0 is claiming the answer cannot be wrong, and
     * downstream code that sees 1.0 will reasonably stop hedging — so the value has to be
     * unreachable rather than merely unusual.
     *
     * <p>Public because it is the ceiling on a reported confidence rather than on this policy's
     * arithmetic, and {@link ConfidenceCalibration#apply} has to respect the same bound. A second
     * copy of the number over there would be free to drift away from the reasoning above.
     */
    public static final double MAX_REPORTED_CONFIDENCE = 0.99;

    private final double minScore;
    private final double minSeparation;
    private final double minMeanConfidence;
    private final int maxAmbiguousCandidates;

    /**
     * @param minScore               fused score the best candidate must reach
     * @param minSeparation          relative gap the best must have over the second, as a fraction
     *                               of the best score; below this the two are called ambiguous
     * @param minMeanConfidence      mean extractor confidence the subgraph must reach, in [0, 1]
     * @param maxAmbiguousCandidates how many competing readings to report
     */
    public ThresholdAbstentionPolicy(double minScore, double minSeparation,
                                     double minMeanConfidence, int maxAmbiguousCandidates) {
        if (minScore < 0 || Double.isNaN(minScore)) {
            throw new IllegalArgumentException("minScore must be non-negative, got " + minScore);
        }
        if (minSeparation < 0 || minSeparation > 1 || Double.isNaN(minSeparation)) {
            throw new IllegalArgumentException(
                "minSeparation is a fraction of the best score and must be in [0, 1], got "
                    + minSeparation);
        }
        if (minMeanConfidence < 0 || minMeanConfidence > 1 || Double.isNaN(minMeanConfidence)) {
            throw new IllegalArgumentException(
                "minMeanConfidence must be in [0, 1], got " + minMeanConfidence);
        }
        if (maxAmbiguousCandidates < 2) {
            throw new IllegalArgumentException(
                "maxAmbiguousCandidates must be at least 2, got " + maxAmbiguousCandidates);
        }
        this.minScore = minScore;
        this.minSeparation = minSeparation;
        this.minMeanConfidence = minMeanConfidence;
        this.maxAmbiguousCandidates = maxAmbiguousCandidates;
    }

    /** The fused score threshold in use. */
    public double minScore() {
        return minScore;
    }

    /** The required relative separation between the best two candidates. */
    public double minSeparation() {
        return minSeparation;
    }

    /** The required mean extractor confidence. */
    public double minMeanConfidence() {
        return minMeanConfidence;
    }

    @Override
    public RetrievalOutcome decide(List<FusedDocument> fused, Subgraph subgraph) {
        if (fused == null || fused.isEmpty()) {
            return RetrievalOutcome.NoResults.of(RetrievalOutcome.Reason.NO_MATCH);
        }

        FusedDocument best = fused.get(0);
        if (best.score() < minScore) {
            return new RetrievalOutcome.NoResults(
                RetrievalOutcome.Reason.BELOW_CONFIDENCE_THRESHOLD,
                "best fused score " + format(best.score()) + " is below " + format(minScore));
        }

        // Checked before provenance: two candidates that cannot be told apart is a question the
        // caller can resolve, whereas untrustworthy evidence is not.
        if (fused.size() > 1) {
            double separation = (best.score() - fused.get(1).score()) / best.score();
            if (separation < minSeparation) {
                return new RetrievalOutcome.Ambiguous(candidatesOf(fused));
            }
        }

        if (subgraph != null && !subgraph.isEmpty()) {
            double meanConfidence = subgraph.meanConfidence();
            if (meanConfidence < minMeanConfidence) {
                return new RetrievalOutcome.NoResults(
                    RetrievalOutcome.Reason.LOW_PROVENANCE_CONFIDENCE,
                    "mean extractor confidence " + format(meanConfidence) + " is below "
                        + format(minMeanConfidence));
            }
        }

        return new RetrievalOutcome.Results(
            subgraph == null ? Subgraph.empty() : subgraph,
            confidenceOf(best, fused, subgraph));
    }

    /**
     * Maps the evidence onto a calibrated {@code [0, 1]} confidence.
     *
     * <p>A fused reciprocal-rank score has no absolute meaning — its range depends on how many
     * signals ran — so reporting it directly would be a number that looks like a probability and
     * is not one. This expresses the score as how far it cleared the threshold, then tempers it by
     * the evidence's own confidence, so a strong match over weak extractions does not report as
     * certain.
     */
    private double confidenceOf(FusedDocument best, List<FusedDocument> fused, Subgraph subgraph) {
        double headroom = minScore <= 0
            ? 1.0
            : Math.min(1.0, (best.score() - minScore) / minScore);
        double base = MIN_ANSWERED_CONFIDENCE + HEADROOM_WEIGHT * headroom;

        // Agreement between independent signals is evidence the score alone does not carry: three
        // signals independently ranking something highly is a different claim from one doing so.
        if (best.signalCount() > 1) {
            base *= 1.0 + AGREEMENT_WEIGHT * (best.signalCount() - 1);
        }
        if (subgraph != null && !subgraph.isEmpty()) {
            base *= subgraph.meanConfidence();
        }
        return Math.max(0.0, Math.min(MAX_REPORTED_CONFIDENCE, base));
    }

    private List<RetrievalOutcome.Candidate> candidatesOf(List<FusedDocument> fused) {
        int limit = Math.min(maxAmbiguousCandidates, fused.size());
        List<RetrievalOutcome.Candidate> candidates = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            FusedDocument document = fused.get(i);
            candidates.add(new RetrievalOutcome.Candidate(
                document.documentId(), null, document.score()));
        }
        return candidates;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    @Override
    public String toString() {
        return "ThresholdAbstentionPolicy[minScore=" + format(minScore)
            + ", minSeparation=" + format(minSeparation)
            + ", minMeanConfidence=" + format(minMeanConfidence) + "]";
    }
}
