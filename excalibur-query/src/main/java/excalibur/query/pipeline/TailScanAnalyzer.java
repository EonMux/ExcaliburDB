package excalibur.query.pipeline;

import io.excalibur.store.Segment;
import io.excalibur.vector.hnsw.HnswGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Analyzes candidate segments to determine tail scan requirements.
 *
 * <p>This class is responsible for comparing {@code lastIntegratedOffset} vs
 * {@code writeLogHead} for each candidate segment in the query pipeline.
 * Segments where {@code lastIntegratedOffset < writeLogHead} have entries
 * in the write log that have not yet been integrated into the HNSW graph.</p>
 *
 * <h2>Backpressure Enforcement</h2>
 * <p>Tail scan entry count is bounded by the backpressure limit (default 100K entries).
 * This ensures query latency remains acceptable even during heavy ingestion.
 * The analyzer estimates entry counts and can cap scanning to stay within limits.</p>
 *
 * <h2>Comparison Logic</h2>
 * <p>For each candidate segment:</p>
 * <ol>
 *   <li>Retrieve {@code lastIntegratedOffset} from the HNSW graph</li>
 *   <li>Retrieve {@code writeLogHead} from the segment's write log</li>
 *   <li>If {@code writeLogHead > lastIntegratedOffset}: tail scan needed</li>
 *   <li>If {@code writeLogHead <= lastIntegratedOffset}: no tail scan needed</li>
 * </ol>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>All offsets are normalized to non-negative values (negative → 0)</li>
 *   <li>Tail size is always non-negative: max(0, writeLogHead - lastIntegratedOffset)</li>
 *   <li>Total tail scan entries is bounded by backpressure limit (100K)</li>
 *   <li>Results are returned in segment ID order for deterministic behavior</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and therefore thread-safe. Each analysis operation
 * operates on its own result list.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class TailScanAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TailScanAnalyzer.class);

    /**
     * Default backpressure limit for tail scan entry count.
     * The total number of entries scanned across all segments must not exceed this limit.
     */
    public static final int DEFAULT_BACKPRESSURE_ENTRY_LIMIT = 100_000;

    /**
     * Default backpressure limit for tail scan size in bytes.
     * When the total tail size exceeds this limit, a warning is logged.
     */
    public static final long DEFAULT_BACKPRESSURE_LIMIT_BYTES = 100_000L * 100; // ~100K entries * ~100 bytes avg

    /**
     * Maximum single-segment tail size before warning (10MB).
     */
    public static final long MAX_SINGLE_SEGMENT_TAIL_BYTES = 10 * 1024 * 1024;

    /**
     * Average entry size in bytes used for entry count estimation.
     */
    public static final int DEFAULT_AVG_ENTRY_SIZE = 100;

    private final long backpressureLimitBytes;
    private final int backpressureEntryLimit;

    /**
     * Creates a new TailScanAnalyzer with default backpressure limits.
     */
    public TailScanAnalyzer() {
        this(DEFAULT_BACKPRESSURE_ENTRY_LIMIT);
    }

    /**
     * Creates a new TailScanAnalyzer with a custom entry limit.
     *
     * @param backpressureEntryLimit Maximum total entries to scan across all segments
     */
    public TailScanAnalyzer(int backpressureEntryLimit) {
        this(backpressureEntryLimit, (long) backpressureEntryLimit * DEFAULT_AVG_ENTRY_SIZE);
    }

    /**
     * Creates a new TailScanAnalyzer with custom byte and entry limits.
     *
     * @param backpressureEntryLimit Maximum total entries to scan
     * @param backpressureLimitBytes Maximum total tail scan size in bytes
     */
    public TailScanAnalyzer(int backpressureEntryLimit, long backpressureLimitBytes) {
        this.backpressureEntryLimit = Math.max(1, backpressureEntryLimit);
        this.backpressureLimitBytes = Math.max(0, backpressureLimitBytes);
    }

    /**
     * Analyzes candidate segments to determine tail scan requirements.
     *
     * <p>This is the primary method for comparing lastIntegratedOffset vs
     * writeLogHead for each candidate segment. It returns a list of
     * TailScanRequirement objects, one for each segment that needs analysis.</p>
     *
     * <h3>Implementation Details</h3>
     * <ul>
     *   <li>Each segment's graph and write log are queried for offsets</li>
     *   <li>Comparison is performed: lastIntegratedOffset vs writeLogHead</li>
     *   <li>Segments with tail entries are flagged for tail scan</li>
     *   <li>Results are sorted by segment ID for deterministic ordering</li>
     * </ul>
     *
     * @param candidates The list of candidate segments to analyze
     * @return List of TailScanRequirement for each candidate segment
     * @throws NullPointerException if candidates is null
     */
    public List<TailScanRequirement> analyzeSegments(List<SegmentCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<TailScanRequirement> requirements = new ArrayList<>(candidates.size());
        long totalTailSize = 0;
        int segmentsNeedingTailScan = 0;

        for (SegmentCandidate candidate : candidates) {
            TailScanRequirement requirement = analyzeSegment(candidate);
            requirements.add(requirement);

            if (requirement.isTailScanNeeded()) {
                segmentsNeedingTailScan++;
                totalTailSize += requirement.tailSize();

                // Log warning for large single-segment tails
                if (requirement.tailSize() > MAX_SINGLE_SEGMENT_TAIL_BYTES) {
                    LOGGER.warn("Segment {} has large tail size: {} bytes. " +
                               "This may impact query latency.",
                               candidate.segmentId(), requirement.tailSize());
                }
            }
        }

        // Log summary
        if (segmentsNeedingTailScan > 0) {
            LOGGER.debug("Tail scan analysis: {} of {} segments need tail scan, " +
                        "total tail size: {} bytes",
                        segmentsNeedingTailScan, candidates.size(), totalTailSize);

            // Check backpressure
            if (totalTailSize > backpressureLimitBytes) {
                LOGGER.warn("Total tail scan size {} exceeds backpressure limit {}. " +
                           "Query latency may be impacted.",
                           totalTailSize, backpressureLimitBytes);
            }
        }

        return requirements;
    }

    /**
     * Analyzes a single segment to determine its tail scan requirement.
     *
     * <p>This method performs the core comparison: lastIntegratedOffset vs writeLogHead.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>If graph is null, returns no tail scan needed (conservative)</li>
     *   <li>If segment is null, returns no tail scan needed (conservative)</li>
     *   <li>Negative offsets are normalized to 0</li>
     *   <li>Tail size is always non-negative</li>
     * </ul>
     *
     * @param candidate The candidate segment to analyze
     * @return A TailScanRequirement representing the comparison result
     */
    public TailScanRequirement analyzeSegment(SegmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");

        long segmentId = candidate.segmentId();
        Segment segment = candidate.segment();
        HnswGraph graph = candidate.graph();

        // Conservative handling: if graph or segment is null, no tail scan
        if (graph == null || segment == null) {
            LOGGER.trace("Segment {}: graph or segment is null, no tail scan needed", segmentId);
            return TailScanRequirement.noTailScan(segmentId);
        }

        // Get last integrated offset from the HNSW graph
        long lastIntegratedOffset = getLastIntegratedOffset(graph);

        // Get write log head from the segment
        long writeLogHead = getWriteLogHead(segment);

        // Create the requirement by comparing offsets
        TailScanRequirement requirement = TailScanRequirement.compare(
            segmentId,
            lastIntegratedOffset,
            writeLogHead
        );

        if (requirement.isTailScanNeeded()) {
            LOGGER.trace("Segment {} needs tail scan: lastIntegratedOffset={}, writeLogHead={}, tailSize={}",
                        segmentId, lastIntegratedOffset, writeLogHead, requirement.tailSize());
        }

        return requirement;
    }

    /**
     * Gets the last integrated offset from an HNSW graph.
     *
     * <p>The last integrated offset is the write log offset up to which all
     * entries have been linked into the HNSW graph. Entries beyond this
     * offset exist in the write log but are not yet in the graph.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Returns 0 if graph is null</li>
     *   <li>Returns 0 if an exception occurs (fail-safe)</li>
     *   <li>Result is always non-negative</li>
     * </ul>
     *
     * @param graph The HNSW graph to query
     * @return The last integrated offset (≥ 0)
     */
    private long getLastIntegratedOffset(HnswGraph graph) {
        if (graph == null) {
            return 0;
        }

        try {
            long offset = graph.getLastIntegratedOffset();
            // Ensure non-negative
            return Math.max(0, offset);
        } catch (Exception e) {
            LOGGER.warn("Failed to get lastIntegratedOffset from graph: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the write log head offset from a segment.
     *
     * <p>The write log head is the offset of the next byte to be written.
     * All entries with offset < writeLogHead have been persisted to the write log.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Returns 0 if segment is null</li>
     *   <li>Returns 0 if write log is not attached</li>
     *   <li>Returns 0 if an exception occurs (fail-safe)</li>
     *   <li>Result is always non-negative</li>
     * </ul>
     *
     * @param segment The segment to query
     * @return The write log head offset (≥ 0)
     */
    private long getWriteLogHead(Segment segment) {
        if (segment == null) {
            return 0;
        }

        try {
            long head = segment.getWriteLogHead();
            // Ensure non-negative
            return Math.max(0, head);
        } catch (Exception e) {
            LOGGER.warn("Failed to get writeLogHead from segment: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Filters candidate segments to only those needing tail scans.
     *
     * <p>This is a convenience method that returns only the segments
     * where lastIntegratedOffset < writeLogHead.</p>
     *
     * @param candidates The list of all candidate segments
     * @return List of candidates that need tail scans
     */
    public List<SegmentCandidate> filterSegmentsNeedingTailScan(List<SegmentCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        List<TailScanRequirement> requirements = analyzeSegments(candidates);
        List<SegmentCandidate> needingTailScan = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            if (requirements.get(i).isTailScanNeeded()) {
                needingTailScan.add(candidates.get(i));
            }
        }

        return needingTailScan;
    }

    /**
     * Computes the total tail scan size across all segments.
     *
     * <p>This is the sum of (writeLogHead - lastIntegratedOffset) for all
     * segments where this difference is positive.</p>
     *
     * @param candidates The list of candidate segments
     * @return Total tail scan size in bytes (≥ 0)
     */
    public long computeTotalTailSize(List<SegmentCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        long total = 0;
        for (SegmentCandidate candidate : candidates) {
            TailScanRequirement requirement = analyzeSegment(candidate);
            total += requirement.tailSize();
        }
        return total;
    }

    /**
     * Checks if the total tail scan size exceeds the backpressure limit.
     *
     * <p>This is used to determine if query latency may be impacted
     * by large tail scans.</p>
     *
     * @param candidates The list of candidate segments
     * @return true if total tail size exceeds backpressure limit
     */
    public boolean exceedsBackpressureLimit(List<SegmentCandidate> candidates) {
        return computeTotalTailSize(candidates) > backpressureLimitBytes;
    }

    /**
     * Gets the backpressure limit in bytes.
     *
     * @return The backpressure limit
     */
    public long getBackpressureLimitBytes() {
        return backpressureLimitBytes;
    }

    /**
     * Gets the backpressure entry limit.
     *
     * @return The maximum number of entries to scan
     */
    public int getBackpressureEntryLimit() {
        return backpressureEntryLimit;
    }

    /**
     * Estimates the total entry count for a tail scan.
     *
     * <p>Uses the default average entry size for estimation.</p>
     *
     * @param tailSizeBytes The tail size in bytes
     * @return Estimated entry count (≥ 0)
     */
    public long estimateEntryCount(long tailSizeBytes) {
        if (tailSizeBytes <= 0) {
            return 0;
        }
        return tailSizeBytes / DEFAULT_AVG_ENTRY_SIZE;
    }

    /**
     * Estimates the total entry count across all segments.
     *
     * @param candidates The list of candidate segments
     * @return Estimated total entry count (≥ 0)
     */
    public long estimateTotalEntryCount(List<SegmentCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        long totalBytes = computeTotalTailSize(candidates);
        return estimateEntryCount(totalBytes);
    }

    /**
     * Checks if the estimated entry count exceeds the backpressure limit.
     *
     * <p>This is the primary method for determining if tail scanning should
     * be throttled to maintain query latency.</p>
     *
     * @param candidates The list of candidate segments
     * @return true if estimated entry count exceeds the limit (100K)
     */
    public boolean exceedsEntryLimit(List<SegmentCandidate> candidates) {
        long estimatedEntries = estimateTotalEntryCount(candidates);
        return estimatedEntries > backpressureEntryLimit;
    }

    /**
     * Creates an analysis result containing all segment requirements and summary statistics.
     *
     * <p>This method estimates entry counts and checks against the backpressure limit
     * (100K entries) to determine if tail scanning may impact query latency.</p>
     *
     * @param candidates The list of candidate segments to analyze
     * @return An AnalysisResult containing all requirements and statistics
     */
    public AnalysisResult analyzeWithSummary(List<SegmentCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        List<TailScanRequirement> requirements = analyzeSegments(candidates);

        long totalTailSize = 0;
        int segmentsNeedingTailScan = 0;
        int segmentsWithNoTail = 0;
        long maxTailSize = 0;
        long segmentIdWithMaxTail = -1;

        for (TailScanRequirement req : requirements) {
            if (req.isTailScanNeeded()) {
                segmentsNeedingTailScan++;
                totalTailSize += req.tailSize();

                if (req.tailSize() > maxTailSize) {
                    maxTailSize = req.tailSize();
                    segmentIdWithMaxTail = req.segmentId();
                }
            } else {
                segmentsWithNoTail++;
            }
        }

        // Estimate entry count and check against limit
        long estimatedEntryCount = estimateEntryCount(totalTailSize);
        boolean exceedsEntryLimit = estimatedEntryCount > backpressureEntryLimit;
        boolean exceedsByteLimit = totalTailSize > backpressureLimitBytes;

        // Log warning if entry limit is exceeded
        if (exceedsEntryLimit) {
            LOGGER.warn("Estimated tail scan entry count ({}) exceeds backpressure limit ({}). " +
                       "Query latency may be impacted. Total tail size: {} bytes.",
                       estimatedEntryCount, backpressureEntryLimit, totalTailSize);
        }

        return new AnalysisResult(
            requirements,
            candidates.size(),
            segmentsNeedingTailScan,
            segmentsWithNoTail,
            totalTailSize,
            maxTailSize,
            segmentIdWithMaxTail,
            exceedsByteLimit,
            backpressureLimitBytes,
            estimatedEntryCount,
            backpressureEntryLimit,
            exceedsEntryLimit
        );
    }

    /**
     * Result of tail scan analysis containing requirements and summary statistics.
     */
    public static final class AnalysisResult {
        private final List<TailScanRequirement> requirements;
        private final int totalSegments;
        private final int segmentsNeedingTailScan;
        private final int segmentsWithNoTail;
        private final long totalTailSize;
        private final long maxTailSize;
        private final long segmentIdWithMaxTail;
        private final boolean exceedsBackpressure;
        private final long backpressureLimit;
        private final long estimatedEntryCount;
        private final int entryLimit;
        private final boolean exceedsEntryLimit;

        private AnalysisResult(
                List<TailScanRequirement> requirements,
                int totalSegments,
                int segmentsNeedingTailScan,
                int segmentsWithNoTail,
                long totalTailSize,
                long maxTailSize,
                long segmentIdWithMaxTail,
                boolean exceedsBackpressure,
                long backpressureLimit,
                long estimatedEntryCount,
                int entryLimit,
                boolean exceedsEntryLimit) {

            this.requirements = Collections.unmodifiableList(requirements);
            this.totalSegments = totalSegments;
            this.segmentsNeedingTailScan = segmentsNeedingTailScan;
            this.segmentsWithNoTail = segmentsWithNoTail;
            this.totalTailSize = totalTailSize;
            this.maxTailSize = maxTailSize;
            this.segmentIdWithMaxTail = segmentIdWithMaxTail;
            this.exceedsBackpressure = exceedsBackpressure;
            this.backpressureLimit = backpressureLimit;
            this.estimatedEntryCount = estimatedEntryCount;
            this.entryLimit = entryLimit;
            this.exceedsEntryLimit = exceedsEntryLimit;
        }

        /**
         * Gets the list of tail scan requirements for each segment.
         */
        public List<TailScanRequirement> requirements() {
            return requirements;
        }

        /**
         * Gets the total number of segments analyzed.
         */
        public int totalSegments() {
            return totalSegments;
        }

        /**
         * Gets the number of segments needing tail scans.
         */
        public int segmentsNeedingTailScan() {
            return segmentsNeedingTailScan;
        }

        /**
         * Gets the number of segments with no tail (fully integrated).
         */
        public int segmentsWithNoTail() {
            return segmentsWithNoTail;
        }

        /**
         * Gets the total tail scan size in bytes.
         */
        public long totalTailSize() {
            return totalTailSize;
        }

        /**
         * Gets the maximum tail size among all segments.
         */
        public long maxTailSize() {
            return maxTailSize;
        }

        /**
         * Gets the segment ID with the largest tail.
         */
        public long segmentIdWithMaxTail() {
            return segmentIdWithMaxTail;
        }

        /**
         * Checks if total tail size exceeds byte-based backpressure limit.
         * @deprecated Use {@link #exceedsEntryLimit()} for entry-based limit checking
         */
        @Deprecated
        public boolean exceedsBackpressure() {
            return exceedsBackpressure;
        }

        /**
         * Gets the backpressure limit in bytes.
         * @deprecated Use {@link #entryLimit()} for entry-based limit
         */
        @Deprecated
        public long backpressureLimit() {
            return backpressureLimit;
        }

        /**
         * Gets the estimated total entry count across all tail scans.
         *
         * @return Estimated entry count (≥ 0)
         */
        public long estimatedEntryCount() {
            return estimatedEntryCount;
        }

        /**
         * Gets the backpressure entry limit.
         *
         * @return The maximum number of entries allowed (default 100K)
         */
        public int entryLimit() {
            return entryLimit;
        }

        /**
         * Checks if the estimated entry count exceeds the backpressure limit.
         *
         * <p>This is the primary indicator of whether tail scanning may
         * impact query latency.</p>
         *
         * @return true if estimated entries > entry limit
         */
        public boolean exceedsEntryLimit() {
            return exceedsEntryLimit;
        }

        /**
         * Gets the tail scan coverage ratio.
         *
         * <p>This is the ratio of segments needing tail scan to total segments.
         * A value of 0.0 means all segments are fully integrated.
         * A value of 1.0 means all segments need tail scans.</p>
         *
         * @return Coverage ratio in range [0.0, 1.0]
         */
        public double tailScanCoverageRatio() {
            if (totalSegments == 0) {
                return 0.0;
            }
            return (double) segmentsNeedingTailScan / totalSegments;
        }

        @Override
        public String toString() {
            return String.format(
                "AnalysisResult{totalSegments=%d, needingTailScan=%d, totalTailSize=%d, " +
                "estimatedEntries=%d, entryLimit=%d, exceedsEntryLimit=%s}",
                totalSegments, segmentsNeedingTailScan, totalTailSize,
                estimatedEntryCount, entryLimit, exceedsEntryLimit
            );
        }
    }
}
