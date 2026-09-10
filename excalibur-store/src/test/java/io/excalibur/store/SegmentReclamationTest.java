package io.excalibur.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the engine is willing to let the lifecycle manager reclaim.
 *
 * <p>A segment index is a slot in a hash directory: every key whose hash lands on it is written
 * there, and a slot cannot be vacated. Purging a segment the directory still points at removes
 * its catalog row and leaves the file and the writes behind - the segment answers no query from
 * that moment, and the next {@link StorageEngine#start()} rediscovers the file, finds no row
 * naming it and adds one back as a live segment. The tombstone a rolling reindex placed is
 * undone by a bounce, and the superseded copy of every document it retired is answerable
 * again.</p>
 *
 * <p>So these tests are about the restart as much as the purge: a segment retired before a
 * bounce has to still be retired after one.</p>
 */
@DisplayName("Segment reclamation")
class SegmentReclamationTest {

    /** Far enough past any tombstone epoch to satisfy the epoch lag threshold. */
    private static final long FAR_FUTURE_EPOCH = 1_000_000L;

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine engine;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        engine = new StorageEngine(tempDir, arena);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("keeps the catalog row of a segment the directory still routes to")
    void refusesToPurgeARoutedSegment() {
        long routed = aSegmentHoldingEntries();
        tombstone(routed);

        engine.getLifecycleManager().checkForReclamationCandidates(FAR_FUTURE_EPOCH);

        assertThat(engine.getCatalog().get(routed))
            .as("slot %d still takes every key that hashes to it; without its row those writes "
                + "are answerable by nothing", routed)
            .isNotNull()
            .extracting(SegmentCatalog.SegmentMetadata::getLifecycleState)
            .isEqualTo(LifecycleState.TOMBSTONED);
    }

    @Test
    @DisplayName("does not hand a retired segment back as a live one after a restart")
    void aRetiredSegmentStaysRetiredAcrossARestart() throws IOException {
        long routed = aSegmentHoldingEntries();
        tombstone(routed);

        engine.getLifecycleManager().checkForReclamationCandidates(FAR_FUTURE_EPOCH);
        restart();

        assertThat(engine.getCatalog().get(routed))
            .as("discovery adds a row for every segment file the catalog does not name, and "
                + "promotes it because it holds entries, so a purge is undone by a bounce")
            .isNotNull()
            .extracting(SegmentCatalog.SegmentMetadata::getLifecycleState)
            .isEqualTo(LifecycleState.TOMBSTONED);
        assertThat(engine.getQueryableSegments())
            .as("the segment the reindex superseded must not answer queries again")
            .doesNotContain(engine.getSegment(routed));
    }

    @Test
    @DisplayName("purges a catalog segment no directory slot points at")
    void purgesAnUnroutedSegment() {
        // What a rolling reindex mints: the catalog's own counter runs past the directory's
        // width, so the row it creates names no slot and no file of the engine's.
        long unrouted = engine.getCatalog()
            .create("source-a", tempDir.resolve("volume-a")).getSegmentId();
        assertThat(engine.getSegment(unrouted))
            .as("precondition: no directory slot points at it")
            .isNull();
        engine.getCatalog().beginIndexing(unrouted);
        engine.getCatalog().tombstone(unrouted, engine.currentEpoch());

        engine.getLifecycleManager().checkForReclamationCandidates(FAR_FUTURE_EPOCH);

        assertThat(engine.getCatalog().contains(unrouted))
            .as("a tombstoned row nothing routes to is what reclamation is for; refusing it too "
                + "would leave the catalog growing without bound")
            .isFalse();
    }

    /** Reopens the data directory the way a restart does. */
    private void restart() throws IOException {
        engine.close();
        arena.close();
        arena = Arena.ofShared();
        engine = new StorageEngine(tempDir, arena);
        engine.start();
    }

    /**
     * Writes until some routed segment holds entries, and returns that segment's index.
     *
     * @return The canonical index of a segment that took at least one of the writes
     */
    private long aSegmentHoldingEntries() {
        for (int i = 0; i < 64; i++) {
            engine.put(("reclaim-key-" + i).getBytes(StandardCharsets.UTF_8),
                ("reclaim-value-" + i).getBytes(StandardCharsets.UTF_8));
        }
        return engine.getSegments().entrySet().stream()
            .filter(entry -> entry.getValue().entryCount() > 0)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no segment took any of the writes"));
    }

    /** Retires a segment the way a rolling reindex retires the copy it has replaced. */
    private void tombstone(long segmentIndex) {
        SegmentCatalog catalog = engine.getCatalog();
        if (catalog.get(segmentIndex).getLifecycleState() == LifecycleState.BOOTSTRAPPING) {
            catalog.beginIndexing(segmentIndex);
        }
        engine.tombstoneSegment(segmentIndex);
    }
}
