package com.eonmux.excalibur.bench.metric;

import com.eonmux.excalibur.bench.metric.MemoryUsageValidator.MemorySnapshot;
import com.eonmux.excalibur.bench.metric.MemoryUsageValidator.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the memory validator against synthesised snapshots.
 *
 * <p>Snapshots taken from the live heap can only exercise whichever state the test
 * JVM happens to be in, so the states that matter - a heap about to run out, a
 * committed figure that contradicts the used one - are constructed here instead. The
 * distinction under test is which of them merely deserve a warning and which mean the
 * measurement cannot be trusted.</p>
 */
class MemorySnapshotReportingTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final long MB = 1024L * 1024;

    @Test
    @DisplayName("a roomy heap validates with nothing to report")
    void aRoomyHeapIsClean() {
        ValidationResult result = MemoryUsageValidator.validate(snapshot(1 * GB, 2 * GB, 8 * GB));

        assertThat(result.valid()).isTrue();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.combinedMessage()).startsWith("Memory usage OK");
        assertThat(result.usagePercent()).isEqualTo("12.5%");
    }

    @Test
    @DisplayName("usage past the safe threshold warns but stays valid")
    void pastTheSafeThresholdWarns() {
        ValidationResult result = MemoryUsageValidator.validate(snapshot(7 * GB, 8 * GB, 8 * GB));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("exceeds safe threshold");
        assertThat(result.combinedMessage()).startsWith("Warnings: ");
    }

    @Test
    @DisplayName("usage past the benchmark threshold warns that the results are unreliable")
    void pastTheBenchmarkThresholdWarnsAboutReliability() {
        // 92% used: still short of the critical 95%, but past the 90% at which GC
        // pressure starts showing up in the measurement itself.
        ValidationResult result = MemoryUsageValidator.validate(
            snapshot(9_200 * MB, 10_000 * MB, 10_000 * MB));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("results may be unreliable");
    }

    @Test
    @DisplayName("usage past the critical threshold is an error")
    void pastTheCriticalThresholdIsAnError() {
        ValidationResult result = MemoryUsageValidator.validate(
            snapshot(9_800 * MB, 10_000 * MB, 10_000 * MB));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("OOM risk");
        assertThat(result.combinedMessage()).startsWith("Errors: ").contains("; Warnings: ");
    }

    @Test
    @DisplayName("committed memory below used memory is flagged as an impossible state")
    void committedBelowUsedIsFlagged() {
        ValidationResult result = MemoryUsageValidator.validate(snapshot(4 * GB, 1 * GB, 8 * GB));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("unusual state");
    }

    @Test
    @DisplayName("committed memory above the heap limit is flagged")
    void committedAboveMaxIsFlagged() {
        ValidationResult result = MemoryUsageValidator.validate(snapshot(1 * GB, 9 * GB, 8 * GB));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("exceeds max heap");
    }

    @Test
    @DisplayName("a benchmark whose peak neared the limit is warned about")
    void peakNearTheLimitWarns() {
        ValidationResult result = MemoryUsageValidator.validateBenchmarkMemory(
            snapshot(1 * GB, 2 * GB, 8 * GB),
            snapshot(2 * GB, 4 * GB, 8 * GB),
            7 * GB);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("Peak memory usage").contains("safe threshold");
    }

    @Test
    @DisplayName("a benchmark whose peak reached the critical threshold fails")
    void peakAtTheCriticalThresholdFails() {
        ValidationResult result = MemoryUsageValidator.validateBenchmarkMemory(
            snapshot(1 * GB, 2 * GB, 8 * GB),
            snapshot(2 * GB, 4 * GB, 8 * GB),
            (long) (7.9 * GB));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("critical threshold");
    }

    @Test
    @DisplayName("an untracked peak falls back to the memory in use at the end")
    void untrackedPeakFallsBackToTheEndSnapshot() {
        ValidationResult result = MemoryUsageValidator.validateBenchmarkMemory(
            snapshot(1 * GB, 2 * GB, 8 * GB),
            snapshot(2 * GB, 4 * GB, 8 * GB),
            0);

        assertThat(result.valid()).isTrue();
        assertThat(result.usageRatio()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("an invalid end snapshot fails the benchmark check outright")
    void anInvalidEndSnapshotFailsImmediately() {
        ValidationResult result = MemoryUsageValidator.validateBenchmarkMemory(
            snapshot(1 * GB, 2 * GB, 8 * GB),
            snapshot(9 * GB, 9 * GB, 8 * GB),
            1 * GB);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("exceeds max heap");
    }

    @Test
    @DisplayName("a snapshot renders its used, max and usage in one line")
    void snapshotFormatsInOneLine() {
        MemorySnapshot snapshot = snapshot(1 * GB, 2 * GB, 4 * GB);

        assertThat(snapshot.format())
            .isEqualTo("MemorySnapshot[used=1.00 GB, max=4.00 GB, usage=25.0%]");
        assertThat(snapshot).hasToString(snapshot.format());
    }

    @Test
    @DisplayName("a snapshot reports its own headroom")
    void snapshotReportsItsHeadroom() {
        assertThat(snapshot(1 * GB, 2 * GB, 4 * GB).availableBytes()).isEqualTo(3 * GB);
        assertThat(snapshot(1 * GB, 1 * GB, 0).availableBytes())
            .as("an undefined heap limit leaves no headroom to claim")
            .isZero();
        assertThat(snapshot(1 * GB, 1 * GB, 0).usageRatio()).isZero();
    }

    @Test
    @DisplayName("a snapshot keeps the fields it was built from")
    void snapshotKeepsItsFields() {
        MemorySnapshot snapshot = new MemorySnapshot(1, 2, 3, 4);

        assertThat(snapshot.usedBytes()).isEqualTo(1);
        assertThat(snapshot.committedBytes()).isEqualTo(2);
        assertThat(snapshot.maxBytes()).isEqualTo(3);
        assertThat(snapshot.timestampMs()).isEqualTo(4);
    }

    private static MemorySnapshot snapshot(long used, long committed, long max) {
        return new MemorySnapshot(used, committed, max, 0L);
    }
}
