package io.excalibur.retrieval.abstain;

import java.util.ArrayList;
import java.util.List;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.graph.subgraph.SubgraphEdge;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.retrieval.fusion.FusedDocument;
import io.excalibur.retrieval.fusion.SignalContribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstentionTest {

    private static FusedDocument doc(long id, double score, int signals) {
        List<SignalContribution> contributions = new ArrayList<>();
        for (int i = 0; i < signals; i++) {
            contributions.add(new SignalContribution("signal" + i, i + 1, score / signals));
        }
        return new FusedDocument(id, score, contributions);
    }

    private static Subgraph subgraphWith(SourceType sourceType, int confidence, int edgeCount) {
        Subgraph.Builder builder = new Subgraph.Builder().seed(0);
        for (int i = 0; i < edgeCount; i++) {
            builder.edge(new SubgraphEdge(0, 1, i + 1, i, Integer.MIN_VALUE, TemporalFilter.OPEN,
                new Provenance(sourceType, i, confidence, 1), 1));
        }
        return builder.build();
    }

    @Nested
    @DisplayName("threshold policy")
    class Threshold {

        private final ThresholdAbstentionPolicy policy =
            new ThresholdAbstentionPolicy(0.02, 0.10, 0.5, 5);

        @Test
        @DisplayName("no candidates at all is NO_MATCH")
        void noCandidates() {
            RetrievalOutcome outcome = policy.decide(List.of(), Subgraph.empty());

            assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(((RetrievalOutcome.NoResults) outcome).reason())
                .isEqualTo(RetrievalOutcome.Reason.NO_MATCH);
        }

        @Test
        @DisplayName("a weak best score declines rather than answering")
        void weakScoreDeclines() {
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.005, 1)), subgraphWith(SourceType.KNOWLEDGE_BASE, 90, 2));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
            RetrievalOutcome.NoResults declined = (RetrievalOutcome.NoResults) outcome;
            assertThat(declined.reason())
                .isEqualTo(RetrievalOutcome.Reason.BELOW_CONFIDENCE_THRESHOLD);
            assertThat(declined.detail()).contains("below");
        }

        @Test
        @DisplayName("a clear winner over trustworthy evidence answers")
        void clearWinnerAnswers() {
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.05, 2), doc(2, 0.01, 1)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.Results.class);
            RetrievalOutcome.Results results = (RetrievalOutcome.Results) outcome;
            assertThat(results.confidence()).isBetween(0.0, 1.0);
            assertThat(results.subgraph().edgeCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("two candidates that cannot be told apart are ambiguous, not a refusal")
        void closeCandidatesAreAmbiguous() {
            // Both clear the score threshold; neither clears the other. Picking the first would be
            // a coin flip presented as an answer, and the caller can often resolve it by asking.
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.050, 2), doc(2, 0.049, 2)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 2));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.Ambiguous.class);
            RetrievalOutcome.Ambiguous ambiguous = (RetrievalOutcome.Ambiguous) outcome;
            assertThat(ambiguous.candidates()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(ambiguous.candidates().get(0).entityId()).isEqualTo(1);
            assertThat(ambiguous.candidates().get(1).entityId()).isEqualTo(2);
        }

        @Test
        @DisplayName("a strong match over untrustworthy extractions still declines")
        void lowProvenanceDeclines() {
            // This is the failure only the provenance columns can see: retrieval worked perfectly,
            // and the evidence underneath it is guesswork.
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.09, 2), doc(2, 0.01, 1)),
                subgraphWith(SourceType.CONVERSATION, 20, 3));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(((RetrievalOutcome.NoResults) outcome).reason())
                .isEqualTo(RetrievalOutcome.Reason.LOW_PROVENANCE_CONFIDENCE);
        }

        @Test
        @DisplayName("weak evidence lowers reported confidence rather than being ignored")
        void confidenceReflectsProvenance() {
            List<FusedDocument> fused = List.of(doc(1, 0.20, 2), doc(2, 0.01, 1));

            RetrievalOutcome strong = policy.decide(
                fused, subgraphWith(SourceType.KNOWLEDGE_BASE, 100, 2));
            RetrievalOutcome weaker = policy.decide(
                fused, subgraphWith(SourceType.DOCUMENT, 60, 2));

            assertThat(strong).isInstanceOf(RetrievalOutcome.Results.class);
            assertThat(weaker).isInstanceOf(RetrievalOutcome.Results.class);
            assertThat(((RetrievalOutcome.Results) weaker).confidence())
                .isLessThan(((RetrievalOutcome.Results) strong).confidence());
        }

        @Test
        @DisplayName("agreement between signals raises confidence over a lone signal")
        void agreementRaisesConfidence() {
            Subgraph evidence = subgraphWith(SourceType.KNOWLEDGE_BASE, 100, 2);

            RetrievalOutcome lone = policy.decide(List.of(doc(1, 0.05, 1)), evidence);
            RetrievalOutcome agreed = policy.decide(List.of(doc(1, 0.05, 3)), evidence);

            assertThat(((RetrievalOutcome.Results) agreed).confidence())
                .isGreaterThan(((RetrievalOutcome.Results) lone).confidence());
        }

        @Test
        @DisplayName("confidence never reaches certainty, however strong the evidence")
        void confidenceNeverReachesOne() {
            // Nothing this policy observes justifies 1.0, and downstream code that sees 1.0 will
            // reasonably stop hedging. The ceiling has to be unreachable, not merely unusual.
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 1000.0, 8)), subgraphWith(SourceType.KNOWLEDGE_BASE, 100, 2));

            double confidence = ((RetrievalOutcome.Results) outcome).confidence();
            assertThat(confidence).isBetween(0.0, 1.0).isLessThan(1.0);
        }

        @Test
        @DisplayName("a single candidate is never ambiguous")
        void singleCandidateNotAmbiguous() {
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.05, 1)), subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 1));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.Results.class);
        }

        @Test
        @DisplayName("an ambiguous outcome with fewer than two candidates is refused")
        void ambiguousNeedsTwo() {
            assertThatThrownBy(() -> new RetrievalOutcome.Ambiguous(
                    List.of(new RetrievalOutcome.Candidate(1, "only", 0.5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
        }

        @Test
        @DisplayName("neverAbstain answers whenever anything matched")
        void neverAbstainBaseline() {
            AbstentionPolicy baseline = AbstentionPolicy.neverAbstain();

            assertThat(baseline.decide(List.of(doc(1, 0.0001, 1)), Subgraph.empty()))
                .isInstanceOf(RetrievalOutcome.Results.class);
            assertThat(baseline.decide(List.of(), Subgraph.empty()))
                .isInstanceOf(RetrievalOutcome.NoResults.class);
        }
    }

    @Nested
    @DisplayName("calibration")
    class Calibration {

        @Test
        @DisplayName("only a wholly knowledge-base subgraph counts as verifiable")
        void verifiableSlice() {
            assertThat(AbstentionCalibrator.isVerifiable(
                subgraphWith(SourceType.KNOWLEDGE_BASE, 90, 3))).isTrue();
            assertThat(AbstentionCalibrator.isVerifiable(
                subgraphWith(SourceType.CONVERSATION, 90, 3))).isFalse();
            assertThat(AbstentionCalibrator.isVerifiable(Subgraph.empty())).isFalse();
        }

        @Test
        @DisplayName("one extracted edge makes an otherwise curated subgraph unverifiable")
        void mixedProvenanceIsNotVerifiable() {
            // Counting this as verified would put an unlabelled answer into the ground truth.
            Subgraph mixed = new Subgraph.Builder()
                .seed(0)
                .edge(new SubgraphEdge(0, 1, 2, 0, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.KNOWLEDGE_BASE, 1, 99, 1), 1))
                .edge(new SubgraphEdge(0, 1, 3, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.CONVERSATION, 2, 40, 1), 1))
                .build();

            assertThat(AbstentionCalibrator.isVerifiable(mixed)).isFalse();
        }

        @Test
        @DisplayName("the fitted threshold is the lowest one reaching the target precision")
        void fitsLowestThresholdMeetingTarget() {
            // Correct answers score high, wrong ones score low. A threshold just above the wrong
            // ones reaches perfect precision; anything higher only gives away coverage.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.09, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.02, 0.5, 0.9, false),
                new AbstentionCalibrator.LabelledOutcome(0.01, 0.5, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.precision()).isEqualTo(1.0);
            assertThat(fitted.minScore()).isEqualTo(0.08);
            assertThat(fitted.coverage())
                .as("all three correct answers are still answered")
                .isEqualTo(0.6);
            assertThat(fitted.sampleSize()).isEqualTo(5);
        }

        @Test
        @DisplayName("a lower target precision buys more coverage")
        void lowerTargetGivesMoreCoverage() {
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.06, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.04, 0.5, 0.9, false),
                new AbstentionCalibrator.LabelledOutcome(0.02, 0.5, 0.9, true));

            AbstentionCalibrator.Calibration strict =
                AbstentionCalibrator.calibrate(samples, 1.0);
            AbstentionCalibrator.Calibration lenient =
                AbstentionCalibrator.calibrate(samples, 0.75);

            assertThat(lenient.coverage()).isGreaterThan(strict.coverage());
            assertThat(lenient.minScore()).isLessThan(strict.minScore());
        }

        @Test
        @DisplayName("an unreachable target is reported, not quietly approximated")
        void unreachableTargetIsReported() {
            // Every score band contains a wrong answer, so no threshold reaches 99% precision.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, false),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.06, 0.5, 0.9, false),
                new AbstentionCalibrator.LabelledOutcome(0.04, 0.5, 0.9, true));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 0.99);

            assertThat(fitted.metTarget())
                .as("believing an unmet target was met is how a system overstates its precision")
                .isFalse();
            assertThat(fitted.precision()).isLessThan(0.99);
        }

        @Test
        @DisplayName("a threshold that answers nothing is not counted as perfect precision")
        void emptyAnsweredSetIsNotPerfect() {
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.05, 0.5, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.coverage())
                .as("a threshold above every sample answers nothing and proves nothing")
                .isGreaterThan(0.0);
            assertThat(fitted.metTarget()).isFalse();
        }

        @Test
        @DisplayName("observe derives separation from the fused list")
        void observeDerivesSeparation() {
            AbstentionCalibrator.LabelledOutcome observed = AbstentionCalibrator.observe(
                List.of(doc(1, 0.10, 2), doc(2, 0.05, 1)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 80, 2), true);

            assertThat(observed.topScore()).isEqualTo(0.10);
            assertThat(observed.separation()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(observed.meanConfidence()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(observed.correct()).isTrue();
        }

        @Test
        @DisplayName("a single candidate has full separation")
        void singleCandidateSeparation() {
            AbstentionCalibrator.LabelledOutcome observed = AbstentionCalibrator.observe(
                List.of(doc(1, 0.10, 1)), Subgraph.empty(), true);

            assertThat(observed.separation()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("calibrating against nothing is refused")
        void needsSamples() {
            assertThatThrownBy(() -> AbstentionCalibrator.calibrate(List.of(), 0.9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        }

        @Test
        @DisplayName("a fitted calibration becomes a usable policy")
        void calibrationBecomesPolicy() {
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true),
                new AbstentionCalibrator.LabelledOutcome(0.01, 0.5, 0.9, false));

            ThresholdAbstentionPolicy policy = AbstentionCalibrator.toPolicy(
                AbstentionCalibrator.calibrate(samples, 1.0), 0.1, 0.5);

            assertThat(policy.minScore()).isEqualTo(0.10);
            assertThat(policy.decide(List.of(doc(1, 0.005, 1)), Subgraph.empty()))
                .isInstanceOf(RetrievalOutcome.NoResults.class);
        }
    }
}
