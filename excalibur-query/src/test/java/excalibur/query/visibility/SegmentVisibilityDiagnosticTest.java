package excalibur.query.visibility;

import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SegmentVisibilityDiagnostic.
 *
 * <p>Tests verify that the diagnostic correctly identifies visibility issues:</p>
 * <ul>
 *   <li>Segments not registered in VisibilityTracker</li>
 *   <li>Segments in non-queryable lifecycle states</li>
 *   <li>Incorrect epoch values</li>
 *   <li>Tombstoned segments</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class SegmentVisibilityDiagnosticTest {

    private MockVisibilityTracker visibilityTracker;
    private MockSegmentCatalog catalog;
    private MockStorageEngine storageEngine;

    @BeforeEach
    void setUp() throws IOException {
        visibilityTracker = new MockVisibilityTracker();
        catalog = new MockSegmentCatalog();
        storageEngine = new MockStorageEngine(catalog);
    }

    @Nested
    @DisplayName("Full Diagnostic")
    class FullDiagnostic {

        @Test
        @DisplayName("reports healthy when all segments are visible and queryable")
        void healthyWhenAllSegmentsVisible() {
            // Setup: Add segments to catalog
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            catalog.addSegment(1L, LifecycleState.ACTIVE);
            catalog.addSegment(2L, LifecycleState.ACTIVE);

            // Register in visibility tracker
            visibilityTracker.registerSegment(0L, 0);
            visibilityTracker.registerSegment(1L, 0);
            visibilityTracker.registerSegment(2L, 0);

            // Run diagnostic
            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            // Verify
            assertThat(result.isHealthy()).isTrue();
            assertThat(result.issues()).isEmpty();
            assertThat(result.totalCatalogSegments()).isEqualTo(3);
            assertThat(result.registeredSegments()).isEqualTo(3);
            assertThat(result.visibleSegments()).isEqualTo(3);
            assertThat(result.queryableSegments()).isEqualTo(3);
        }

        @Test
        @DisplayName("reports issue when catalog is empty")
        void reportsEmptyCatalog() {
            // Empty catalog, no segments

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            assertThat(result.isHealthy()).isFalse();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).code()).isEqualTo("EMPTY_CATALOG");
        }

        @Test
        @DisplayName("reports critical issue when segments not registered")
        void reportsUnregisteredSegments() {
            // Add segments to catalog but don't register
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            catalog.addSegment(1L, LifecycleState.ACTIVE);

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            assertThat(result.isHealthy()).isFalse();
            // 2 per-segment issues + 1 global NO_REGISTERED_SEGMENTS issue
            assertThat(result.issues()).hasSize(3);

            // Check for critical NOT_REGISTERED issues
            List<SegmentVisibilityDiagnostic.DiagnosticIssue> criticalIssues = result.issues().stream()
                .filter(i -> i.severity() == SegmentVisibilityDiagnostic.Severity.CRITICAL)
                .toList();
            assertThat(criticalIssues).isNotEmpty();
            assertThat(criticalIssues.stream().anyMatch(i -> i.code().equals("NOT_REGISTERED"))).isTrue();
        }

        @Test
        @DisplayName("reports info when segments are in non-queryable states")
        void reportsNonQueryableStates() {
            // Add segments in various states
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            catalog.addSegment(1L, LifecycleState.INDEXING);
            catalog.addSegment(2L, LifecycleState.BOOTSTRAPPING); // Not queryable
            catalog.addSegment(3L, LifecycleState.TOMBSTONED);    // Not queryable

            // Register all
            visibilityTracker.registerSegment(0L, 0);
            visibilityTracker.registerSegment(1L, 0);
            visibilityTracker.registerSegment(2L, 0);
            visibilityTracker.registerSegment(3L, 0);
            visibilityTracker.markTombstoned(3L, 0); // Tombstone at epoch 0

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            // Should have info issues for non-queryable states
            List<SegmentVisibilityDiagnostic.DiagnosticIssue> infoIssues = result.issues().stream()
                .filter(i -> i.severity() == SegmentVisibilityDiagnostic.Severity.INFO)
                .toList();
            assertThat(infoIssues).isNotEmpty();
        }

        @Test
        @DisplayName("reports critical issue when creation epoch is in future")
        void reportsFutureCreationEpoch() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 100); // Creation epoch in future

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            assertThat(result.isHealthy()).isFalse();
            assertThat(result.issues().stream()
                .anyMatch(i -> i.code().equals("FUTURE_CREATION_EPOCH")))
                .isTrue();
        }

        @Test
        @DisplayName("reports no visible segments when none are visible")
        void reportsNoVisibleSegments() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 100); // Future epoch means not visible

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            assertThat(result.visibleSegments()).isEqualTo(0);
            assertThat(result.issues().stream()
                .anyMatch(i -> i.code().equals("NO_VISIBLE_SEGMENTS")))
                .isTrue();
        }

        @Test
        @DisplayName("reports tombstoned segments")
        void reportsTombstonedSegments() {
            catalog.addSegment(0L, LifecycleState.TOMBSTONED);
            visibilityTracker.registerSegment(0L, 0);
            visibilityTracker.markTombstoned(0L, 1);

            // Advance epoch past tombstone
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            assertThat(result.issues().stream()
                .anyMatch(i -> i.code().equals("TOMBSTONED")))
                .isTrue();
        }
    }

    @Nested
    @DisplayName("Segment Visibility Check")
    class SegmentVisibilityCheck {

        @Test
        @DisplayName("returns correct info for visible segment")
        void visibleSegmentInfo() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 0);

            SegmentVisibilityDiagnostic.SegmentVisibilityInfo info =
                SegmentVisibilityDiagnostic.checkSegmentVisibility(0L, visibilityTracker, catalog);

            assertThat(info.isVisible()).isTrue();
            assertThat(info.isQueryable()).isTrue();
            assertThat(info.hasIssues()).isFalse();
            assertThat(info.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("returns correct info for unregistered segment")
        void unregisteredSegmentInfo() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            // Don't register in visibility tracker

            SegmentVisibilityDiagnostic.SegmentVisibilityInfo info =
                SegmentVisibilityDiagnostic.checkSegmentVisibility(0L, visibilityTracker, catalog);

            assertThat(info.isVisible()).isFalse();
            assertThat(info.hasIssues()).isTrue();
            assertThat(info.issues()).contains("Segment not registered in VisibilityTracker");
        }

        @Test
        @DisplayName("returns correct info for segment not in catalog")
        void segmentNotInCatalog() {
            // Don't add to catalog
            visibilityTracker.registerSegment(0L, 0);

            SegmentVisibilityDiagnostic.SegmentVisibilityInfo info =
                SegmentVisibilityDiagnostic.checkSegmentVisibility(0L, visibilityTracker, catalog);

            assertThat(info.hasIssues()).isTrue();
            assertThat(info.issues()).contains("Segment not found in catalog");
        }
    }

    @Nested
    @DisplayName("Quick Check Methods")
    class QuickChecks {

        @Test
        @DisplayName("hasVisibleSegments returns true when segments visible")
        void hasVisibleSegmentsTrue() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 0);

            assertThat(SegmentVisibilityDiagnostic.hasVisibleSegments(visibilityTracker)).isTrue();
        }

        @Test
        @DisplayName("hasVisibleSegments returns false when no segments")
        void hasVisibleSegmentsFalse() {
            assertThat(SegmentVisibilityDiagnostic.hasVisibleSegments(visibilityTracker)).isFalse();
        }

        @Test
        @DisplayName("countActiveSegments counts ACTIVE segments")
        void countActiveSegments() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            catalog.addSegment(1L, LifecycleState.ACTIVE);
            catalog.addSegment(2L, LifecycleState.INDEXING);

            assertThat(SegmentVisibilityDiagnostic.countActiveSegments(catalog)).isEqualTo(2);
        }

        @Test
        @DisplayName("isEpochCorrect returns true for valid epochs")
        void isEpochCorrect() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 0);

            assertThat(SegmentVisibilityDiagnostic.isEpochCorrect(visibilityTracker)).isTrue();
        }
    }

    @Nested
    @DisplayName("Report Formatting")
    class ReportFormatting {

        @Test
        @DisplayName("formatReport produces readable output")
        void formatReportReadable() {
            catalog.addSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(0L, 0);

            SegmentVisibilityDiagnostic.DiagnosticResult result =
                SegmentVisibilityDiagnostic.runFullDiagnostic(visibilityTracker, catalog, storageEngine);

            String report = SegmentVisibilityDiagnostic.formatReport(result);

            assertThat(report).contains("Segment Visibility Diagnostic Report");
            assertThat(report).contains("Current Epoch:");
            assertThat(report).contains("Catalog Segments: 1");
            assertThat(report).contains("Visible Segments: 1");
            assertThat(report).contains("Health Status: HEALTHY");
        }
    }

    // ==================== Mock Implementations ====================

    private static class MockVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(0);
        private final Map<Long, Long> creationEpochs = new ConcurrentHashMap<>();
        private final Map<Long, Long> tombstoneEpochs = new ConcurrentHashMap<>();

        MockVisibilityTracker() {
            super(null);
        }

        @Override
        public long currentEpoch() {
            return epoch.get();
        }

        @Override
        public long captureEpoch() {
            return epoch.get();
        }

        @Override
        public long advanceEpoch() {
            return epoch.incrementAndGet();
        }

        @Override
        public void registerSegment(long segmentId, long creationEpoch) {
            creationEpochs.put(segmentId, creationEpoch);
            tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
        }

        @Override
        public void markTombstoned(long segmentId, long tombstoneEpoch) {
            tombstoneEpochs.put(segmentId, tombstoneEpoch);
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            Set<Long> visible = new HashSet<>();
            for (Map.Entry<Long, Long> entry : creationEpochs.entrySet()) {
                long segmentId = entry.getKey();
                long creation = entry.getValue();
                long tombstone = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
                if (creation <= queryEpoch && queryEpoch < tombstone) {
                    visible.add(segmentId);
                }
            }
            return Collections.unmodifiableSet(visible);
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            Long creation = creationEpochs.get(segmentId);
            if (creation == null) return false;
            long tombstone = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
            return creation <= queryEpoch && queryEpoch < tombstone;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return creationEpochs.getOrDefault(segmentId, -1L);
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            return tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
        }

        @Override
        public int getRegisteredSegmentCount() {
            return creationEpochs.size();
        }
    }

    private static class MockSegmentCatalog extends SegmentCatalog {
        private final Map<Long, SegmentCatalog.SegmentMetadata> segments = new ConcurrentHashMap<>();

        void addSegment(long segmentId, LifecycleState state) {
            SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(segmentId);
            // Properly transition through states to reach the target state
            if (state != LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing();
                if (state != LifecycleState.INDEXING) {
                    metadata.transitionToWarming();
                    if (state != LifecycleState.WARMING) {
                        metadata.transitionToActive();
                        if (state == LifecycleState.COMPACTING) {
                            metadata.transitionToCompacting();
                        } else if (state == LifecycleState.TOMBSTONED) {
                            metadata.transitionToTombstoned();
                        }
                    }
                }
            }
            segments.put(segmentId, metadata);
        }

        @Override
        public SegmentCatalog.SegmentMetadata get(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, SegmentCatalog.SegmentMetadata> getAll() {
            return Collections.unmodifiableMap(segments);
        }

        @Override
        public int size() {
            return segments.size();
        }
    }

    private static class MockStorageEngine extends StorageEngine {
        private final SegmentCatalog catalog;

        MockStorageEngine(SegmentCatalog catalog) throws IOException {
            super(createTempDir(), Arena.ofShared());
            this.catalog = catalog;
        }

        private static Path createTempDir() throws IOException {
            Path tempDir = Files.createTempDirectory("mock-storage-");
            tempDir.toFile().deleteOnExit();
            return tempDir;
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return null;
        }
    }
}

