package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for unit recognition and conversion.
 *
 * <p>The expectations here are arithmetic facts, not values read back from the class
 * under test: 1 ops/ms is 1000 ops/sec, 1 ns/op is 1e-6 ms/op, and a throughput can
 * never be compared against a recall.</p>
 *
 * @author ExcaliburDB
 */
class MetricUnitsTest {

    @Nested
    @DisplayName("Dimension recognition")
    class DimensionRecognition {

        @Test
        @DisplayName("JMH's own throughput unit is recognised")
        void jmhThroughputUnit() {
            // JMH writes "ops/s". The previous predicate tested for "ops/sec" and
            // "op/s", neither of which is a substring of it, so no throughput metric
            // was ever attached to a captured result.
            assertThat("ops/s".contains("ops/sec")).isFalse();
            assertThat("ops/s".contains("op/s")).isFalse();

            assertThat(MetricUnits.dimensionOf("ops/s"))
                .isEqualTo(MetricUnits.Dimension.THROUGHPUT);
        }

        @Test
        @DisplayName("Counts of work items per second are throughputs")
        void workItemRatesAreThroughputs() {
            for (String unit : new String[]{MetricUnits.COMPONENTS_PER_SEC, MetricUnits.ENTRIES_PER_SEC}) {
                assertThat(MetricUnits.dimensionOf(unit)).isEqualTo(MetricUnits.Dimension.THROUGHPUT);
                assertThat(MetricUnits.convert(5.0, unit, MetricUnits.OPS_PER_SEC)).isEqualTo(5.0);
            }
        }

        @Test
        @DisplayName("Time-per-operation units are recognised")
        void timePerOperationUnits() {
            for (String unit : new String[]{"ns/op", "us/op", "ms/op", "s/op", "ms", "ns"}) {
                assertThat(MetricUnits.dimensionOf(unit))
                    .as("unit %s", unit)
                    .isEqualTo(MetricUnits.Dimension.TIME_PER_OP);
            }
        }

        @Test
        @DisplayName("Ratio units are recognised")
        void ratioUnits() {
            for (String unit : new String[]{"rate", "recall", "ratio", "score"}) {
                assertThat(MetricUnits.dimensionOf(unit))
                    .as("unit %s", unit)
                    .isEqualTo(MetricUnits.Dimension.DIMENSIONLESS);
            }
        }

        @Test
        @DisplayName("Unrecognised units are UNKNOWN, not guessed at")
        void unknownUnits() {
            assertThat(MetricUnits.dimensionOf("widgets/fortnight"))
                .isEqualTo(MetricUnits.Dimension.UNKNOWN);
            assertThat(MetricUnits.dimensionOf(null))
                .isEqualTo(MetricUnits.Dimension.UNKNOWN);
            assertThat(MetricUnits.dimensionOf(""))
                .isEqualTo(MetricUnits.Dimension.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("Compatibility")
    class Compatibility {

        @Test
        @DisplayName("Units of the same dimension are compatible")
        void sameDimensionIsCompatible() {
            assertThat(MetricUnits.isCompatible("ops/s", "ops/sec")).isTrue();
            assertThat(MetricUnits.isCompatible("ns/op", "ms")).isTrue();
        }

        @Test
        @DisplayName("A latency is never comparable with a recall or a throughput")
        void differentDimensionsAreIncompatible() {
            assertThat(MetricUnits.isCompatible("ms/op", "recall")).isFalse();
            assertThat(MetricUnits.isCompatible("ns/op", "rate")).isFalse();
            assertThat(MetricUnits.isCompatible("ms/op", "ops/sec")).isFalse();
            assertThat(MetricUnits.isCompatible("ops/s", "bytes/sec")).isFalse();
        }

        @Test
        @DisplayName("An unknown unit is compatible with nothing, including itself")
        void unknownIsCompatibleWithNothing() {
            assertThat(MetricUnits.isCompatible("widgets", "widgets")).isFalse();
            assertThat(MetricUnits.isCompatible("widgets", "ops/sec")).isFalse();
        }
    }

    @Nested
    @DisplayName("Conversion")
    class Conversion {

        @Test
        @DisplayName("Throughput conversions use the correct factors")
        void throughputConversions() {
            assertThat(MetricUnits.convert(1.0, "ops/ms", "ops/sec")).isEqualTo(1_000.0);
            assertThat(MetricUnits.convert(1.0, "ops/us", "ops/sec")).isEqualTo(1_000_000.0);
            assertThat(MetricUnits.convert(2_500_000.0, "ops/s", "ops/sec")).isEqualTo(2_500_000.0);
        }

        @Test
        @DisplayName("Latency conversions use the correct factors")
        void latencyConversions() {
            assertThat(MetricUnits.convert(1_000_000.0, "ns/op", "ms")).isCloseTo(1.0, within(1e-9));
            assertThat(MetricUnits.convert(1.0, "s/op", "ms/op")).isEqualTo(1_000.0);
            assertThat(MetricUnits.convert(2.5, "ms/op", "ms")).isEqualTo(2.5);
        }

        @Test
        @DisplayName("Conversion across dimensions is refused, never guessed")
        void crossDimensionConversionThrows() {
            assertThatThrownBy(() -> MetricUnits.convert(12.0, "ms/op", "recall"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot convert");

            assertThatThrownBy(() -> MetricUnits.convert(1.0, "widgets", "ops/sec"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Optimisation direction")
    class OptimisationDirection {

        @Test
        @DisplayName("Higher is better for throughput and transfer rates")
        void higherIsBetterForRates() {
            assertThat(MetricUnits.higherIsBetter("ops/s")).isTrue();
            assertThat(MetricUnits.higherIsBetter("bytes/sec")).isTrue();
        }

        @Test
        @DisplayName("Lower is better for latency")
        void lowerIsBetterForLatency() {
            assertThat(MetricUnits.higherIsBetter("ns/op")).isFalse();
            assertThat(MetricUnits.higherIsBetter("ms")).isFalse();
        }

        @Test
        @DisplayName("Direction cannot be derived from a dimensionless unit")
        void dimensionlessHasNoDirection() {
            assertThatThrownBy(() -> MetricUnits.higherIsBetter("recall"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
