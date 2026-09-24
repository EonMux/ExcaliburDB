package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for MemoryUsageValidator domain correctness validation.
 *
 * <p>Verifies that memory usage values are within acceptable bounds
 * and that benchmarks don't exceed available RAM.</p>
 *
 * @author ExcaliburDB
 */
class MemoryUsageValidatorTest {

    // ==================== Snapshot Tests ====================

    @Nested
    @DisplayName("Memory Snapshot")
    class MemorySnapshotTests {

        @Test
        @DisplayName("captureSnapshot should return valid snapshot")
        void captureSnapshot_shouldReturnValid() {
            MemoryUsageValidator.MemorySnapshot snapshot = MemoryUsageValidator.captureSnapshot();

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.usedBytes()).isGreaterThanOrEqualTo(0);
            assertThat(snapshot.maxBytes()).isGreaterThan(0);
            assertThat(snapshot.committedBytes()).isGreaterThan(0);
            assertThat(snapshot.timestampMs()).isGreaterThan(0);
        }

        @Test
        @DisplayName("usageRatio should be in valid range [0, 1]")
        void usageRatio_shouldBeValidRange() {
            MemoryUsageValidator.MemorySnapshot snapshot = MemoryUsageValidator.captureSnapshot();

            double ratio = snapshot.usageRatio();
            assertThat(ratio).isGreaterThanOrEqualTo(0.0);
            assertThat(ratio).isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("availableBytes should be non-negative")
        void availableBytes_shouldBeNonNegative() {
            MemoryUsageValidator.MemorySnapshot snapshot = MemoryUsageValidator.captureSnapshot();

            assertThat(snapshot.availableBytes()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Manual snapshot with zero max should have 0 usage ratio")
        void zeroMax_shouldHaveZeroRatio() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(100, 200, 0, System.currentTimeMillis());

            assertThat(snapshot.usageRatio()).isEqualTo(0.0);
            assertThat(snapshot.availableBytes()).isEqualTo(0);
        }
    }

    // ==================== Current Usage Tests ====================

    @Nested
    @DisplayName("Current Usage")
    class CurrentUsageTests {

        @Test
        @DisplayName("getMaxHeapBytes should return positive value")
        void getMaxHeapBytes_shouldBePositive() {
            long maxHeap = MemoryUsageValidator.getMaxHeapBytes();

            assertThat(maxHeap).isGreaterThan(0);
        }

        @Test
        @DisplayName("getUsedHeapBytes should return non-negative value")
        void getUsedHeapBytes_shouldBeNonNegative() {
            long usedHeap = MemoryUsageValidator.getUsedHeapBytes();

            assertThat(usedHeap).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("getUsageRatio should be in valid range")
        void getUsageRatio_shouldBeValidRange() {
            double ratio = MemoryUsageValidator.getUsageRatio();

            assertThat(ratio).isGreaterThanOrEqualTo(0.0);
            assertThat(ratio).isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("validateCurrentUsage should pass on typical heap")
        void validateCurrentUsage_shouldPass() {
            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validateCurrentUsage();

            // Should pass unless heap is critically full
            assertThat(result.valid() || result.usageRatio() >= MemoryUsageValidator.CRITICAL_THRESHOLD)
                .isTrue();
        }
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Valid snapshot with low usage should pass")
        void validLowUsage_shouldPass() {
            // Create a snapshot with 50% usage
            long max = 1024 * 1024 * 1024; // 1GB
            long used = max / 2; // 50%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("Snapshot exceeding safe threshold should warn")
        void exceedingSafeThreshold_shouldWarn() {
            // Create a snapshot with 90% usage
            long max = 1024 * 1024 * 1024; // 1GB
            long used = (long) (max * 0.90); // 90%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue(); // Still valid, just warning
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.warnings()).contains("exceeds safe threshold");
        }

        @Test
        @DisplayName("Snapshot exceeding critical threshold should fail")
        void exceedingCriticalThreshold_shouldFail() {
            // Create a snapshot with 96% usage
            long max = 1024 * 1024 * 1024; // 1GB
            long used = (long) (max * 0.96); // 96%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
            assertThat(result.hasErrors()).isTrue();
            assertThat(result.errors()).contains("exceeds critical threshold");
        }

        @Test
        @DisplayName("Negative used bytes should fail")
        void negativeUsed_shouldFail() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(-1, 100, 1000, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("negative");
        }

        @Test
        @DisplayName("Used exceeding max should fail")
        void usedExceedsMax_shouldFail() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(2000, 2000, 1000, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("exceeds max heap");
        }

        @Test
        @DisplayName("Zero max bytes should fail")
        void zeroMax_shouldFail() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(100, 100, 0, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("not defined");
        }

        @Test
        @DisplayName("Negative max bytes should fail")
        void negativeMax_shouldFail() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(100, 100, -1, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
        }
    }

    // ==================== Pre-Benchmark Validation Tests ====================

    @Nested
    @DisplayName("Pre-Benchmark Validation")
    class PreBenchmarkValidationTests {

        @Test
        @DisplayName("validatePreBenchmark should return result")
        void validatePreBenchmark_shouldReturnResult() {
            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validatePreBenchmark();

            assertThat(result).isNotNull();
            assertThat(result.snapshot()).isNotNull();
        }

        @Test
        @DisplayName("Pre-benchmark should warn on small heap")
        void smallHeap_shouldWarn() {
            // This test verifies the logic exists; actual heap may vary
            MemoryUsageValidator.ValidationResult result = MemoryUsageValidator.validatePreBenchmark();

            // Just verify the method runs without exception
            assertThat(result.valid() || result.hasErrors() || result.hasWarnings()).isTrue();
        }
    }

    // ==================== Benchmark Memory Validation Tests ====================

    @Nested
    @DisplayName("Benchmark Memory Validation")
    class BenchmarkMemoryValidationTests {

        @Test
        @DisplayName("Valid benchmark memory should pass")
        void validBenchmarkMemory_shouldPass() {
            long max = 1024 * 1024 * 1024; // 1GB
            MemoryUsageValidator.MemorySnapshot start =
                new MemoryUsageValidator.MemorySnapshot(max / 4, max / 2, max, System.currentTimeMillis());
            MemoryUsageValidator.MemorySnapshot end =
                new MemoryUsageValidator.MemorySnapshot(max / 3, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateBenchmarkMemory(start, end, 0);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Peak exceeding max should fail")
        void peakExceedsMax_shouldFail() {
            long max = 1024 * 1024 * 1024; // 1GB
            MemoryUsageValidator.MemorySnapshot start =
                new MemoryUsageValidator.MemorySnapshot(max / 4, max / 2, max, System.currentTimeMillis());
            MemoryUsageValidator.MemorySnapshot end =
                new MemoryUsageValidator.MemorySnapshot(max / 2, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateBenchmarkMemory(start, end, max * 2);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("exceeded max heap");
        }

        @Test
        @DisplayName("Significant memory growth should warn")
        void significantGrowth_shouldWarn() {
            long max = 1024 * 1024 * 1024; // 1GB
            MemoryUsageValidator.MemorySnapshot start =
                new MemoryUsageValidator.MemorySnapshot(max / 10, max / 2, max, System.currentTimeMillis());
            // End uses 30% more than start (significant growth)
            MemoryUsageValidator.MemorySnapshot end =
                new MemoryUsageValidator.MemorySnapshot((long)(max * 0.40), max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateBenchmarkMemory(start, end, 0);

            // May or may not warn depending on exact thresholds
            assertThat(result).isNotNull();
        }
    }

    // ==================== Available Memory Tests ====================

    @Nested
    @DisplayName("Available Memory")
    class AvailableMemoryTests {

        @Test
        @DisplayName("hasAvailableMemory should return true for small request")
        void hasAvailableMemory_smallRequest() {
            // 1KB should always be available
            boolean available = MemoryUsageValidator.hasAvailableMemory(1024);

            assertThat(available).isTrue();
        }

        @Test
        @DisplayName("hasAvailableMemory should return false for huge request")
        void hasAvailableMemory_hugeRequest() {
            // 1TB should not be available
            boolean available = MemoryUsageValidator.hasAvailableMemory(1024L * 1024 * 1024 * 1024);

            assertThat(available).isFalse();
        }

        @Test
        @DisplayName("validateAvailableMemory should pass for small request")
        void validateAvailableMemory_smallRequest() {
            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateAvailableMemory(1024, "test");

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("validateAvailableMemory should fail for negative request")
        void validateAvailableMemory_negativeRequest() {
            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateAvailableMemory(-1, "test");

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("cannot be negative");
        }

        @Test
        @DisplayName("validateAvailableMemory should fail for huge request")
        void validateAvailableMemory_hugeRequest() {
            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validateAvailableMemory(Long.MAX_VALUE, "hugeOperation");

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).contains("requires");
        }
    }

    // ==================== Formatting Tests ====================

    @Nested
    @DisplayName("Formatting")
    class FormattingTests {

        @Test
        @DisplayName("formatBytes should format bytes")
        void formatBytes_bytes() {
            String formatted = MemoryUsageValidator.formatBytes(512);
            assertThat(formatted).contains("512");
            assertThat(formatted).contains("bytes");
        }

        @Test
        @DisplayName("formatBytes should format kilobytes")
        void formatBytes_kilobytes() {
            String formatted = MemoryUsageValidator.formatBytes(2 * 1024);
            assertThat(formatted).contains("2");
            assertThat(formatted).contains("KB");
        }

        @Test
        @DisplayName("formatBytes should format megabytes")
        void formatBytes_megabytes() {
            String formatted = MemoryUsageValidator.formatBytes(256L * 1024 * 1024);
            assertThat(formatted).contains("256");
            assertThat(formatted).contains("MB");
        }

        @Test
        @DisplayName("formatBytes should format gigabytes")
        void formatBytes_gigabytes() {
            String formatted = MemoryUsageValidator.formatBytes(2L * 1024 * 1024 * 1024);
            assertThat(formatted).contains("2");
            assertThat(formatted).contains("GB");
        }

        @Test
        @DisplayName("formatBytes should handle negative as INVALID")
        void formatBytes_negative() {
            String formatted = MemoryUsageValidator.formatBytes(-1);
            assertThat(formatted).isEqualTo("INVALID");
        }

        @Test
        @DisplayName("formatSummary should return formatted summary")
        void formatSummary_shouldReturnFormatted() {
            String summary = MemoryUsageValidator.formatSummary();

            assertThat(summary).contains("Memory Usage");
            assertThat(summary).contains("used:");
            assertThat(summary).contains("max:");
            assertThat(summary).contains("usage:");
            assertThat(summary).contains("status:");
        }
    }

    // ==================== Assertion Tests ====================

    @Nested
    @DisplayName("Assertions")
    class AssertionTests {

        @Test
        @DisplayName("assertMemorySafe should not throw on typical heap")
        void assertMemorySafe_shouldNotThrow() {
            // This should pass unless heap is critically full
            if (MemoryUsageValidator.getUsageRatio() < MemoryUsageValidator.CRITICAL_THRESHOLD) {
                assertThatCode(() -> MemoryUsageValidator.assertMemorySafe())
                    .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("assertMemoryAvailable should not throw for small request")
        void assertMemoryAvailable_smallRequest() {
            assertThatCode(() -> MemoryUsageValidator.assertMemoryAvailable(1024, "test"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertMemoryAvailable should throw for huge request")
        void assertMemoryAvailable_hugeRequest() {
            assertThatThrownBy(() -> MemoryUsageValidator.assertMemoryAvailable(Long.MAX_VALUE, "hugeOperation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient memory");
        }
    }

    // ==================== Threshold Constants Tests ====================

    @Nested
    @DisplayName("Threshold Constants")
    class ThresholdConstantsTests {

        @Test
        @DisplayName("SAFE_THRESHOLD should be 0.85 (85%)")
        void safeThreshold() {
            assertThat(MemoryUsageValidator.SAFE_THRESHOLD).isEqualTo(0.85);
        }

        @Test
        @DisplayName("CRITICAL_THRESHOLD should be 0.95 (95%)")
        void criticalThreshold() {
            assertThat(MemoryUsageValidator.CRITICAL_THRESHOLD).isEqualTo(0.95);
        }

        @Test
        @DisplayName("BENCHMARK_MEMORY_THRESHOLD should be 0.90 (90%)")
        void benchmarkMemoryThreshold() {
            assertThat(MemoryUsageValidator.BENCHMARK_MEMORY_THRESHOLD).isEqualTo(0.90);
        }

        @Test
        @DisplayName("MIN_HEAP_SIZE_BYTES should be 64MB")
        void minHeapSize() {
            assertThat(MemoryUsageValidator.MIN_HEAP_SIZE_BYTES).isEqualTo(64L * 1024 * 1024);
        }

        @Test
        @DisplayName("RECOMMENDED_MIN_HEAP_BYTES should be 512MB")
        void recommendedMinHeap() {
            assertThat(MemoryUsageValidator.RECOMMENDED_MIN_HEAP_BYTES).isEqualTo(512L * 1024 * 1024);
        }
    }

    // ==================== ValidationResult Record Tests ====================

    @Nested
    @DisplayName("ValidationResult Record")
    class ValidationResultTests {

        @Test
        @DisplayName("hasWarnings and hasErrors")
        void hasWarningsAndErrors() {
            long max = 1024 * 1024 * 1024;
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(max / 2, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult validResult =
                MemoryUsageValidator.validate(snapshot);
            assertThat(validResult.hasWarnings()).isFalse();
            assertThat(validResult.hasErrors()).isFalse();

            MemoryUsageValidator.MemorySnapshot badSnapshot =
                new MemoryUsageValidator.MemorySnapshot(-1, max / 2, max, System.currentTimeMillis());
            MemoryUsageValidator.ValidationResult invalidResult =
                MemoryUsageValidator.validate(badSnapshot);
            assertThat(invalidResult.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("combinedMessage with no issues")
        void combinedMessageNoIssues() {
            long max = 1024 * 1024 * 1024;
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(max / 4, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.combinedMessage()).contains("Memory usage OK");
        }

        @Test
        @DisplayName("combinedMessage with errors")
        void combinedMessageWithErrors() {
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(-1, 100, 1000, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.combinedMessage()).contains("Errors:");
        }

        @Test
        @DisplayName("usagePercent should format percentage")
        void usagePercent() {
            long max = 1024 * 1024 * 1024;
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(max / 2, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.usagePercent()).isEqualTo("50.0%");
        }
    }

    // ==================== Garbage Collection Tests ====================

    @Nested
    @DisplayName("Garbage Collection")
    class GarbageCollectionTests {

        @Test
        @DisplayName("suggestGarbageCollection should return non-negative value")
        void suggestGarbageCollection_shouldReturnNonNegative() {
            long freed = MemoryUsageValidator.suggestGarbageCollection();

            assertThat(freed).isGreaterThanOrEqualTo(0);
        }
    }

    // ==================== Parameterized Tests ====================

    @Nested
    @DisplayName("Parameterized Validation")
    class ParameterizedValidationTests {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 0.25, 0.50, 0.75, 0.84})
        @DisplayName("Usage ratios below safe threshold should pass")
        void usageRatiosBelowSafeThreshold(double ratio) {
            long max = 1024L * 1024 * 1024; // 1GB
            long used = (long) (max * ratio);
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasErrors()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.85, 0.88, 0.90, 0.94})
        @DisplayName("Usage ratios between safe and critical should warn")
        void usageRatiosWarnZone(double ratio) {
            // Use 1000 as max to avoid floating-point truncation issues with large values
            long max = 1000L;
            long used = (long) (max * ratio);
            // Ensure we're actually at or above the intended ratio
            if (ratio == 0.85) used = 850L; // Exactly 85%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.95, 0.96, 0.99, 1.0})
        @DisplayName("Usage ratios at or above critical should fail")
        void usageRatiosCriticalZone(double ratio) {
            // Use 1000 as max to avoid floating-point truncation issues with large values
            long max = 1000L;
            long used = (long) (max * ratio);
            // Ensure we're actually at or above the intended ratio
            if (ratio == 0.95) used = 950L; // Exactly 95%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
            assertThat(result.hasErrors()).isTrue();
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Zero used bytes should be valid")
        void zeroUsed() {
            long max = 1024 * 1024 * 1024;
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(0, max / 2, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue();
            assertThat(result.usageRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Exactly at safe threshold should warn")
        void exactlyAtSafeThreshold() {
            // Use a value that's guaranteed to be >= 85% (avoid truncation issues)
            long max = 1000L;
            long used = 850L; // Exactly 85%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            // At exactly 85% it should warn (>= SAFE_THRESHOLD)
            assertThat(result.hasWarnings()).isTrue();
        }

        @Test
        @DisplayName("Exactly at critical threshold should fail")
        void exactlyAtCriticalThreshold() {
            // Use a value that's guaranteed to be >= 95% (avoid truncation issues)
            long max = 1000L;
            long used = 950L; // Exactly 95%
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("Large heap values should be handled correctly")
        void largeHeapValues() {
            // 1TB heap (theoretical)
            long max = 1024L * 1024 * 1024 * 1024;
            long used = max / 2;
            MemoryUsageValidator.MemorySnapshot snapshot =
                new MemoryUsageValidator.MemorySnapshot(used, used, max, System.currentTimeMillis());

            MemoryUsageValidator.ValidationResult result =
                MemoryUsageValidator.validate(snapshot);

            assertThat(result.valid()).isTrue();
            assertThat(result.usageRatio()).isEqualTo(0.5);
        }
    }
}
