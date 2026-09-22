package com.eonmux.excalibur.retrieval.abstain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.SignalContribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ConfidenceCalibrationTest {

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

    /** {@code count} answers all claiming {@code confidence}, of which {@code right} were. */
    private static List<ConfidenceOutcome> band(double confidence, int count, int right) {
        List<ConfidenceOutcome> outcomes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            outcomes.add(new ConfidenceOutcome(confidence, i < right));
        }
        return outcomes;
    }

    private static List<ConfidenceOutcome> concat(List<ConfidenceOutcome> a,
                                                  List<ConfidenceOutcome> b) {
        List<ConfidenceOutcome> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Nested
    @DisplayName("reliability diagram")
    class Reliability {

        @Test
        @DisplayName("every band is returned, including the ones nothing landed in")
        void emptyBandsAreReported() {
            List<ReliabilityBin> diagram =
                AbstentionCalibrator.reliability(band(0.85, 4, 3), 10);

            assertThat(diagram).hasSize(10);
            assertThat(diagram.get(8).count()).isEqualTo(4);
            assertThat(diagram.get(8).isEmpty()).isFalse();
            assertThat(diagram.stream().filter(ReliabilityBin::isEmpty).count())
                .as("a diagram that hides the unused part of the scale hides the coverage")
                .isEqualTo(9);
        }

        @Test
        @DisplayName("an empty band reports no gap rather than a perfect one")
        void emptyBandHasNoGap() {
            ReliabilityBin empty = AbstentionCalibrator.reliability(band(0.85, 1, 1), 10).get(0);

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.gap()).isZero();
            assertThat(empty.overconfident())
                .as("a band that saw nothing cannot have been overconfident about it")
                .isFalse();
        }

        @Test
        @DisplayName("full confidence lands in the top band rather than off the end")
        void fullConfidenceIsBinned() {
            List<ReliabilityBin> diagram =
                AbstentionCalibrator.reliability(band(1.0, 3, 2), 10);

            assertThat(diagram.get(9).count()).isEqualTo(3);
            assertThat(diagram.get(9).upperBound()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a band records what it claimed against what it achieved")
        void bandRecordsClaimAndOutcome() {
            ReliabilityBin bin = AbstentionCalibrator.reliability(band(0.9, 10, 6), 10).get(9);

            assertThat(bin.meanConfidence()).isCloseTo(0.9, within(1e-9));
            assertThat(bin.observedAccuracy()).isCloseTo(0.6, within(1e-9));
            assertThat(bin.gap()).isCloseTo(0.3, within(1e-9));
            assertThat(bin.overconfident()).isTrue();
        }

        @Test
        @DisplayName("underconfidence is a gap but not overconfidence")
        void underconfidenceIsDistinguished() {
            ReliabilityBin bin = AbstentionCalibrator.reliability(band(0.6, 10, 9), 10).get(6);

            assertThat(bin.gap()).isCloseTo(0.3, within(1e-9));
            assertThat(bin.overconfident())
                .as("checking an answer that was right costs less than acting on one that was not")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("expected calibration error")
    class Error {

        @Test
        @DisplayName("honest confidences score near zero")
        void honestConfidencesScoreZero() {
            List<ConfidenceOutcome> samples = concat(band(0.9, 100, 90), band(0.6, 100, 60));

            assertThat(AbstentionCalibrator.expectedCalibrationError(samples))
                .isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("the error is the count-weighted mean gap, not the mean of the gaps")
        void errorIsCountWeighted() {
            // 90 answers overstated by 0.30, 10 understated by 0.10. An unweighted mean would
            // report 0.20; the answers actually given were overstated by 0.28.
            List<ConfidenceOutcome> samples = concat(band(0.9, 90, 54), band(0.6, 10, 7));

            assertThat(AbstentionCalibrator.expectedCalibrationError(samples))
                .isCloseTo(0.9 * 0.3 + 0.1 * 0.1, within(1e-9));
        }

        @Test
        @DisplayName("bands too coarse to separate two claims average them away")
        void coarseBandsHideStructure() {
            // Overconfident at 0.9, equally underconfident at 0.6. One band spanning both sees a
            // mean claim of 0.75 against a mean accuracy of 0.75 and reports nothing wrong.
            List<ConfidenceOutcome> samples = concat(band(0.9, 100, 60), band(0.6, 100, 90));

            assertThat(AbstentionCalibrator.expectedCalibrationError(samples, 1))
                .as("this is why the diagram is public beside the summary")
                .isCloseTo(0.0, within(1e-9));
            assertThat(AbstentionCalibrator.expectedCalibrationError(samples, 10))
                .isCloseTo(0.3, within(1e-9));
        }
    }

    @Nested
    @DisplayName("temperature scaling")
    class Temperature {

        @Test
        @DisplayName("overconfident claims are softened, and the error falls")
        void overconfidenceIsSoftened() {
            List<ConfidenceOutcome> samples = concat(band(0.9, 100, 60), band(0.7, 100, 55));

            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(samples);

            assertThat(fitted.wellPosed()).isTrue();
            assertThat(fitted.temperature())
                .as("a temperature above one pulls claims towards the midpoint")
                .isGreaterThan(1.0);
            assertThat(fitted.uncalibratedError()).isCloseTo(0.225, within(1e-9));
            assertThat(fitted.expectedCalibrationError())
                .isLessThan(0.05)
                .isLessThan(fitted.uncalibratedError());
            assertThat(fitted.sampleSize()).isEqualTo(200);
        }

        @Test
        @DisplayName("underconfident claims are sharpened")
        void underconfidenceIsSharpened() {
            List<ConfidenceOutcome> samples = concat(band(0.6, 100, 90), band(0.55, 100, 80));

            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(samples);

            assertThat(fitted.wellPosed()).isTrue();
            assertThat(fitted.temperature()).isLessThan(1.0);
            assertThat(fitted.expectedCalibrationError())
                .isLessThan(fitted.uncalibratedError());
        }

        @Test
        @DisplayName("confidences that were already honest are left alone")
        void honestConfidencesAreLeftAlone() {
            List<ConfidenceOutcome> samples = concat(band(0.9, 100, 90), band(0.6, 100, 60));

            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(samples);

            assertThat(fitted.wellPosed()).isTrue();
            assertThat(fitted.temperature()).isCloseTo(1.0, within(1e-6));
            assertThat(fitted.expectedCalibrationError()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("a perfectly separable sample has no temperature, and says so")
        void perfectSeparationIsNotWellPosed() {
            // Every confident answer right and every diffident one wrong. The likelihood keeps
            // improving as the temperature falls, so there is no optimum to find.
            List<ConfidenceOutcome> samples = concat(band(0.9, 50, 50), band(0.2, 50, 0));

            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(samples);

            assertThat(fitted.wellPosed())
                .as("a fit that ran to the end of the range has not converged on anything")
                .isFalse();
        }

        @Test
        @DisplayName("confidences carrying no information have no temperature either")
        void uninformativeSampleIsNotWellPosed() {
            ConfidenceCalibration fitted =
                AbstentionCalibrator.fitTemperature(band(0.5, 100, 50));

            assertThat(fitted.wellPosed()).isFalse();
            assertThat(fitted.temperature()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("scaling preserves order, so it cannot damage a ranking")
        void scalingIsMonotone() {
            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(
                concat(band(0.9, 100, 60), band(0.7, 100, 55)));

            double previous = -1.0;
            for (int i = 0; i <= 100; i++) {
                double calibrated = fitted.apply(i / 100.0);
                assertThat(calibrated).isGreaterThanOrEqualTo(previous);
                previous = calibrated;
            }
        }

        @Test
        @DisplayName("scaling fixes the midpoint, so it cannot flip a leaning")
        void scalingFixesTheMidpoint() {
            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(
                concat(band(0.9, 100, 60), band(0.7, 100, 55)));

            assertThat(fitted.apply(0.5)).isCloseTo(0.5, within(1e-9));
            assertThat(fitted.apply(0.8)).isGreaterThan(0.5);
            assertThat(fitted.apply(0.2)).isLessThan(0.5);
        }

        @Test
        @DisplayName("a calibrated confidence still never claims certainty")
        void certaintyStaysUnreachable() {
            ConfidenceCalibration sharp = AbstentionCalibrator.fitTemperature(
                concat(band(0.9, 50, 50), band(0.2, 50, 0)));

            assertThat(sharp.apply(1.0))
                .isLessThanOrEqualTo(ThresholdAbstentionPolicy.MAX_REPORTED_CONFIDENCE);
            assertThat(sharp.apply(0.99))
                .isLessThanOrEqualTo(ThresholdAbstentionPolicy.MAX_REPORTED_CONFIDENCE);
        }

        @Test
        @DisplayName("the error reported is the error of the numbers that will be reported")
        void reportedErrorMatchesReportedNumbers() {
            List<ConfidenceOutcome> samples = concat(band(0.9, 100, 60), band(0.7, 100, 55));
            ConfidenceCalibration fitted = AbstentionCalibrator.fitTemperature(samples);

            List<ConfidenceOutcome> asReported = new ArrayList<>();
            for (ConfidenceOutcome sample : samples) {
                asReported.add(
                    new ConfidenceOutcome(fitted.apply(sample.confidence()), sample.correct()));
            }

            assertThat(AbstentionCalibrator.expectedCalibrationError(asReported))
                .isCloseTo(fitted.expectedCalibrationError(), within(1e-12));
        }

        @Test
        @DisplayName("fitting against nothing is refused")
        void needsSamples() {
            assertThatThrownBy(() -> AbstentionCalibrator.fitTemperature(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        }

        @Test
        @DisplayName("a diagram needs at least one band")
        void needsBins() {
            assertThatThrownBy(
                () -> AbstentionCalibrator.reliability(band(0.9, 1, 1), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        }

        @Test
        @DisplayName("a confidence outside [0, 1] is not a confidence")
        void rejectsImpossibleConfidence() {
            assertThatThrownBy(() -> new ConfidenceOutcome(1.4, true))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ConfidenceOutcome(Double.NaN, true))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("observing what a policy reported")
    class Observing {

        private final ThresholdAbstentionPolicy policy =
            new ThresholdAbstentionPolicy(0.02, 0.10, 0.5, 5);

        @Test
        @DisplayName("an answered query contributes the confidence the policy stated")
        void answeredQueryIsObserved() {
            Optional<ConfidenceOutcome> observed = AbstentionCalibrator.observeConfidence(
                policy, List.of(doc(1, 0.05, 2), doc(2, 0.01, 1)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3), true);

            assertThat(observed).isPresent();
            assertThat(observed.get().correct()).isTrue();
            assertThat(observed.get().confidence()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("a refusal contributes nothing, because it claimed nothing")
        void refusalIsNotObserved() {
            Optional<ConfidenceOutcome> observed = AbstentionCalibrator.observeConfidence(
                policy, List.of(doc(1, 0.005, 1)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 90, 2), false);

            assertThat(observed).isEmpty();
        }

        @Test
        @DisplayName("an ambiguity contributes nothing either")
        void ambiguityIsNotObserved() {
            Optional<ConfidenceOutcome> observed = AbstentionCalibrator.observeConfidence(
                policy, List.of(doc(1, 0.050, 1), doc(2, 0.049, 1)),
                subgraphWith(SourceType.KNOWLEDGE_BASE, 90, 2), true);

            assertThat(observed).isEmpty();
        }

        @Test
        @DisplayName("no candidates at all is not an answer")
        void emptyCandidatesAreNotObserved() {
            assertThat(AbstentionCalibrator.observeConfidence(
                policy, List.of(), Subgraph.empty(), false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("calibrated policy")
    class Calibrated {

        private final ThresholdAbstentionPolicy delegate =
            new ThresholdAbstentionPolicy(0.02, 0.10, 0.5, 5);

        private final ConfidenceCalibration softening = AbstentionCalibrator.fitTemperature(
            concat(band(0.9, 100, 60), band(0.7, 100, 55)));

        @Test
        @DisplayName("the reported confidence is scaled")
        void confidenceIsScaled() {
            List<FusedDocument> fused = List.of(doc(1, 0.05, 2), doc(2, 0.01, 1));
            Subgraph evidence = subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3);

            double raw = ((RetrievalOutcome.Results) delegate.decide(fused, evidence)).confidence();
            double calibrated = ((RetrievalOutcome.Results) AbstentionCalibrator
                .toPolicy(delegate, softening).decide(fused, evidence)).confidence();

            assertThat(calibrated).isCloseTo(softening.apply(raw), within(1e-12));
            assertThat(calibrated).isLessThan(raw);
        }

        @Test
        @DisplayName("the decision is untouched, so the operating point does not move")
        void decisionIsUnchanged() {
            AbstentionPolicy calibrated = AbstentionCalibrator.toPolicy(delegate, softening);

            List<List<FusedDocument>> cases = List.of(
                List.of(),
                List.of(doc(1, 0.005, 1)),
                List.of(doc(1, 0.050, 1), doc(2, 0.049, 1)),
                List.of(doc(1, 0.05, 2), doc(2, 0.01, 1)));

            for (List<FusedDocument> fused : cases) {
                Subgraph evidence = subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3);
                assertThat(calibrated.decide(fused, evidence))
                    .as("case %s", fused)
                    .hasSameClassAs(delegate.decide(fused, evidence));
            }
        }

        @Test
        @DisplayName("a refusal passes through with its reason intact")
        void refusalPassesThrough() {
            RetrievalOutcome outcome = AbstentionCalibrator.toPolicy(delegate, softening)
                .decide(List.of(doc(1, 0.005, 1)), subgraphWith(SourceType.DOCUMENT, 90, 2));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.NoResults.class);
            assertThat(((RetrievalOutcome.NoResults) outcome).reason())
                .isEqualTo(RetrievalOutcome.Reason.BELOW_CONFIDENCE_THRESHOLD);
        }

        @Test
        @DisplayName("an ambiguity passes through with its candidate scores untouched")
        void ambiguityPassesThrough() {
            List<FusedDocument> fused = List.of(doc(1, 0.050, 1), doc(2, 0.049, 1));
            RetrievalOutcome outcome = AbstentionCalibrator.toPolicy(delegate, softening)
                .decide(fused, subgraphWith(SourceType.KNOWLEDGE_BASE, 90, 2));

            assertThat(outcome).isInstanceOf(RetrievalOutcome.Ambiguous.class);
            assertThat(((RetrievalOutcome.Ambiguous) outcome).candidates().get(0).score())
                .as("a candidate score is a fused score, not a probability this fit applies to")
                .isEqualTo(0.050);
        }

        @Test
        @DisplayName("a bare temperature is enough, because that is all a node configures")
        void bareTemperatureIsEnough() {
            List<FusedDocument> fused = List.of(doc(1, 0.05, 2), doc(2, 0.01, 1));
            Subgraph evidence = subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3);

            double fromFit = ((RetrievalOutcome.Results) AbstentionCalibrator
                .toPolicy(delegate, softening).decide(fused, evidence)).confidence();
            double fromTemperature = ((RetrievalOutcome.Results)
                new CalibratedAbstentionPolicy(delegate, softening.temperature())
                    .decide(fused, evidence)).confidence();

            assertThat(fromTemperature).isEqualTo(fromFit);
        }

        @Test
        @DisplayName("the neutral temperature is the identity, so opting out costs nothing")
        void neutralTemperatureIsIdentity() {
            List<FusedDocument> fused = List.of(doc(1, 0.05, 2), doc(2, 0.01, 1));
            Subgraph evidence = subgraphWith(SourceType.KNOWLEDGE_BASE, 95, 3);

            double raw = ((RetrievalOutcome.Results) delegate.decide(fused, evidence)).confidence();
            double neutral = ((RetrievalOutcome.Results) new CalibratedAbstentionPolicy(
                delegate, CalibratedAbstentionPolicy.NEUTRAL_TEMPERATURE)
                .decide(fused, evidence)).confidence();

            assertThat(neutral).isCloseTo(raw, within(1e-12));
        }

        @Test
        @DisplayName("a temperature that is not a positive number is refused")
        void impossibleTemperatureIsRefused() {
            assertThatThrownBy(() -> new CalibratedAbstentionPolicy(delegate, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
            assertThatThrownBy(() -> new CalibratedAbstentionPolicy(delegate, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CalibratedAbstentionPolicy(delegate, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                () -> new CalibratedAbstentionPolicy(delegate, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("stacking two temperatures is refused")
        void stackingIsRefused() {
            AbstentionPolicy once = AbstentionCalibrator.toPolicy(delegate, softening);

            assertThatThrownBy(() -> AbstentionCalibrator.toPolicy(once, softening))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already calibrated");
        }
    }
}
