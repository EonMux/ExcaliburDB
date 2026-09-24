package com.eonmux.excalibur.bench.metric;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Validates memory usage during benchmarks to ensure they don't exceed available RAM.
 *
 * <p>Ensures that memory usage follows the constraints required for safe benchmark execution:</p>
 * <ul>
 *   <li>Memory used must be non-negative</li>
 *   <li>Memory used must not exceed available heap memory</li>
 *   <li>Memory values should be within physical limits of the system</li>
 *   <li>Peak memory usage should be tracked during benchmark execution</li>
 * </ul>
 *
 * <h2>Domain Correctness Rules</h2>
 * <ul>
 *   <li>Used memory: [0, maxMemory] - cannot use more than available</li>
 *   <li>Committed memory: [used, maxMemory] - reserved by JVM</li>
 *   <li>Max memory: positive value representing heap limit</li>
 *   <li>Memory usage ratio: [0.0, 1.0] - fraction of heap used</li>
 *   <li>Safe threshold: 0.85 (85%) - warns when approaching limit</li>
 *   <li>Critical threshold: 0.95 (95%) - fails when too close to OOM</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and all methods are thread-safe. Memory snapshots
 * are taken atomically to ensure consistency.</p>
 *
 * @author ExcaliburDB
 */
public final class MemoryUsageValidator {

    /**
     * Safe memory usage threshold (85% of max heap).
     * Usage above this triggers a warning.
     */
    public static final double SAFE_THRESHOLD = 0.85;

    /**
     * Critical memory usage threshold (95% of max heap).
     * Usage above this triggers a failure.
     */
    public static final double CRITICAL_THRESHOLD = 0.95;

    /**
     * Minimum heap size for benchmarks (64MB).
     * Heaps smaller than this may cause GC thrashing.
     */
    public static final long MIN_HEAP_SIZE_BYTES = 64L * 1024 * 1024;

    /**
     * Recommended minimum heap size for benchmarks (512MB).
     */
    public static final long RECOMMENDED_MIN_HEAP_BYTES = 512L * 1024 * 1024;

    /**
     * Memory usage threshold for benchmark validation (90% of max).
     * Benchmarks exceeding this may be unreliable due to GC pressure.
     */
    public static final double BENCHMARK_MEMORY_THRESHOLD = 0.90;

    private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();

    /**
     * Captures current heap memory usage snapshot.
     *
     * @return a snapshot of current memory usage
     */
    public static MemorySnapshot captureSnapshot() {
        MemoryUsage usage = MEMORY_MX_BEAN.getHeapMemoryUsage();
        return new MemorySnapshot(
            usage.getUsed(),
            usage.getCommitted(),
            usage.getMax(),
            System.currentTimeMillis()
        );
    }

    /**
     * Gets the maximum heap memory available.
     *
     * @return max heap in bytes
     */
    public static long getMaxHeapBytes() {
        return MEMORY_MX_BEAN.getHeapMemoryUsage().getMax();
    }

    /**
     * Gets the currently used heap memory.
     *
     * @return used heap in bytes
     */
    public static long getUsedHeapBytes() {
        return MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
    }

    /**
     * Gets the current memory usage ratio (used / max).
     *
     * @return usage ratio in range [0.0, 1.0]
     */
    public static double getUsageRatio() {
        MemoryUsage usage = MEMORY_MX_BEAN.getHeapMemoryUsage();
        long max = usage.getMax();
        if (max <= 0) {
            return 0.0;
        }
        return (double) usage.getUsed() / max;
    }

    /**
     * Validates current memory usage against safe thresholds.
     *
     * @return validation result indicating if memory usage is safe
     */
    public static ValidationResult validateCurrentUsage() {
        return validate(captureSnapshot());
    }

    /**
     * Validates memory usage at the start of a benchmark.
     *
     * <p>Checks that there is sufficient memory available for the benchmark to run.</p>
     *
     * @return validation result with pre-benchmark memory state
     */
    public static ValidationResult validatePreBenchmark() {
        MemorySnapshot snapshot = captureSnapshot();
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Check heap size is adequate
        if (snapshot.maxBytes < MIN_HEAP_SIZE_BYTES) {
            errors.append(String.format(
                "Heap size (%.1f MB) is below minimum (%.1f MB) - may cause GC thrashing; ",
                bytesToMB(snapshot.maxBytes), bytesToMB(MIN_HEAP_SIZE_BYTES)));
        } else if (snapshot.maxBytes < RECOMMENDED_MIN_HEAP_BYTES) {
            warnings.append(String.format(
                "Heap size (%.1f MB) is below recommended (%.1f MB); ",
                bytesToMB(snapshot.maxBytes), bytesToMB(RECOMMENDED_MIN_HEAP_BYTES)));
        }

        // Check initial memory pressure
        double usageRatio = snapshot.usageRatio();
        if (usageRatio >= CRITICAL_THRESHOLD) {
            errors.append(String.format(
                "Initial memory usage (%.1f%%) is at critical level (%.0f%%) - insufficient headroom; ",
                usageRatio * 100, CRITICAL_THRESHOLD * 100));
        } else if (usageRatio >= SAFE_THRESHOLD) {
            warnings.append(String.format(
                "Initial memory usage (%.1f%%) is high - consider increasing heap; ",
                usageRatio * 100));
        }

        // Calculate available memory
        long availableBytes = snapshot.maxBytes - snapshot.usedBytes;
        if (availableBytes < MIN_HEAP_SIZE_BYTES / 2) {
            warnings.append(String.format(
                "Only %.1f MB available - benchmark may cause memory pressure; ",
                bytesToMB(availableBytes)));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), snapshot, usageRatio);
    }

    /**
     * Validates a memory snapshot for domain correctness.
     *
     * @param snapshot the memory snapshot to validate
     * @return validation result
     */
    public static ValidationResult validate(MemorySnapshot snapshot) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Validate max bytes is positive
        if (snapshot.maxBytes <= 0) {
            errors.append("Max heap memory is not defined (<= 0); ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), snapshot, 0.0);
        }

        // Validate used bytes is non-negative
        if (snapshot.usedBytes < 0) {
            errors.append(String.format(
                "Used memory (%d bytes) cannot be negative; ",
                snapshot.usedBytes));
            return new ValidationResult(false, errors.toString(), warnings.toString(), snapshot, 0.0);
        }

        // Validate used doesn't exceed max
        if (snapshot.usedBytes > snapshot.maxBytes) {
            errors.append(String.format(
                "Used memory (%.1f MB) exceeds max heap (%.1f MB); ",
                bytesToMB(snapshot.usedBytes), bytesToMB(snapshot.maxBytes)));
            return new ValidationResult(false, errors.toString(), warnings.toString(), snapshot, 0.0);
        }

        // Validate committed is between used and max
        if (snapshot.committedBytes < snapshot.usedBytes) {
            warnings.append(String.format(
                "Committed memory (%.1f MB) is less than used (%.1f MB) - unusual state; ",
                bytesToMB(snapshot.committedBytes), bytesToMB(snapshot.usedBytes)));
        }
        if (snapshot.committedBytes > snapshot.maxBytes) {
            warnings.append(String.format(
                "Committed memory (%.1f MB) exceeds max heap (%.1f MB); ",
                bytesToMB(snapshot.committedBytes), bytesToMB(snapshot.maxBytes)));
        }

        // Check usage ratio thresholds
        double usageRatio = snapshot.usageRatio();

        if (usageRatio >= CRITICAL_THRESHOLD) {
            errors.append(String.format(
                "Memory usage (%.1f%%) exceeds critical threshold (%.0f%%) - OOM risk; ",
                usageRatio * 100, CRITICAL_THRESHOLD * 100));
        } else if (usageRatio >= SAFE_THRESHOLD) {
            warnings.append(String.format(
                "Memory usage (%.1f%%) exceeds safe threshold (%.0f%%); ",
                usageRatio * 100, SAFE_THRESHOLD * 100));
        }

        // Check if approaching heap limit
        if (usageRatio >= BENCHMARK_MEMORY_THRESHOLD) {
            warnings.append(String.format(
                "Memory usage (%.1f%%) exceeds benchmark threshold (%.0f%%) - results may be unreliable; ",
                usageRatio * 100, BENCHMARK_MEMORY_THRESHOLD * 100));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), snapshot, usageRatio);
    }

    /**
     * Validates that a benchmark's peak memory usage was within acceptable limits.
     *
     * @param startSnapshot memory snapshot at benchmark start
     * @param endSnapshot memory snapshot at benchmark end
     * @param peakBytes peak memory usage during benchmark (or 0 if not tracked)
     * @return validation result with memory delta information
     */
    public static ValidationResult validateBenchmarkMemory(
            MemorySnapshot startSnapshot,
            MemorySnapshot endSnapshot,
            long peakBytes) {
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        // Validate end snapshot
        ValidationResult endValidation = validate(endSnapshot);
        if (!endValidation.valid()) {
            return endValidation;
        }
        warnings.append(endValidation.warnings());

        // Calculate memory delta
        long memoryDelta = endSnapshot.usedBytes - startSnapshot.usedBytes;
        long effectivePeak = peakBytes > 0 ? peakBytes : endSnapshot.usedBytes;

        // Check peak didn't exceed max
        if (effectivePeak > endSnapshot.maxBytes) {
            errors.append(String.format(
                "Peak memory (%.1f MB) exceeded max heap (%.1f MB); ",
                bytesToMB(effectivePeak), bytesToMB(endSnapshot.maxBytes)));
        }

        // Check peak usage ratio
        double peakRatio = (double) effectivePeak / endSnapshot.maxBytes;
        if (peakRatio >= CRITICAL_THRESHOLD) {
            errors.append(String.format(
                "Peak memory usage (%.1f%%) exceeded critical threshold (%.0f%%); ",
                peakRatio * 100, CRITICAL_THRESHOLD * 100));
        } else if (peakRatio >= SAFE_THRESHOLD) {
            warnings.append(String.format(
                "Peak memory usage (%.1f%%) exceeded safe threshold (%.0f%%); ",
                peakRatio * 100, SAFE_THRESHOLD * 100));
        }

        // Check for significant memory growth (potential leak)
        if (memoryDelta > endSnapshot.maxBytes * 0.10) {
            warnings.append(String.format(
                "Significant memory growth during benchmark (%.1f MB) - potential memory leak; ",
                bytesToMB(memoryDelta)));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), endSnapshot, peakRatio);
    }

    /**
     * Checks if there is enough memory available for an operation.
     *
     * @param requiredBytes the bytes required for the operation
     * @return true if sufficient memory is available
     */
    public static boolean hasAvailableMemory(long requiredBytes) {
        MemorySnapshot snapshot = captureSnapshot();
        long available = snapshot.maxBytes - snapshot.usedBytes;
        // Reserve 10% buffer for GC and other overhead
        long safeAvailable = (long) (available * 0.9);
        return safeAvailable >= requiredBytes;
    }

    /**
     * Validates that sufficient memory is available for an operation.
     *
     * @param requiredBytes the bytes required for the operation
     * @param operationName name of the operation for error messages
     * @return validation result
     */
    public static ValidationResult validateAvailableMemory(long requiredBytes, String operationName) {
        MemorySnapshot snapshot = captureSnapshot();
        StringBuilder warnings = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        if (requiredBytes < 0) {
            errors.append("Required bytes cannot be negative; ");
            return new ValidationResult(false, errors.toString(), warnings.toString(), snapshot, 0.0);
        }

        long available = snapshot.maxBytes - snapshot.usedBytes;
        long safeAvailable = (long) (available * 0.9);

        if (requiredBytes > safeAvailable) {
            errors.append(String.format(
                "Operation '%s' requires %.1f MB but only %.1f MB available (safe: %.1f MB); ",
                operationName,
                bytesToMB(requiredBytes),
                bytesToMB(available),
                bytesToMB(safeAvailable)));
        } else if (requiredBytes > available / 2) {
            warnings.append(String.format(
                "Operation '%s' requires %.1f MB (> 50%% of available %.1f MB); ",
                operationName,
                bytesToMB(requiredBytes),
                bytesToMB(available)));
        }

        boolean valid = errors.length() == 0;
        return new ValidationResult(valid, errors.toString(), warnings.toString(), snapshot, snapshot.usageRatio());
    }

    /**
     * Suggests garbage collection and returns memory freed.
     *
     * <p>Note: This is only a hint to the JVM and may not immediately free memory.</p>
     *
     * @return bytes freed (difference in used memory before/after), may be 0
     */
    public static long suggestGarbageCollection() {
        long before = getUsedHeapBytes();
        System.gc();
        long after = getUsedHeapBytes();
        return Math.max(0, before - after);
    }

    /**
     * Formats bytes as a human-readable string.
     *
     * @param bytes the byte count
     * @return formatted string (e.g., "1.5 GB", "256 MB", "512 KB")
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "INVALID";
        }

        final long KB = 1024;
        final long MB = KB * 1024;
        final long GB = MB * 1024;

        if (bytes >= GB) {
            return String.format("%.2f GB", (double) bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.1f MB", (double) bytes / MB);
        } else if (bytes >= KB) {
            return String.format("%.1f KB", (double) bytes / KB);
        } else {
            return String.format("%d bytes", bytes);
        }
    }

    /**
     * Creates a formatted summary of current memory usage.
     *
     * @return human-readable summary string
     */
    public static String formatSummary() {
        MemorySnapshot snapshot = captureSnapshot();
        ValidationResult validation = validate(snapshot);

        StringBuilder sb = new StringBuilder();
        sb.append("Memory Usage:\n");
        sb.append(String.format("  used: %s\n", formatBytes(snapshot.usedBytes)));
        sb.append(String.format("  committed: %s\n", formatBytes(snapshot.committedBytes)));
        sb.append(String.format("  max: %s\n", formatBytes(snapshot.maxBytes)));
        sb.append(String.format("  usage: %.1f%%\n", snapshot.usageRatio() * 100));
        sb.append(String.format("  available: %s\n", formatBytes(snapshot.maxBytes - snapshot.usedBytes)));

        if (validation.valid()) {
            sb.append("  status: OK");
            if (validation.hasWarnings()) {
                sb.append(" (with warnings)");
            }
        } else {
            sb.append("  status: EXCEEDS LIMITS");
        }

        if (!validation.warnings().isEmpty()) {
            sb.append(String.format("\n  warnings: %s", validation.warnings().trim()));
        }

        if (!validation.errors().isEmpty()) {
            sb.append(String.format("\n  errors: %s", validation.errors().trim()));
        }

        return sb.toString();
    }

    /**
     * Asserts that current memory usage is within safe limits.
     *
     * @throws IllegalStateException if memory usage exceeds safe limits
     */
    public static void assertMemorySafe() {
        ValidationResult result = validateCurrentUsage();
        if (!result.valid()) {
            throw new IllegalStateException(
                "Memory usage validation failed: " + result.combinedMessage());
        }
    }

    /**
     * Asserts that sufficient memory is available for an operation.
     *
     * @param requiredBytes the bytes required
     * @param operationName name of the operation
     * @throws IllegalStateException if insufficient memory is available
     */
    public static void assertMemoryAvailable(long requiredBytes, String operationName) {
        ValidationResult result = validateAvailableMemory(requiredBytes, operationName);
        if (!result.valid()) {
            throw new IllegalStateException(
                "Insufficient memory for " + operationName + ": " + result.combinedMessage());
        }
    }

    private static double bytesToMB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    /**
     * Immutable snapshot of memory usage at a point in time.
     */
    public static final class MemorySnapshot {
        private final long usedBytes;
        private final long committedBytes;
        private final long maxBytes;
        private final long timestampMs;

        /**
         * Creates a memory snapshot.
         *
         * @param usedBytes bytes currently in use
         * @param committedBytes bytes committed by the JVM
         * @param maxBytes maximum bytes available (heap limit)
         * @param timestampMs timestamp when snapshot was taken
         */
        public MemorySnapshot(long usedBytes, long committedBytes, long maxBytes, long timestampMs) {
            this.usedBytes = usedBytes;
            this.committedBytes = committedBytes;
            this.maxBytes = maxBytes;
            this.timestampMs = timestampMs;
        }

        /**
         * Gets the bytes currently in use.
         */
        public long usedBytes() {
            return usedBytes;
        }

        /**
         * Gets the bytes committed by the JVM.
         */
        public long committedBytes() {
            return committedBytes;
        }

        /**
         * Gets the maximum bytes available (heap limit).
         */
        public long maxBytes() {
            return maxBytes;
        }

        /**
         * Gets the timestamp when this snapshot was taken.
         */
        public long timestampMs() {
            return timestampMs;
        }

        /**
         * Gets the memory usage ratio (used / max).
         *
         * @return ratio in range [0.0, 1.0], or 0.0 if max is 0
         */
        public double usageRatio() {
            if (maxBytes <= 0) {
                return 0.0;
            }
            return (double) usedBytes / maxBytes;
        }

        /**
         * Gets the available memory (max - used).
         */
        public long availableBytes() {
            return Math.max(0, maxBytes - usedBytes);
        }

        /**
         * Formats this snapshot as a human-readable string.
         */
        public String format() {
            return String.format("MemorySnapshot[used=%s, max=%s, usage=%.1f%%]",
                formatBytes(usedBytes),
                formatBytes(maxBytes),
                usageRatio() * 100);
        }

        @Override
        public String toString() {
            return format();
        }
    }

    /**
     * Result of a memory usage validation.
     *
     * @param valid whether the validation passed
     * @param errors error messages if validation failed
     * @param warnings warning messages
     * @param snapshot the memory snapshot that was validated
     * @param usageRatio the memory usage ratio at validation time
     */
    public record ValidationResult(
        boolean valid,
        String errors,
        String warnings,
        MemorySnapshot snapshot,
        double usageRatio
    ) {

        /**
         * Checks if there are any warnings.
         */
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }

        /**
         * Checks if there are any errors.
         */
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        /**
         * Gets a combined message of errors and warnings.
         */
        public String combinedMessage() {
            if (errors.isEmpty() && warnings.isEmpty()) {
                return String.format("Memory usage OK (%.1f%%)", usageRatio * 100);
            }
            if (errors.isEmpty()) {
                return "Warnings: " + warnings.trim();
            }
            if (warnings.isEmpty()) {
                return "Errors: " + errors.trim();
            }
            return "Errors: " + errors.trim() + "; Warnings: " + warnings.trim();
        }

        /**
         * Gets the usage ratio as a percentage string.
         */
        public String usagePercent() {
            return String.format("%.1f%%", usageRatio * 100);
        }
    }

    private MemoryUsageValidator() {
        // Utility class - no instantiation
    }
}
