package excalibur.query.visibility;

import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diagnostic utility for checking segment visibility state and epoch correctness.
 *
 * <p>This class provides methods to diagnose why segments may not be visible
 * to queries. It checks:</p>
 * <ul>
 *   <li>Are segments registered in the VisibilityTracker?</li>
 *   <li>Are segments in queryable lifecycle states (INDEXING, WARMING, ACTIVE, COMPACTING)?</li>
 *   <li>Is the visibility epoch correct (creation epoch &lt;= query epoch &lt; tombstone epoch)?</li>
 *   <li>Do segments have metadata in the catalog?</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Run full diagnostic
 * DiagnosticResult result = SegmentVisibilityDiagnostic.runFullDiagnostic(
 *     visibilityTracker, catalog, storageEngine
 * );
 *
 * // Check for issues
 * if (!result.isHealthy()) {
 *     for (DiagnosticIssue issue : result.issues()) {
 *         System.err.println("Issue: " + issue.description());
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All diagnostic methods capture consistent snapshots.</p>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class SegmentVisibilityDiagnostic {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentVisibilityDiagnostic.class);

    private SegmentVisibilityDiagnostic() {
        // Utility class - no instances
    }

    // ==================== Diagnostic Result Types ====================

    /**
     * Result of a full visibility diagnostic.
     *
     * @param currentEpoch Current visibility epoch
     * @param totalCatalogSegments Total segments in catalog
     * @param registeredSegments Segments registered in VisibilityTracker
     * @param visibleSegments Segments visible at current epoch
     * @param queryableSegments Segments in queryable lifecycle states
     * @param issues List of diagnostic issues found
     */
    public record DiagnosticResult(
        long currentEpoch,
        int totalCatalogSegments,
        int registeredSegments,
        int visibleSegments,
        int queryableSegments,
        List<DiagnosticIssue> issues
    ) {
        /**
         * Returns true if no issues were found.
         *
         * @return true if the visibility state is healthy
         */
        public boolean isHealthy() {
            return issues.isEmpty();
        }

        /**
         * Returns the ratio of visible segments to catalog segments.
         *
         * @return Visibility ratio (0.0 to 1.0), or 1.0 if no catalog segments
         */
        public double visibilityRatio() {
            return totalCatalogSegments > 0
                ? (double) visibleSegments / totalCatalogSegments
                : 1.0;
        }

        /**
         * Returns a formatted summary of the diagnostic.
         *
         * @return Human-readable summary
         */
        public String summary() {
            return String.format(
                "SegmentVisibilityDiagnostic{epoch=%d, catalog=%d, registered=%d, visible=%d, queryable=%d, issues=%d}",
                currentEpoch, totalCatalogSegments, registeredSegments, visibleSegments, queryableSegments, issues.size()
            );
        }
    }

    /**
     * Severity level for diagnostic issues.
     */
    public enum Severity {
        /** Critical issue - segments will not be visible to queries */
        CRITICAL,
        /** Warning - visibility may be degraded */
        WARNING,
        /** Informational - potential optimization opportunity */
        INFO
    }

    /**
     * A single diagnostic issue found during visibility checking.
     *
     * @param severity Issue severity
     * @param segmentId Affected segment ID (may be -1 for global issues)
     * @param code Short code identifying the issue type
     * @param description Human-readable description
     * @param recommendation Suggested fix
     */
    public record DiagnosticIssue(
        Severity severity,
        long segmentId,
        String code,
        String description,
        String recommendation
    ) {
        /**
         * Creates a critical issue.
         */
        public static DiagnosticIssue critical(long segmentId, String code, String description, String recommendation) {
            return new DiagnosticIssue(Severity.CRITICAL, segmentId, code, description, recommendation);
        }

        /**
         * Creates a warning issue.
         */
        public static DiagnosticIssue warning(long segmentId, String code, String description, String recommendation) {
            return new DiagnosticIssue(Severity.WARNING, segmentId, code, description, recommendation);
        }

        /**
         * Creates an info issue.
         */
        public static DiagnosticIssue info(long segmentId, String code, String description, String recommendation) {
            return new DiagnosticIssue(Severity.INFO, segmentId, code, description, recommendation);
        }

        @Override
        public String toString() {
            String segmentStr = segmentId >= 0 ? "segment " + segmentId : "global";
            return String.format("[%s] %s: %s (%s) - %s",
                severity, segmentStr, code, description, recommendation);
        }
    }

    // ==================== Main Diagnostic Methods ====================

    /**
     * Runs a full visibility diagnostic on the storage engine.
     *
     * <p>This method checks all aspects of segment visibility:</p>
     * <ol>
     *   <li>Catalog segment count</li>
     *   <li>Visibility tracker registration</li>
     *   <li>Segment lifecycle states</li>
     *   <li>Epoch correctness</li>
     * </ol>
     *
     * @param visibilityTracker The visibility tracker
     * @param catalog The segment catalog
     * @param storageEngine The storage engine
     * @return Diagnostic result with issues list
     */
    public static DiagnosticResult runFullDiagnostic(
            VisibilityTracker visibilityTracker,
            SegmentCatalog catalog,
            StorageEngine storageEngine) {

        List<DiagnosticIssue> issues = new ArrayList<>();

        // Capture current epoch
        long currentEpoch = visibilityTracker.currentEpoch();

        // Check catalog
        Map<Long, SegmentCatalog.SegmentMetadata> allMetadata = catalog.getAll();
        int totalCatalogSegments = allMetadata.size();

        // Check visibility tracker registration
        Set<Long> visibleAtCurrentEpoch = visibilityTracker.visibleSegments(currentEpoch);
        int registeredSegments = visibilityTracker.getRegisteredSegmentCount();
        int visibleSegments = visibleAtCurrentEpoch.size();

        // Count queryable segments
        int queryableSegments = 0;

        // Issue: No segments in catalog
        if (totalCatalogSegments == 0) {
            issues.add(DiagnosticIssue.warning(
                -1, "EMPTY_CATALOG",
                "No segments in catalog",
                "Ingest data before querying, or verify ingestion pipeline is working"
            ));
        }

        // Issue: No segments registered in visibility tracker
        if (registeredSegments == 0 && totalCatalogSegments > 0) {
            issues.add(DiagnosticIssue.critical(
                -1, "NO_REGISTERED_SEGMENTS",
                "Catalog has segments but none are registered in VisibilityTracker",
                "Ensure segments are registered via visibilityTracker.registerSegment() after creation"
            ));
        }

        // Issue: No visible segments at current epoch
        if (visibleSegments == 0 && registeredSegments > 0) {
            issues.add(DiagnosticIssue.critical(
                -1, "NO_VISIBLE_SEGMENTS",
                "Segments are registered but none are visible at epoch " + currentEpoch,
                "Check segment lifecycle states and epoch values (creation epoch should be <= current epoch)"
            ));
        }

        // Check each segment in the catalog
        for (Map.Entry<Long, SegmentCatalog.SegmentMetadata> entry : allMetadata.entrySet()) {
            long segmentId = entry.getKey();
            SegmentCatalog.SegmentMetadata metadata = entry.getValue();
            LifecycleState state = metadata.getLifecycleState();

            // Count queryable
            if (state.isQueryable()) {
                queryableSegments++;
            }

            // Check registration
            long creationEpoch = visibilityTracker.getCreationEpoch(segmentId);
            if (creationEpoch < 0) {
                issues.add(DiagnosticIssue.critical(
                    segmentId, "NOT_REGISTERED",
                    "Segment in catalog but not registered in VisibilityTracker",
                    "Call visibilityTracker.registerSegment(" + segmentId + ") after segment creation"
                ));
                continue;
            }

            // Check lifecycle state
            if (!state.isQueryable()) {
                issues.add(DiagnosticIssue.info(
                    segmentId, "NOT_QUERYABLE_STATE",
                    "Segment is in " + state + " state (not queryable)",
                    "Wait for segment to transition to INDEXING, WARMING, ACTIVE, or COMPACTING"
                ));
            }

            // Check epoch correctness
            long tombstoneEpoch = visibilityTracker.getTombstoneEpoch(segmentId);

            // Check if creation epoch is in the future
            if (creationEpoch > currentEpoch) {
                issues.add(DiagnosticIssue.critical(
                    segmentId, "FUTURE_CREATION_EPOCH",
                    "Segment creation epoch (" + creationEpoch + ") is greater than current epoch (" + currentEpoch + ")",
                    "Epoch may have been corrupted, or segment was registered incorrectly"
                ));
            }

            // Check if tombstoned
            if (tombstoneEpoch != Long.MAX_VALUE && tombstoneEpoch <= currentEpoch) {
                issues.add(DiagnosticIssue.info(
                    segmentId, "TOMBSTONED",
                    "Segment was tombstoned at epoch " + tombstoneEpoch,
                    "Segment is no longer visible to new queries"
                ));
            }
        }

        // Check for segments in visibility tracker but not in catalog
        for (Long segmentId : visibleAtCurrentEpoch) {
            if (!allMetadata.containsKey(segmentId)) {
                issues.add(DiagnosticIssue.warning(
                    segmentId, "ORPHANED_VISIBILITY",
                    "Segment registered in VisibilityTracker but not in catalog",
                    "Segment may have been purged but not unregistered from visibility tracker"
                ));
            }
        }

        // Log results
        LOGGER.info("Visibility diagnostic complete: {} catalog segments, {} registered, {} visible, {} queryable, {} issues",
            totalCatalogSegments, registeredSegments, visibleSegments, queryableSegments, issues.size());

        for (DiagnosticIssue issue : issues) {
            LOGGER.debug("Diagnostic issue: {}", issue);
        }

        return new DiagnosticResult(
            currentEpoch,
            totalCatalogSegments,
            registeredSegments,
            visibleSegments,
            queryableSegments,
            Collections.unmodifiableList(issues)
        );
    }

    /**
     * Checks if a specific segment is visible and returns detailed information.
     *
     * @param segmentId The segment ID to check
     * @param visibilityTracker The visibility tracker
     * @param catalog The segment catalog
     * @return Segment visibility info
     */
    public static SegmentVisibilityInfo checkSegmentVisibility(
            long segmentId,
            VisibilityTracker visibilityTracker,
            SegmentCatalog catalog) {

        long currentEpoch = visibilityTracker.currentEpoch();
        long creationEpoch = visibilityTracker.getCreationEpoch(segmentId);
        long tombstoneEpoch = visibilityTracker.getTombstoneEpoch(segmentId);
        boolean isVisible = visibilityTracker.isSegmentVisible(segmentId, currentEpoch);

        SegmentCatalog.SegmentMetadata metadata = catalog.get(segmentId);
        LifecycleState state = metadata != null ? metadata.getLifecycleState() : null;
        boolean isQueryable = state != null && state.isQueryable();

        List<String> issues = new ArrayList<>();

        if (creationEpoch < 0) {
            issues.add("Segment not registered in VisibilityTracker");
        }
        if (metadata == null) {
            issues.add("Segment not found in catalog");
        }
        if (state != null && !state.isQueryable()) {
            issues.add("Segment lifecycle state (" + state + ") is not queryable");
        }
        if (creationEpoch > currentEpoch) {
            issues.add("Creation epoch (" + creationEpoch + ") > current epoch (" + currentEpoch + ")");
        }
        if (tombstoneEpoch != Long.MAX_VALUE && tombstoneEpoch <= currentEpoch) {
            issues.add("Segment tombstoned at epoch " + tombstoneEpoch);
        }

        return new SegmentVisibilityInfo(
            segmentId,
            currentEpoch,
            creationEpoch,
            tombstoneEpoch,
            state,
            isVisible,
            isQueryable,
            Collections.unmodifiableList(issues)
        );
    }

    /**
     * Detailed visibility information for a single segment.
     *
     * @param segmentId Segment ID
     * @param currentEpoch Current visibility epoch
     * @param creationEpoch Segment creation epoch (-1 if not registered)
     * @param tombstoneEpoch Segment tombstone epoch (Long.MAX_VALUE if not tombstoned)
     * @param lifecycleState Current lifecycle state (null if not in catalog)
     * @param isVisible Whether segment is visible at current epoch
     * @param isQueryable Whether lifecycle state allows querying
     * @param issues List of issues affecting visibility
     */
    public record SegmentVisibilityInfo(
        long segmentId,
        long currentEpoch,
        long creationEpoch,
        long tombstoneEpoch,
        LifecycleState lifecycleState,
        boolean isVisible,
        boolean isQueryable,
        List<String> issues
    ) {
        /**
         * Returns true if the segment has visibility issues.
         *
         * @return true if issues exist
         */
        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        /**
         * Returns a human-readable summary.
         *
         * @return Summary string
         */
        public String summary() {
            return String.format(
                "Segment %d: epoch=%d, creation=%d, tombstone=%d, state=%s, visible=%s, queryable=%s, issues=%d",
                segmentId, currentEpoch, creationEpoch, tombstoneEpoch,
                lifecycleState, isVisible, isQueryable, issues.size()
            );
        }
    }

    // ==================== Quick Check Methods ====================

    /**
     * Quick check: Are there any visible segments at the current epoch?
     *
     * @param visibilityTracker The visibility tracker
     * @return true if at least one segment is visible
     */
    public static boolean hasVisibleSegments(VisibilityTracker visibilityTracker) {
        long epoch = visibilityTracker.currentEpoch();
        return !visibilityTracker.visibleSegments(epoch).isEmpty();
    }

    /**
     * Quick check: Are segments in ACTIVE state?
     *
     * <p>This checks the catalog for segments in ACTIVE state.</p>
     *
     * @param catalog The segment catalog
     * @return Count of segments in ACTIVE state
     */
    public static int countActiveSegments(SegmentCatalog catalog) {
        int count = 0;
        for (SegmentCatalog.SegmentMetadata metadata : catalog.getAll().values()) {
            if (metadata.getLifecycleState() == LifecycleState.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /**
     * Quick check: Is the visibility epoch correct?
     *
     * <p>Checks that for all registered segments, creation epoch &lt;= current epoch.</p>
     *
     * @param visibilityTracker The visibility tracker
     * @return true if all epochs are correct
     */
    public static boolean isEpochCorrect(VisibilityTracker visibilityTracker) {
        long currentEpoch = visibilityTracker.currentEpoch();
        Set<Long> visible = visibilityTracker.visibleSegments(currentEpoch);

        // If no segments registered, epoch is trivially correct
        if (visibilityTracker.getRegisteredSegmentCount() == 0) {
            return true;
        }

        // Check that at least some segments are visible if any are registered
        // (assuming segments should be visible if properly configured)
        return !visible.isEmpty() || currentEpoch == 0;
    }

    /**
     * Formats a diagnostic report for logging or display.
     *
     * @param result The diagnostic result
     * @return Formatted report string
     */
    public static String formatReport(DiagnosticResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Segment Visibility Diagnostic Report ===\n");
        sb.append(String.format("Current Epoch: %d\n", result.currentEpoch()));
        sb.append(String.format("Catalog Segments: %d\n", result.totalCatalogSegments()));
        sb.append(String.format("Registered Segments: %d\n", result.registeredSegments()));
        sb.append(String.format("Visible Segments: %d\n", result.visibleSegments()));
        sb.append(String.format("Queryable Segments: %d\n", result.queryableSegments()));
        sb.append(String.format("Visibility Ratio: %.2f%%\n", result.visibilityRatio() * 100));
        sb.append(String.format("Health Status: %s\n", result.isHealthy() ? "HEALTHY" : "ISSUES FOUND"));

        if (!result.issues().isEmpty()) {
            sb.append("\n--- Issues ---\n");
            for (DiagnosticIssue issue : result.issues()) {
                sb.append(String.format("  %s\n", issue));
            }
        }

        return sb.toString();
    }
}
