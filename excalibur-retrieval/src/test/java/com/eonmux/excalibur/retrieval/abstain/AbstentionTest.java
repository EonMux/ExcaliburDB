package com.eonmux.excalibur.retrieval.abstain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.eonmux.excalibur.config.GraphConfig;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;
import com.eonmux.excalibur.retrieval.fusion.SignalContribution;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                List.of(doc(1, 0.005, 1)), subgraphWith(SourceType.USER_ASSERTED, 90, 2));

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
                subgraphWith(SourceType.USER_ASSERTED, 95, 3));

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
                subgraphWith(SourceType.USER_ASSERTED, 95, 2));

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
                subgraphWith(SourceType.DOCUMENT, 20, 3));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(((RetrievalOutcome.NoResults) outcome).reason())
                .isEqualTo(RetrievalOutcome.Reason.LOW_PROVENANCE_CONFIDENCE);
        }

        @Test
        @DisplayName("weak evidence lowers reported confidence rather than being ignored")
        void confidenceReflectsProvenance() {
            List<FusedDocument> fused = List.of(doc(1, 0.20, 2), doc(2, 0.01, 1));

            RetrievalOutcome strong = policy.decide(
                fused, subgraphWith(SourceType.USER_ASSERTED, 100, 2));
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
            Subgraph evidence = subgraphWith(SourceType.USER_ASSERTED, 100, 2);

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
                List.of(doc(1, 1000.0, 8)), subgraphWith(SourceType.USER_ASSERTED, 100, 2));

            double confidence = ((RetrievalOutcome.Results) outcome).confidence();
            assertThat(confidence).isBetween(0.0, 1.0).isLessThan(1.0);
        }

        @Test
        @DisplayName("separation is a fraction of the best, capped at one when the runner-up is below zero")
        void separationIsBounded() {
            assertThat(ThresholdAbstentionPolicy.separation(0.8, 0.6))
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(ThresholdAbstentionPolicy.separation(0.4, -0.6)).isEqualTo(1.0);
            assertThat(ThresholdAbstentionPolicy.separation(0.4, 0.4)).isZero();
            assertThat(ThresholdAbstentionPolicy.separation(0.0, -1.0)).isEqualTo(1.0);
            assertThat(ThresholdAbstentionPolicy.separation(0.0, 0.0)).isZero();
        }

        @Test
        @DisplayName("a runner-up placed below zero leaves the best clearly separated, not over-separated")
        void negativeRunnerUpAnswers() {
            // A reranker places a candidate it could not measure beneath every one it could, below
            // zero. That is a decisive gap, and the policy must read it as one without the
            // separation running past the whole of the best score.
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.30, 1), doc(2, -0.70, 1)),
                subgraphWith(SourceType.USER_ASSERTED, 95, 2));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.Results.class);
        }

        @Test
        @DisplayName("a best score of zero or less is refused even with the score gate at zero")
        void nothingMatchedIsRefusedAtZeroThreshold() {
            ThresholdAbstentionPolicy permissive = new ThresholdAbstentionPolicy(0.0, 0.0, 0.0, 5);

            RetrievalOutcome zero = permissive.decide(List.of(doc(1, 0.0, 1)), Subgraph.empty());
            RetrievalOutcome negative = permissive.decide(
                List.of(doc(1, -0.2, 1)), Subgraph.empty());

            assertThat(zero).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(negative).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(permissive.decide(List.of(doc(1, 0.001, 1)), Subgraph.empty()))
                .isInstanceOf(RetrievalOutcome.Results.class);
        }

        @Test
        @DisplayName("a single candidate is never ambiguous")
        void singleCandidateNotAmbiguous() {
            RetrievalOutcome outcome = policy.decide(
                List.of(doc(1, 0.05, 1)), subgraphWith(SourceType.USER_ASSERTED, 95, 1));

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
                subgraphWith(SourceType.USER_ASSERTED, 90, 3))).isTrue();
            assertThat(AbstentionCalibrator.isVerifiable(
                subgraphWith(SourceType.DOCUMENT, 90, 3))).isFalse();
            assertThat(AbstentionCalibrator.isVerifiable(Subgraph.empty())).isFalse();
        }

        @Test
        @DisplayName("one extracted edge makes an otherwise curated subgraph unverifiable")
        void mixedProvenanceIsNotVerifiable() {
            // Counting this as verified would put an unlabelled answer into the ground truth.
            Subgraph mixed = new Subgraph.Builder()
                .seed(0)
                .edge(new SubgraphEdge(0, 1, 2, 0, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.USER_ASSERTED, 1, 99, 1), 1))
                .edge(new SubgraphEdge(0, 1, 3, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 2, 40, 1), 1))
                .build();

            assertThat(AbstentionCalibrator.isVerifiable(mixed)).isFalse();
        }

        @Test
        @DisplayName("the fitted threshold is the lowest one reaching the target precision")
        void fitsLowestThresholdMeetingTarget() {
            // Correct answers score high, wrong ones score low. A threshold just above the wrong
            // ones reaches perfect precision; anything higher only gives away coverage.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.09, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.02, 0.5, 0.9, true, false),
                new AbstentionCalibrator.LabelledOutcome(0.01, 0.5, 0.9, true, false));

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
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.06, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.04, 0.5, 0.9, true, false),
                new AbstentionCalibrator.LabelledOutcome(0.02, 0.5, 0.9, true, true));

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
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true, false),
                new AbstentionCalibrator.LabelledOutcome(0.08, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.06, 0.5, 0.9, true, false),
                new AbstentionCalibrator.LabelledOutcome(0.04, 0.5, 0.9, true, true));

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
                new AbstentionCalibrator.LabelledOutcome(0.05, 0.5, 0.9, true, false));

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
                subgraphWith(SourceType.USER_ASSERTED, 80, 2), true);

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
        @DisplayName("observe records what the policy reads when the scores run below zero")
        void observeBoundsNativeScores() {
            // A similarity can be negative and a runner-up the reranker could not measure sits
            // below zero. The outcome records a refused-anyway best as zero and caps separation at
            // one, which is exactly how the policy judges the same list.
            AbstentionCalibrator.LabelledOutcome belowZero = AbstentionCalibrator.observe(
                List.of(doc(1, -0.3, 1), doc(2, -0.9, 1)), Subgraph.empty(), false);
            AbstentionCalibrator.LabelledOutcome farRunnerUp = AbstentionCalibrator.observe(
                List.of(doc(1, 0.4, 1), doc(2, -0.6, 1)), Subgraph.empty(), true);

            assertThat(belowZero.topScore()).isZero();
            assertThat(farRunnerUp.separation()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("an outcome with nothing matched is refused at every score threshold, as the policy refuses it")
        void nothingMatchedIsRefusedWhenFitted() {
            // The empty-list outcome scores zero. A fit that tried zero as the score gate and
            // counted it answered would be fitting a query the policy never answers.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                AbstentionCalibrator.observe(List.of(), Subgraph.empty(), true),
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true, true));

            AbstentionCalibrator.Calibration fitted = AbstentionCalibrator.calibrate(samples, 0.5);

            assertThat(fitted.answered()).isEqualTo(1);
            assertThat(fitted.refused()).isEqualTo(1);
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
                new AbstentionCalibrator.LabelledOutcome(0.10, 0.5, 0.9, true, true),
                new AbstentionCalibrator.LabelledOutcome(0.01, 0.5, 0.9, true, false));

            ThresholdAbstentionPolicy policy =
                AbstentionCalibrator.toPolicy(AbstentionCalibrator.calibrate(samples, 1.0));

            assertThat(policy.minScore()).isEqualTo(0.10);
            assertThat(policy.decide(List.of(doc(1, 0.005, 1)), Subgraph.empty()))
                .isInstanceOf(RetrievalOutcome.NoResults.class);
        }
    }

    @Nested
    @DisplayName("joint calibration")
    class Joint {

        private static AbstentionCalibrator.LabelledOutcome sample(double top, double second,
                                                                   double confidence,
                                                                   boolean correct) {
            return AbstentionCalibrator.observe(
                List.of(doc(1, top, 1), doc(2, second, 1)),
                subgraphWith(SourceType.USER_ASSERTED, (int) Math.round(confidence * 100), 2),
                correct);
        }

        @Test
        @DisplayName("a separation gate is fitted when separation is what separates right "
            + "from wrong")
        void separationIsFitted() {
            // Score says nothing: every query scores the same. Separation says everything.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.10, 0.02, 0.9, true),
                sample(0.10, 0.02, 0.9, true),
                sample(0.10, 0.09, 0.9, false),
                sample(0.10, 0.09, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.minSeparation())
                .as("the only gate that can reach the target is the one the old fit never set")
                .isGreaterThan(0.1);
            assertThat(fitted.coverage()).isEqualTo(0.5);
            assertThat(fitted.ambiguous()).isEqualTo(2);
            assertThat(fitted.refused()).isZero();
        }

        @Test
        @DisplayName("a provenance gate is fitted when the evidence is what separates them")
        void provenanceIsFitted() {
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.10, 0.01, 0.95, true),
                sample(0.10, 0.01, 0.95, true),
                sample(0.10, 0.01, 0.40, false),
                sample(0.10, 0.01, 0.40, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.minMeanConfidence()).isGreaterThan(0.40);
            assertThat(fitted.coverage()).isEqualTo(0.5);
            assertThat(fitted.refused())
                .as("a weak-evidence query is declined, not handed back as ambiguous")
                .isEqualTo(2);
            assertThat(fitted.ambiguous()).isZero();
        }

        @Test
        @DisplayName("a gate the sample gives no reason to raise stays at zero")
        void unneededGatesStayDown() {
            // Only the score gate can exclude the wrong answer: it separates more sharply than
            // the two right ones and rests on evidence just as strong, so neither other gate can
            // remove it without removing them too.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.20, 0.01, 0.9, true),
                sample(0.20, 0.01, 0.9, true),
                sample(0.01, 0.0001, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.minScore()).isEqualTo(0.20);
            assertThat(fitted.minSeparation())
                .as("gating on a dimension that bought nothing costs coverage on the next corpus")
                .isZero();
            assertThat(fitted.minMeanConfidence()).isZero();
        }

        @Test
        @DisplayName("of two fits that answer the same queries, the one handing back ambiguities "
            + "wins")
        void ambiguityIsPreferredOverRefusal() {
            // The weak query can be excluded by the score gate, which refuses it, or by the
            // separation gate, which hands it back with its competing readings. Both answer the
            // same two queries.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.20, 0.01, 0.9, true),
                sample(0.20, 0.01, 0.9, true),
                sample(0.01, 0.005, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.coverage()).isCloseTo(2 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(fitted.ambiguous())
                .as("a caller can resolve an ambiguity by asking; a refusal ends the exchange")
                .isEqualTo(1);
            assertThat(fitted.refused()).isZero();
            assertThat(fitted.minSeparation()).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("the provenance gate is not applied to a query that had no evidence")
        void noEvidenceSkipsTheProvenanceGate() {
            AbstentionCalibrator.LabelledOutcome withoutEvidence = AbstentionCalibrator.observe(
                List.of(doc(1, 0.10, 1), doc(2, 0.01, 1)), Subgraph.empty(), true);

            assertThat(withoutEvidence.hasEvidence()).isFalse();

            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                withoutEvidence,
                sample(0.10, 0.01, 0.95, true),
                sample(0.02, 0.01, 0.20, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isTrue();
            assertThat(fitted.answered())
                .as("the policy skips the provenance check on an empty subgraph, so a fit that "
                    + "applied it would be fitting a check that never runs")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("the counts reported are the counts the fitted policy produces")
        void fitMatchesThePolicyItDescribes() {
            record Query(double top, double second, int confidence, boolean correct) { }

            List<Query> queries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                double top = 0.02 + i * 0.004;
                double second = top * (i % 5 == 0 ? 0.98 : 0.30);
                int confidence = 30 + (i % 7) * 10;
                queries.add(new Query(top, second, confidence, i % 3 != 0));
            }

            List<AbstentionCalibrator.LabelledOutcome> samples = new ArrayList<>();
            for (Query query : queries) {
                samples.add(AbstentionCalibrator.observe(
                    List.of(doc(1, query.top(), 1), doc(2, query.second(), 1)),
                    subgraphWith(SourceType.USER_ASSERTED, query.confidence(), 2),
                    query.correct()));
            }

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 0.85);
            ThresholdAbstentionPolicy policy = AbstentionCalibrator.toPolicy(fitted);

            int answered = 0;
            int ambiguous = 0;
            int refused = 0;
            int correct = 0;
            for (Query query : queries) {
                RetrievalOutcome outcome = policy.decide(
                    List.of(doc(1, query.top(), 1), doc(2, query.second(), 1)),
                    subgraphWith(SourceType.USER_ASSERTED, query.confidence(), 2));
                if (outcome instanceof RetrievalOutcome.Results) {
                    answered++;
                    if (query.correct()) {
                        correct++;
                    }
                } else if (outcome instanceof RetrievalOutcome.Ambiguous) {
                    ambiguous++;
                } else {
                    refused++;
                }
            }

            assertThat(answered)
                .as("a fit that simulates the policy differently from how the policy runs is "
                    + "fitting something else")
                .isEqualTo(fitted.answered());
            assertThat(ambiguous).isEqualTo(fitted.ambiguous());
            assertThat(refused).isEqualTo(fitted.refused());
            assertThat(correct / (double) answered).isCloseTo(fitted.precision(), org.assertj.core.data.Offset.offset(1e-9));
            assertThat(fitted.precision()).isGreaterThanOrEqualTo(0.85);
        }

        @Test
        @DisplayName("every query is answered, handed back, or declined - never lost")
        void everyQueryIsAccountedFor() {
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.20, 0.19, 0.9, false),
                sample(0.20, 0.01, 0.9, true),
                sample(0.01, 0.005, 0.2, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.answered() + fitted.ambiguous() + fitted.refused())
                .isEqualTo(fitted.sampleSize());
            assertThat(fitted.coverage())
                .isCloseTo(fitted.answered() / (double) fitted.sampleSize(), org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("a sample with more distinct values than the grid still fits")
        void largeSampleIsThinnedNotRefused() {
            List<AbstentionCalibrator.LabelledOutcome> samples = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                double top = 0.001 + i * 0.001;
                samples.add(sample(top, top * (0.05 + i * 0.003), 0.3 + i * 0.002, i >= 150));
            }

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 0.95);

            assertThat(fitted.sampleSize()).isEqualTo(300);
            assertThat(fitted.precision()).isGreaterThanOrEqualTo(0.95);
            assertThat(fitted.coverage()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("an unreachable target is still reported as unreachable across all three")
        void unreachableAcrossAllGates() {
            // Every combination of gates leaves a wrong answer in the answered set.
            List<AbstentionCalibrator.LabelledOutcome> samples = List.of(
                sample(0.20, 0.01, 0.9, false),
                sample(0.10, 0.01, 0.9, true),
                sample(0.05, 0.01, 0.9, false));

            AbstentionCalibrator.Calibration fitted =
                AbstentionCalibrator.calibrate(samples, 1.0);

            assertThat(fitted.metTarget()).isFalse();
            assertThat(fitted.precision()).isLessThan(1.0);
        }
    }

    @Nested
    @DisplayName("gates on the retriever's own scale")
    class NativeScale {

        /** One retriever as the graph route builds it: one signal, on the unit relevance scale. */
        private static HybridRetriever retrieverOver(LexicalIndex index) {
            return new HybridRetriever(
                List.of(new LuceneRanker(index, "bm25", LuceneRanker.Scale.ATTAINABLE_SHARE)),
                new ScorePassthrough(), HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH);
        }

        /** The shipped thresholds, so the tests show the defaults doing what they say. */
        private static ThresholdAbstentionPolicy shipped() {
            return new ThresholdAbstentionPolicy(GraphConfig.DEFAULT_MIN_SCORE,
                GraphConfig.DEFAULT_MIN_SEPARATION, 0.0,
                GraphConfig.DEFAULT_MAX_AMBIGUOUS_CANDIDATES);
        }

        /** People sharing the word every description has, two of them sharing a first name. */
        private static LexicalIndex people(Path dir) throws IOException {
            LexicalIndex index = LexicalIndex.open(dir, 16.0);
            index.put(1, "person ada lovelace mathematician");
            index.put(2, "person ada king countess");
            index.put(3, "person charles babbage engineer");
            index.put(4, "person alan turing logician");
            index.put(5, "person grace hopper admiral");
            index.put(6, "person claude shannon engineer");
            index.put(7, "person john neumann physicist");
            index.put(8, "person kurt goedel logician");
            index.put(9, "person marie curie chemist");
            index.put(10, "person niels bohr physicist");
            index.put(11, "person emmy noether algebraist");
            index.put(12, "person srinivasa ramanujan prodigy");
            index.refresh();
            return index;
        }

        private static RetrievalOutcome decide(LexicalIndex index, String question) {
            return shipped().decide(retrieverOver(index).retrieve(RetrievalQuery.ofText(question),
                10), Subgraph.empty());
        }

        @Test
        @DisplayName("min-score fires when no entity carries a twentieth of what the question asks")
        void minScoreFires(@TempDir Path dir) throws IOException {
            // Every word here is one the index holds, spread across all twelve people: whichever
            // matches best carries a sliver of it, and answering from that one would answer a
            // question nobody asked.
            try (LexicalIndex index = people(dir)) {
                RetrievalOutcome outcome = decide(index, "ada lovelace charles babbage alan "
                    + "turing grace hopper claude shannon john neumann kurt goedel king countess "
                    + "mathematician engineer logician admiral physicist marie curie chemist "
                    + "niels bohr emmy noether algebraist srinivasa ramanujan prodigy");

                assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
                assertThat(((RetrievalOutcome.NoResults) outcome).reason())
                    .isEqualTo(RetrievalOutcome.Reason.BELOW_CONFIDENCE_THRESHOLD);
            }
        }

        @Test
        @DisplayName("min-score does not fire on a question naming someone the graph knows")
        void minScoreHolds(@TempDir Path dir) throws IOException {
            try (LexicalIndex index = people(dir)) {
                RetrievalOutcome outcome = decide(index, "which person was grace hopper");

                assertThat(outcome).isInstanceOf(RetrievalOutcome.Results.class);
            }
        }

        @Test
        @DisplayName("min-separation fires when the question's one name fits two entities equally")
        void minSeparationFires(@TempDir Path dir) throws IOException {
            try (LexicalIndex index = people(dir)) {
                RetrievalOutcome outcome = decide(index, "what did ada write");

                assertThat(outcome).isInstanceOf(RetrievalOutcome.Ambiguous.class);
                assertThat(((RetrievalOutcome.Ambiguous) outcome).candidates())
                    .extracting(RetrievalOutcome.Candidate::entityId)
                    .startsWith(1L, 2L);
            }
        }

        @Test
        @DisplayName("min-separation does not fire once the question tells the two apart")
        void minSeparationHolds(@TempDir Path dir) throws IOException {
            try (LexicalIndex index = people(dir)) {
                RetrievalOutcome outcome = decide(index, "what did ada lovelace write");

                assertThat(outcome).isInstanceOf(RetrievalOutcome.Results.class);
            }
        }

        @Test
        @DisplayName("fused alone, the same two questions would score identically and neither gate could tell")
        void singleListFusionWouldBeInert(@TempDir Path dir) throws IOException {
            // What the passthrough fixes: reciprocal rank over one list scores the top 1/(k+1)
            // whether the question named a known person or only the word every entry shares.
            try (LexicalIndex index = people(dir)) {
                LuceneRanker ranker =
                    new LuceneRanker(index, "bm25", LuceneRanker.Scale.ATTAINABLE_SHARE);
                ReciprocalRankFusion fusion = new ReciprocalRankFusion();
                double unknown = fusion.fuse(List.of(ranker.rank(
                    RetrievalQuery.ofText("which person invented the telescope"), 10)), 10)
                    .get(0).score();
                double known = fusion.fuse(List.of(ranker.rank(
                    RetrievalQuery.ofText("which person was grace hopper"), 10)), 10)
                    .get(0).score();

                assertThat(unknown).isEqualTo(known);
            }
        }
    }
}
