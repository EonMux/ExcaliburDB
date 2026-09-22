package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the tail-scan totals only ever go up.
 *
 * <p>They are published as counters, and a counter is read by taking differences: every
 * {@code rate()} and {@code increase()} written over one assumes it never decreases, and
 * treats a drop as a process restart. One negative contribution therefore does not just
 * misreport its own scan - it puts a spike into every rate computed across it, for as long as
 * the query window reaches back that far. {@link Metrics#recordIngestEntries(long)} already
 * screens its argument for exactly this reason; these counters take the same care.</p>
 *
 * @author ExcaliburDB
 */
class TailScanCounterMonotonicityTest {

    @Test
    @DisplayName("a negative report cannot walk the totals backwards")
    void negativeReportCannotLowerTheTotals() {
        Metrics.recordTailScanMetrics(80, 30);
        long scanned = Metrics.getTotalTailScanned();
        long found = Metrics.getTotalVectorEntriesFound();

        Metrics.recordTailScanMetrics(-50, -20);

        assertTrue(Metrics.getTotalTailScanned() >= scanned,
            "entries scanned is a counter and must not decrease");
        assertTrue(Metrics.getTotalVectorEntriesFound() >= found,
            "entries with a vector is a counter and must not decrease");
        assertEquals(scanned, Metrics.getTotalTailScanned(),
            "a negative count contributes nothing rather than subtracting");
        assertEquals(found, Metrics.getTotalVectorEntriesFound(),
            "a negative count contributes nothing rather than subtracting");
    }

    @Test
    @DisplayName("a scan is still counted when one of its numbers is unusable")
    void theScanItselfIsStillCounted() {
        long scans = Metrics.getTotalTailScanCount();

        Metrics.recordTailScanMetrics(-1, 4);

        assertEquals(scans + 1, Metrics.getTotalTailScanCount(),
            "the scan happened, whatever its report said");
        assertTrue(Metrics.getAverageTailScanEntries() >= 0,
            "the average stays defined and non-negative");
    }

    @Test
    @DisplayName("a usable number is still kept when its partner is not")
    void theUsableHalfIsStillKept() {
        long found = Metrics.getTotalVectorEntriesFound();

        Metrics.recordTailScanMetrics(-7, 6);

        assertEquals(found + 6, Metrics.getTotalVectorEntriesFound(),
            "the two counters are independent; one bad number must not discard the other");
    }

    @Test
    @DisplayName("a negative report cannot lower the maximum scan size")
    void negativeReportCannotLowerTheMaximum() {
        Metrics.recordTailScanMetrics(90, 1);
        long max = Metrics.getMaxTailScanEntries();

        Metrics.recordTailScanMetrics(-1000, -1000);

        assertEquals(max, Metrics.getMaxTailScanEntries(), "the maximum must not move down");
    }
}
