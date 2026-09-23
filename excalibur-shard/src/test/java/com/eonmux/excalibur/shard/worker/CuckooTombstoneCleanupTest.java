package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retiring a segment has to take its terms out of the filter.
 *
 * <p>The filter answers whether a segment holds a term, and a term left behind for a segment that
 * is gone routes every query matching it to a segment that cannot answer. A segment is cleaned
 * once, because a Cuckoo delete removes the first slot whose fingerprint matches and repeating it
 * would evict a live term that collides - so which segments count as cleaned decides both whether
 * the filter is ever repaired and whether the log is telling the truth about it.</p>
 */
@DisplayName("Tombstoned segment filter cleanup")
class CuckooTombstoneCleanupTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog catalog;
    private StorageEngine storageEngine;
    private List<Long> cleaned;
    private CuckooMaintenanceWorker worker;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        storageEngine = mock(StorageEngine.class);
        cleaned = new ArrayList<>();
        worker = new CuckooMaintenanceWorker(catalog, storageEngine, cleaned::add);
    }

    private void tombstone(long segmentId) {
        var metadata = catalog.add(segmentId, tempDir.resolve("segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }

    @Test
    @DisplayName("a segment the engine has not loaded is tried again, not written off")
    void anUnloadedSegmentIsRetried() {
        tombstone(7L);
        when(storageEngine.getSegment(7L)).thenReturn(null);

        worker.processTombstonedSegments();

        assertThat(worker.getTombstonedSegmentsProcessed())
            .as("nothing was removed from the filter, so nothing was processed")
            .isZero();
        assertThat(cleaned).isEmpty();
    }

    @Test
    @DisplayName("a segment that loads on a later cycle is cleaned then")
    void aSegmentThatArrivesLateIsCleaned() throws Exception {
        tombstone(7L);
        when(storageEngine.getSegment(7L)).thenReturn(null);
        worker.processTombstonedSegments();

        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena)) {
            when(storageEngine.getSegment(7L)).thenReturn(segment);

            worker.processTombstonedSegments();

            assertThat(worker.getTombstonedSegmentsProcessed()).isEqualTo(1);
            assertThat(cleaned).containsExactly(7L);
        }
    }

    @Test
    @DisplayName("a segment already cleaned is not cleaned twice")
    void aCleanedSegmentIsNotRepeated() throws Exception {
        tombstone(7L);
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena)) {
            when(storageEngine.getSegment(7L)).thenReturn(segment);

            worker.processTombstonedSegments();
            worker.processTombstonedSegments();

            assertThat(worker.getTombstonedSegmentsProcessed())
                .as("a Cuckoo delete repeated would evict a live term that collides")
                .isEqualTo(1);
            assertThat(cleaned).containsExactly(7L);
        }
    }

    @Test
    @DisplayName("a tombstoned segment's entries are pending until the sweep removes them")
    void pendingCountsTheEntriesATombstonedSegmentStillHolds() throws Exception {
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(10_000);
        worker.setHierarchicalFilter(hierarchy);
        hierarchy.registerSegment("7");
        hierarchy.registerSegment("8");
        String text = "alpha bravo charlie delta";
        Set<String> terms = TermExtractor.extractUniqueTerms(text);
        for (String term : terms) {
            hierarchy.insert(term.getBytes(StandardCharsets.UTF_8), "7");
        }
        // A live segment's entries are not pending, whatever it holds.
        hierarchy.insert("echo".getBytes(StandardCharsets.UTF_8), "8");
        assertThat(worker.getPendingDeletionCount())
            .as("nothing is tombstoned yet")
            .isZero();

        tombstone(7L);
        assertThat(worker.getPendingDeletionCount()).isEqualTo(terms.size());

        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena)) {
            segment.put("chunk-1".getBytes(StandardCharsets.UTF_8), chunkValue(text));
            when(storageEngine.getSegment(7L)).thenReturn(segment);

            int removed = worker.processTombstonedSegments();

            assertThat(removed).isEqualTo(terms.size());
            assertThat(worker.getTotalDeletions()).isEqualTo(terms.size());
            assertThat(worker.getPendingDeletionCount())
                .as("everything pending was removed, and a cleaned segment is not pending again")
                .isZero();
            assertThat(hierarchy.mightContain("echo".getBytes(StandardCharsets.UTF_8)))
                .as("the live segment's term stays in the corpus level")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a sweep stops starting segments once it has spent its budget")
    void theSweepStopsStartingSegmentsAtItsBudget() throws Exception {
        HierarchicalCuckooFilter hierarchy = new HierarchicalCuckooFilter(10_000);
        worker.setHierarchicalFilter(hierarchy);
        String text = "alpha bravo charlie delta";
        int perSegment = TermExtractor.extractUniqueTerms(text).size();
        tombstone(7L);
        tombstone(8L);
        try (Arena arena = Arena.ofShared();
             Segment seven = new Segment(tempDir.resolve("segment7"), 1024L * 1024L, arena);
             Segment eight = new Segment(tempDir.resolve("segment8"), 1024L * 1024L, arena)) {
            for (long id : new long[] {7L, 8L}) {
                hierarchy.registerSegment(String.valueOf(id));
                for (String term : TermExtractor.extractUniqueTerms(text)) {
                    hierarchy.insert(term.getBytes(StandardCharsets.UTF_8), String.valueOf(id));
                }
            }
            seven.put("chunk-7".getBytes(StandardCharsets.UTF_8), chunkValue(text));
            eight.put("chunk-8".getBytes(StandardCharsets.UTF_8), chunkValue(text));
            when(storageEngine.getSegment(7L)).thenReturn(seven);
            when(storageEngine.getSegment(8L)).thenReturn(eight);
            assertThat(worker.getPendingDeletionCount()).isEqualTo(2 * perSegment);

            worker.processTombstonedSegments(1);

            assertThat(worker.getTombstonedSegmentsProcessed())
                .as("the first segment spent the budget, so the second waits")
                .isEqualTo(1);
            assertThat(worker.getPendingDeletionCount()).isEqualTo(perSegment);

            worker.processTombstonedSegments(1);

            assertThat(worker.getTombstonedSegmentsProcessed()).isEqualTo(2);
            assertThat(worker.getPendingDeletionCount()).isZero();
        }
    }

    /** A stored chunk value as ingestion writes it: a one-dimension vector, then the text. */
    private static byte[] chunkValue(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + Float.BYTES + utf8.length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1)
            .putFloat(0.5f)
            .put(utf8)
            .array();
    }
}
