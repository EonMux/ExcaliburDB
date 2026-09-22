package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests what the validators tell a person when a measurement is questionable.
 *
 * <p>Each validator separates errors from warnings: an error means the number cannot
 * be believed, a warning means it can but is worth a second look. The summaries and
 * combined messages are how that distinction reaches whoever reads the run, so a
 * warning rendered as a pass, or an error swallowed into one, is the failure these
 * tests guard against.</p>
 */
class ValidatorReportingTest {

    @Nested
    @DisplayName("Throughput")
    class Throughput {

        @Test
        @DisplayName("a summary states the throughput, its scale and its verdict")
        void summaryStatesTheVerdict() {
            String summary = ThroughputValidator.formatSummary(5_000_000.0);

            assertThat(summary).contains("Throughput Validation:");
            assertThat(summary).contains("throughput: 5.00M ops/sec");
            assertThat(summary).contains("status: VALID");
        }

        @Test
        @DisplayName("a summary flags an implausible throughput as a warning, not a pass")
        void summaryFlagsAnImplausibleRate() {
            // Above the physical plausibility ceiling: still a number, still suspect.
            String summary = ThroughputValidator.formatSummary(5e12);

            assertThat(summary).contains("status: VALID (with warnings)");
            assertThat(summary).contains("warnings:");
        }

        @Test
        @DisplayName("an invalid throughput is reported as invalid")
        void summaryReportsAnInvalidRate() {
            assertThat(ThroughputValidator.formatSummary(Double.NaN)).contains("status: INVALID");
        }

        @Test
        @DisplayName("a targeted summary says whether the target was met")
        void targetedSummaryStatesWhetherTheTargetWasMet() {
            assertThat(ThroughputValidator.formatSummaryWithTarget(2e6, 1e6))
                .contains("target: 1.00M ops/sec").contains("meets target: YES");
            assertThat(ThroughputValidator.formatSummaryWithTarget(5e5, 1e6))
                .contains("meets target: NO");
        }

        @Test
        @DisplayName("a targeted summary of an invalid measurement lists the errors")
        void targetedSummaryListsErrors() {
            String summary = ThroughputValidator.formatSummaryWithTarget(-1.0, 1e6);

            assertThat(summary).contains("status: INVALID").contains("errors:");
        }

        @Test
        @DisplayName("scale suffixes follow the magnitude")
        void scaleSuffixesFollowMagnitude() {
            assertThat(ThroughputValidator.format(2.5e9)).isEqualTo("2.50B ops/sec");
            assertThat(ThroughputValidator.format(2.5e6)).isEqualTo("2.50M ops/sec");
            assertThat(ThroughputValidator.format(2.5e3)).isEqualTo("2.50K ops/sec");
            assertThat(ThroughputValidator.format(2.5)).isEqualTo("2.50 ops/sec");
            assertThat(ThroughputValidator.format(Double.NaN)).isEqualTo("INVALID");
        }

        @Test
        @DisplayName("a met target reads as a pass; a missed one names the gap")
        void combinedMessageDistinguishesTargetOutcome() {
            assertThat(ThroughputValidator.validateWithTarget(2e6, 1e6).combinedMessage())
                .isEqualTo("Validation passed - throughput meets target");
            assertThat(ThroughputValidator.validateWithTarget(5e5, 1e6).combinedMessage())
                .startsWith("Errors: ")
                .contains("below target")
                .contains("50.0% gap");
        }

        @Test
        @DisplayName("a combined message carries warnings, errors, or both")
        void combinedMessageCarriesBothKinds() {
            assertThat(ThroughputValidator.validate(5e12).combinedMessage())
                .startsWith("Warnings: ");
            assertThat(ThroughputValidator.validate(-1.0).combinedMessage())
                .startsWith("Errors: ");
        }

        @Test
        @DisplayName("a validated result can render its own throughput")
        void validationResultFormatsItsThroughput() {
            assertThat(ThroughputValidator.validate(2e6).throughputFormatted())
                .isEqualTo("2.00M ops/sec");
        }

        @Test
        @DisplayName("asserting a target throws only when the measurement itself is invalid")
        void assertingATargetRejectsInvalidMeasurements() {
            assertThatThrownBy(() -> ThroughputValidator.assertValidAndMeetsTarget(-1.0, 1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Throughput validation failed");
            ThroughputValidator.assertValidAndMeetsTarget(2e6, 1e6);
        }
    }

    @Nested
    @DisplayName("Recall")
    class Recall {

        @Test
        @DisplayName("a summary renders the recall as both a decimal and a percentage")
        void summaryRendersBothForms() {
            String summary = RecallValidator.formatSummary(0.9923);

            assertThat(summary).contains("Recall Validation:");
            assertThat(summary).contains("recall: 0.9923 (99.23%)");
            assertThat(summary).contains("target: 0.9900 (99%)");
            assertThat(summary).contains("meets target: YES");
            assertThat(summary).contains("status: VALID");
        }

        @Test
        @DisplayName("a summary is line-broken rather than carrying a literal format directive")
        void summaryIsProperlyLineBroken() {
            String summary = RecallValidator.formatSummary(0.99);

            assertThat(summary).doesNotContain("%n");
            assertThat(summary.lines()).hasSizeGreaterThan(3);
        }

        @Test
        @DisplayName("a recall below target is reported as valid but unmet")
        void belowTargetIsValidButUnmet() {
            assertThat(RecallValidator.formatSummary(0.95))
                .contains("meets target: NO").contains("status: VALID");
        }

        @Test
        @DisplayName("a poor recall is valid but warned about")
        void poorRecallIsWarnedAbout() {
            String summary = RecallValidator.formatSummary(0.25);

            assertThat(summary).contains("status: VALID (with warnings)");
            assertThat(summary).contains("below 50%");
        }

        @Test
        @DisplayName("a recall outside [0,1] is an error, not a warning")
        void outOfRangeRecallIsAnError() {
            assertThat(RecallValidator.validateRange(1.5).valid()).isFalse();
            assertThat(RecallValidator.validateRange(1.5).errors()).contains("exceeds maximum");
            assertThat(RecallValidator.validateRange(-0.5).errors()).contains("below minimum");
            assertThat(RecallValidator.validateRange(Double.NaN).errors()).contains("NaN");
            assertThat(RecallValidator.validateRange(Double.POSITIVE_INFINITY).errors())
                .contains("infinite");
        }

        @Test
        @DisplayName("a recall a hair outside [0,1] is tolerated with a warning")
        void floatingPointOvershootIsTolerated() {
            RecallValidator.ValidationResult above = RecallValidator.validateRange(1.0 + 1e-12);
            RecallValidator.ValidationResult below = RecallValidator.validateRange(-1e-12);

            assertThat(above.valid()).isTrue();
            assertThat(above.warnings()).contains("slightly above 1.0");
            assertThat(below.valid()).isTrue();
            assertThat(below.warnings()).contains("slightly below 0.0");
        }

        @Test
        @DisplayName("recall@10 is judged against the 99% target, other k against 95%")
        void recallAtKUsesTheRightTarget() {
            assertThat(RecallValidator.validateRecallAtK(0.99, 10).meetsTarget()).isTrue();
            assertThat(RecallValidator.validateRecallAtK(0.96, 10).meetsTarget()).isFalse();
            assertThat(RecallValidator.validateRecallAtK(0.96, 100).meetsTarget()).isTrue();
            assertThat(RecallValidator.validateRecallAtK(0.90, 100).meetsTarget()).isFalse();
        }

        @Test
        @DisplayName("an out-of-range recall@k is invalid and meets nothing")
        void outOfRangeRecallAtKIsInvalid() {
            RecallValidator.ValidationResult result = RecallValidator.validateRecallAtK(2.0, 10);

            assertThat(result.valid()).isFalse();
            assertThat(result.meetsTarget()).isFalse();
        }

        @Test
        @DisplayName("the recall@k summary names k in its target line")
        void recallAtKSummaryNamesK() {
            assertThat(RecallValidator.formatSummaryAtK(0.97, 100))
                .contains("Recall@100 Validation:")
                .contains("recall@100: 0.9700")
                .contains("target: 0.9500");
            assertThat(RecallValidator.formatSummaryAtK(2.0, 10)).contains("status: INVALID");
            assertThat(RecallValidator.formatSummaryAtK(0.2, 10))
                .contains("status: VALID (with warnings)");
        }

        @Test
        @DisplayName("a met target reads as a pass; a missed one is a warning naming both numbers")
        void combinedMessageDistinguishesTargetOutcome() {
            assertThat(RecallValidator.validateWithTarget(0.99).combinedMessage())
                .isEqualTo("Validation passed - recall meets target");
            assertThat(RecallValidator.validateWithTarget(0.80).combinedMessage())
                .startsWith("Warnings: ")
                .contains("0.8000")
                .contains("0.9900");
            assertThat(RecallValidator.validateRange(2.0).combinedMessage()).startsWith("Errors: ");
        }

        @Test
        @DisplayName("asserting the target rejects both an invalid and a below-target recall")
        void assertingTheTargetRejectsBothFailures() {
            assertThatThrownBy(() -> RecallValidator.assertValidAndMeetsTarget(2.0))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> RecallValidator.assertValidAndMeetsTarget(0.5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not meet target");
            RecallValidator.assertValidAndMeetsTarget(0.995);
        }
    }

    @Nested
    @DisplayName("False positive rate")
    class FalsePositiveRate {

        @Test
        @DisplayName("a summary states the rate against the 0.1% specification")
        void summaryStatesTheSpecification() {
            String summary = FPRateValidator.formatSummary(0.0005);

            assertThat(summary).contains("FP Rate Validation:");
            assertThat(summary).contains("spec target: 0.1000%");
            assertThat(summary).contains("status: VALID - meets spec");
        }

        @Test
        @DisplayName("a rate above the specification is reported as failing it")
        void aboveSpecFailsTheSummary() {
            String summary = FPRateValidator.formatSummary(0.05);

            assertThat(summary).contains("status: INVALID - fails spec");
            assertThat(summary).contains("errors:");
        }

        @Test
        @DisplayName("a targeted summary states the target and whether it was met")
        void targetedSummaryStatesTheTarget() {
            assertThat(FPRateValidator.formatSummaryWithTarget(0.0005, 0.001))
                .contains("target: 0.1000%").contains("meets target: YES")
                .contains("status: VALID");
            assertThat(FPRateValidator.formatSummaryWithTarget(0.002, 0.001))
                .contains("meets target: NO");
        }

        @Test
        @DisplayName("a targeted summary of an invalid rate lists the errors")
        void targetedSummaryListsErrors() {
            assertThat(FPRateValidator.formatSummaryWithTarget(-1.0, 0.001))
                .contains("status: INVALID").contains("errors:");
        }

        @Test
        @DisplayName("a combined message says whether a valid rate met the specification")
        void combinedMessageDistinguishesSpecOutcome() {
            assertThat(FPRateValidator.validateWithTarget(0.0005, 0.001).combinedMessage())
                .contains("meets specification");
            assertThat(FPRateValidator.validateWithTarget(0.002, 0.001).combinedMessage())
                .startsWith("Errors: ")
                .contains("exceeds target");
            assertThat(FPRateValidator.validate(-1.0).combinedMessage()).startsWith("Errors: ");
        }

        @Test
        @DisplayName("a rate exactly at the target meets it")
        void rateAtTheTargetMeetsIt() {
            assertThat(FPRateValidator.meetsTarget(0.001, 0.001)).isTrue();
            assertThat(FPRateValidator.meetsTarget(0.0011, 0.001)).isFalse();
        }

        @Test
        @DisplayName("a rate renders with enough precision to see a tenth of a basis point")
        void precisionIsEnoughToSeeTheSpec() {
            assertThat(FPRateValidator.formatPrecise(0.000123)).contains("0.0123");
            assertThat(FPRateValidator.format(0.001)).isEqualTo("0.1000%");
        }
    }

    @Nested
    @DisplayName("Metric units")
    class Units {

        @Test
        @DisplayName("each dimension has a canonical unit")
        void everyDimensionHasACanonicalUnit() {
            assertThat(MetricUnits.canonicalUnit(MetricUnits.Dimension.THROUGHPUT))
                .isEqualTo(MetricUnits.OPS_PER_SEC);
            assertThat(MetricUnits.canonicalUnit(MetricUnits.Dimension.TIME_PER_OP))
                .isEqualTo(MetricUnits.MS_PER_OP);
            assertThat(MetricUnits.canonicalUnit(MetricUnits.Dimension.BYTES_PER_SEC))
                .isEqualTo(MetricUnits.BYTES_PER_SEC);
            assertThat(MetricUnits.canonicalUnit(MetricUnits.Dimension.DIMENSIONLESS))
                .isEqualTo(MetricUnits.RATIO);
            assertThat(MetricUnits.canonicalUnit(MetricUnits.Dimension.UNKNOWN))
                .isEqualTo("unknown");
        }

        @Test
        @DisplayName("conversion scales across every dimension it recognises")
        void conversionScalesWithinEachDimension() {
            assertThat(MetricUnits.convert(1.0, "ops/ms", "ops/sec")).isEqualTo(1_000.0);
            assertThat(MetricUnits.convert(1.0, "ops/us", "ops/sec")).isEqualTo(1_000_000.0);
            assertThat(MetricUnits.convert(1.0, "ops/ns", "ops/sec")).isEqualTo(1e9);
            assertThat(MetricUnits.convert(60.0, "ops/min", "ops/sec")).isEqualTo(1.0);
            assertThat(MetricUnits.convert(1.0, "s/op", "ms/op")).isEqualTo(1_000.0);
            assertThat(MetricUnits.convert(1_000.0, "ns/op", "us/op")).isEqualTo(1.0);
            assertThat(MetricUnits.convert(1.0, "mb/sec", "bytes/sec")).isEqualTo(1_000_000.0);
            assertThat(MetricUnits.convert(1.0, "gb/sec", "kb/sec")).isEqualTo(1_000_000.0);
            assertThat(MetricUnits.convert(0.5, "recall", "ratio")).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("Metric")
    class Metric {

        @Test
        @DisplayName("a metric renders its error margin only when it has one")
        void errorMarginIsShownOnlyWhenPresent() {
            assertThat(new BenchmarkMetric("p50", 1.5, "ms", 0.25, 10).format())
                .isEqualTo("p50: 1.50 ± 0.25 ms");
            assertThat(new BenchmarkMetric("p50", 1.5, "ms").format()).isEqualTo("p50: 1.50 ms");
        }

        @Test
        @DisplayName("toString is the formatted metric")
        void toStringIsTheFormattedMetric() {
            BenchmarkMetric metric = new BenchmarkMetric("p50", 1.5, "ms");

            assertThat(metric).hasToString(metric.format());
        }

        @Test
        @DisplayName("the named factories set the name, unit and sample count they imply")
        void factoriesSetTheirOwnNamesAndUnits() {
            assertThat(BenchmarkMetric.latencyPercentileNs(99, 1_500.0, 1.0))
                .satisfies(m -> {
                    assertThat(m.name()).isEqualTo("p99_ns");
                    assertThat(m.unit()).isEqualTo("ns");
                    assertThat(m.error()).isEqualTo(1.0);
                });
            assertThat(BenchmarkMetric.latencyPercentile(50, 1.5, 0.0).name()).isEqualTo("p50");
            assertThat(BenchmarkMetric.score("recall", 0.99, 0.0))
                .satisfies(m -> assertThat(m.unit()).isEqualTo("score"));
            assertThat(BenchmarkMetric.count("queries", 42))
                .satisfies(m -> {
                    assertThat(m.unit()).isEqualTo("count");
                    assertThat(m.value()).isEqualTo(42.0);
                    assertThat(m.sampleCount()).isEqualTo(42);
                });
        }

        @Test
        @DisplayName("a metric knows whether it meets a threshold in either direction")
        void thresholdComparisonRespectsDirection() {
            BenchmarkMetric metric = new BenchmarkMetric("x", 10.0, "ms");

            assertThat(metric.meetsTarget(10.0, true)).isTrue();
            assertThat(metric.meetsTarget(11.0, true)).isFalse();
            assertThat(metric.meetsTarget(10.0, false)).isTrue();
            assertThat(metric.meetsTarget(9.0, false)).isFalse();
        }

        @Test
        @DisplayName("a non-positive throughput metric is refused at construction")
        void throughputMustBePositive() {
            assertThatThrownBy(() -> BenchmarkMetric.throughput(0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        }
    }
}
