package com.eonmux.excalibur.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What tail scanning over un-integrated write-log entries has cost so far.
 *
 * <h2>Why four numbers rather than one</h2>
 * <p>Tail scanning is the read path's fallback for entries the HNSW worker has not absorbed
 * yet, so its cost is what a growing backlog actually feels like to a query. A total alone
 * hides the shape of that cost: the average says whether scanning is routine, the maximum says
 * how bad a single unlucky query got, and the vector hit count says how much of the scanning
 * was useful at all. An operator deciding whether the backlog is hurting reads needs all four,
 * and they have to come from one set of updates or they describe different moments.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>The three totals are atomic adds, so no scan's contribution can be lost under contention.
 * The maximum is a compare-and-set retry loop rather than a lock: a lock here would sit on the
 * read path of every query that falls back to scanning, and the loop only spins when two scans
 * set a new record at the same instant.</p>
 *
 * <h2>Consistency</h2>
 * <p>The four are updated one after another rather than together, so a reader that samples
 * mid-update can see a scan counted in one total and not yet in another. That is accepted: the
 * skew is a single scan's worth on counters read for trend, and making the set atomic would
 * mean a lock on the scan path to fix a discrepancy no operator can act on.</p>
 *
 * @author ExcaliburDB
 */
final class TailScanCounters {

    /**
     * Total number of tail scan operations performed.
     * Each call to {@link #record(int, int)} increments this by 1.
     */
    private final AtomicLong totalCount = new AtomicLong(0);

    /**
     * Total number of entries scanned across all tail scan operations.
     * This accumulates the entriesScanned values from all scans.
     */
    private final AtomicLong totalScanned = new AtomicLong(0);

    /**
     * Maximum number of entries scanned in a single tail scan operation.
     * Updated atomically using CAS loop for thread safety.
     */
    private final AtomicLong maxEntries = new AtomicLong(0);

    /**
     * Total number of scanned entries that carried a valid vector.
     * This accumulates the vectorEntriesFound values from all scans.
     */
    private final AtomicLong totalVectorEntriesFound = new AtomicLong(0);

    /**
     * Creates a counter set with every total at zero.
     */
    TailScanCounters() {
    }

    /**
     * Records one completed tail scan.
     *
     * <p>A negative count contributes nothing. These three totals are published as counters,
     * and a counter is read by differencing: every {@code rate()} written over one takes a
     * decrease to mean the process restarted, so a single negative contribution puts a spike
     * into every rate computed across it for as long as the query window reaches back. The
     * two counts are screened independently, because they measure different things and one
     * unusable number is no reason to discard the other. The scan itself is always counted -
     * it happened, whatever its report said - which also keeps {@link #averageEntries()}
     * defined.</p>
     *
     * @param entriesScanned Number of log entries scanned, including non-vector entries;
     *                       negative is ignored
     * @param vectorEntriesFound Number of entries with valid vectors found; negative is ignored
     */
    void record(int entriesScanned, int vectorEntriesFound) {
        long scanned = Math.max(0, entriesScanned);
        long withVector = Math.max(0, vectorEntriesFound);

        // Increment the count of tail scan operations
        totalCount.incrementAndGet();

        // Add to the total entries scanned
        totalScanned.addAndGet(scanned);

        // Add to the total entries that carried a valid vector
        totalVectorEntriesFound.addAndGet(withVector);

        // Update max using CAS loop for thread safety without locks
        long currentMax = maxEntries.get();
        while (scanned > currentMax) {
            if (maxEntries.compareAndSet(currentMax, scanned)) {
                break;
            }
            currentMax = maxEntries.get();
        }
    }

    /**
     * Gets the average number of entries scanned per tail scan operation.
     *
     * @return Average entries scanned per operation, or 0 if no scans performed
     */
    long averageEntries() {
        long total = totalScanned.get();
        long count = totalCount.get();
        return count == 0 ? 0 : total / count;
    }

    /**
     * Gets the maximum number of entries scanned in a single tail scan operation.
     *
     * @return Maximum entries scanned in any single operation
     */
    long maxEntries() {
        return maxEntries.get();
    }

    /**
     * Gets the total number of entries scanned across all tail scan operations.
     *
     * @return Total entries scanned across all operations
     */
    long totalScanned() {
        return totalScanned.get();
    }

    /**
     * Gets the total number of scanned entries that carried a valid vector.
     *
     * @return Total entries with a valid vector across all tail scans
     */
    long totalVectorEntriesFound() {
        return totalVectorEntriesFound.get();
    }
}
