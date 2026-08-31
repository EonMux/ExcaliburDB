package io.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every value handed to {@link Metrics#recordTailScanMetrics(int, int)} is kept.
 *
 * <p>The tail scanner reports how many entries it walked and how many of them carried a usable
 * vector. The second number is what tells an operator whether tail scanning is doing useful
 * work or grinding through entries that decode to nothing, so an argument that is accepted and
 * then dropped is worse than an absent parameter: the call site looks instrumented.</p>
 *
 * @author ExcaliburDB
 */
class MetricsTailScanTest {

    @Test
    @DisplayName("vectorEntriesFound accumulates and is published")
    void vectorEntriesFoundAccumulates() throws Exception {
        long scannedBefore = Metrics.getTotalTailScanned();
        long foundBefore = Metrics.getTotalVectorEntriesFound();
        long scansBefore = Metrics.getTotalTailScanCount();

        Metrics.recordTailScanMetrics(100, 30);
        Metrics.recordTailScanMetrics(50, 20);

        assertEquals(scannedBefore + 150, Metrics.getTotalTailScanned(),
            "entries scanned must accumulate");
        assertEquals(foundBefore + 50, Metrics.getTotalVectorEntriesFound(),
            "entries with a vector must accumulate, not be discarded");
        assertEquals(scansBefore + 2, Metrics.getTotalTailScanCount(),
            "each call is one scan");

        Object published = Metrics.getHnswQueueDepthMBean().getAttribute("totalVectorEntriesFound");
        assertEquals(Metrics.getTotalVectorEntriesFound(), published,
            "the counter must be readable over JMX");
    }

    @Test
    @DisplayName("The two tail-scan counters stay independent")
    void countersAreIndependent() {
        long scannedBefore = Metrics.getTotalTailScanned();
        long foundBefore = Metrics.getTotalVectorEntriesFound();

        // A scan that found no vectors at all must still count the entries it walked.
        Metrics.recordTailScanMetrics(40, 0);

        assertEquals(scannedBefore + 40, Metrics.getTotalTailScanned(),
            "entries scanned must move");
        assertEquals(foundBefore, Metrics.getTotalVectorEntriesFound(),
            "entries with a vector must not move");
    }

    @Test
    @DisplayName("The maximum scan size is the largest single scan")
    void maximumTracksLargestScan() {
        Metrics.recordTailScanMetrics(5, 1);
        long max = Metrics.getMaxTailScanEntries();

        Metrics.recordTailScanMetrics(1, 0);
        assertEquals(max, Metrics.getMaxTailScanEntries(), "a smaller scan must not lower the max");

        Metrics.recordTailScanMetrics((int) max + 7, 2);
        assertEquals(max + 7, Metrics.getMaxTailScanEntries(), "a larger scan must raise the max");
        assertTrue(Metrics.getAverageTailScanEntries() > 0, "the average must be populated");
    }
}
