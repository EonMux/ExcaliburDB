package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the segment swaps the orchestrator has to survive: a compaction replacing a
 * segment instance, and the storage directory changing behind its back.
 *
 * <h2>Why these matter</h2>
 * <ul>
 *   <li>The compacted file is installed by the store at the canonical
 *       {@code segment_N.dat} name. Renaming it again produced {@code segment_N.dat.dat},
 *       which segment discovery cannot find, losing the segment on the next start.</li>
 *   <li>Every worker registration is keyed by {@link Segment} identity, so a swap that
 *       updates only the maps leaves the workers bound to a retired instance.</li>
 * </ul>
 */
class IndexOrchestratorSwapTest {

    private static final int DIMENSION = 16;

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine storageEngine;
    private IndexOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        storageEngine = new StorageEngine(tempDir, arena);
        storageEngine.start();
        orchestrator = new IndexOrchestrator(storageEngine.getCatalog(), storageEngine);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("Compaction installs the replacement at the canonical segment path")
    void compactionKeepsCanonicalSegmentPath() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        assertThat(original).isNotNull();
        original.put("alpha".getBytes(StandardCharsets.UTF_8), "one".getBytes(StandardCharsets.UTF_8));
        orchestrator.registerSegment(0L, original, null, null);

        Segment replacement = compact(original);

        assertThat(replacement.path()).isEqualTo(tempDir.resolve("segment_0.dat"));
        assertThat(Files.exists(tempDir.resolve("segment_0.dat"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("segment_0.dat.dat"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("segment_0.dat.compact"))).isFalse();
    }

    @Test
    @DisplayName("Compaction carries the HNSW graph and re-points the workers")
    void compactionCarriesAttachmentsAndRepointsWorkers() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        HnswGraph graph = new HnswGraph(tempDir.resolve("segment_0.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 100);
        TailOffsetTracker tracker = new TailOffsetTracker(graph);
        original.attachHnswGraph(graph);
        original.attachTailOffsetTracker(tracker);
        orchestrator.registerSegment(0L, original, graph, tracker);

        Segment replacement = compact(original);

        // The query path reads the graph off the segment; a replacement without it drops
        // the whole segment out of vector search.
        assertThat(replacement.getHnswGraph()).isSameAs(graph);
        assertThat(replacement.getTailOffsetTracker()).isSameAs(tracker);

        // Exactly one registration each, and it is the replacement: a reconciliation pass
        // that leaves segment 0 alone - it only registers the other, never-registered
        // segments - proves the registry already points at the live instance.
        assertThat(orchestrator.getHnswWorker().getRegisteredSegmentCount()).isEqualTo(1);
        assertThat(orchestrator.getCompactionWorker().getRegisteredSegmentCount()).isEqualTo(1);
        assertThat(orchestrator.syncSegmentsFromStorage())
            .isEqualTo(storageEngine.getSegments().size() - 1);

        graph.close();
    }

    @Test
    @DisplayName("Compaction carries the vector pre-filter to the replacement, still open")
    void compactionCarriesTheVectorPrefilter() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        com.eonmux.excalibur.filter.lsh.LshIndex prefilter = com.eonmux.excalibur.filter.lsh.LshIndex.create(DIMENSION);
        original.attachLshIndex(prefilter);
        orchestrator.registerSegment(0L, original, null, null);

        Segment replacement = compact(original);

        // The pre-filter is keyed by HNSW node id and compaction does not renumber nodes, so it
        // describes the replacement exactly as well as it described the original. Dropped here
        // instead, a segment loses its pre-filter on its first compaction and never gets it back.
        assertThat(replacement.getLshIndex()).isSameAs(prefilter);

        // The old segment closes as soon as the swap returns, and it closes whatever is still
        // attached to it - so carrying the reference across without detaching it first would hand
        // the replacement an index whose mapped files have been released.
        assertThat(prefilter.isClosed())
            .as("the replacement must not be left holding a closed pre-filter")
            .isFalse();

        prefilter.close();
    }

    @Test
    @DisplayName("A swap the registry refuses does not strand the indexes on the replacement")
    void aRefusedSwapReturnsTheAttachments() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        HnswGraph graph = new HnswGraph(tempDir.resolve("segment_0.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 100);
        TailOffsetTracker tracker = new TailOffsetTracker(graph);
        com.eonmux.excalibur.filter.lsh.LshIndex prefilter = com.eonmux.excalibur.filter.lsh.LshIndex.create(DIMENSION);
        original.attachHnswGraph(graph);
        original.attachTailOffsetTracker(tracker);
        original.attachLshIndex(prefilter);
        orchestrator.registerSegment(0L, original, graph, tracker);

        // Put a different segment in the directory slot the orchestrator still associates with
        // the original. This is the state a split reaches by publishing its children while a
        // compaction of the parent is in flight, and it is what makes replaceSegment refuse:
        // the segment it is asked to replace is no longer the one the registry holds.
        Segment decoy = new Segment(tempDir.resolve("decoy.dat"), 1024 * 1024, arena);
        assertThat(storageEngine.replaceSegment(0L, original, decoy)).isTrue();

        orchestrator.getCompactionWorker().compactNow(original);

        // The replacement is never installed and never closed, so anything left attached to it
        // is a mapping nothing will ever release - one file per LSH hash table, plus the graph.
        // Handing them back to the segment that owned them puts them under a close that happens.
        assertThat(prefilter.isClosed())
            .as("a pre-filter left on an uninstalled replacement is a mapping nobody closes")
            .isTrue();

        decoy.close();
    }

    @Test
    @DisplayName("A segment the orchestrator has drifted on is swapped at the directory's index")
    void aDriftedRegistrationSwapsAtTheDirectoryIndex() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        original.put("alpha".getBytes(StandardCharsets.UTF_8), "one".getBytes(StandardCharsets.UTF_8));

        // The orchestrator remembers this segment under an index the directory does not use for
        // it. That drift is what makes replaceSegment refuse: it compares the segment it holds at
        // the index it is given, and index 7 holds nothing at all.
        orchestrator.registerSegment(7L, original, null, null);

        Segment replacement = compact(original);

        // Swapped at 0, where the directory actually holds it, rather than refused at 7.
        assertThat(storageEngine.getSegment(0L))
            .as("the directory must now route index 0 to the compacted segment")
            .isSameAs(replacement);
        assertThat(original.isClosed())
            .as("the original is retired only because the swap succeeded")
            .isTrue();

        // A refused swap used to leave the directory routing to a segment the worker then closed.
        assertThat(storageEngine.getSegment(0L).isClosed())
            .as("the directory must never be left routing to a closed segment")
            .isFalse();
    }

    @Test
    @DisplayName("Reconciliation registers directory segments and retires departed ones")
    void reconciliationConvergesOnTheStorageDirectory() {
        assertThat(orchestrator.getRegisteredSegmentCount()).isZero();

        int registered = orchestrator.syncSegmentsFromStorage();

        int distinctSegments = storageEngine.getSegments().size();
        assertThat(distinctSegments).isPositive();
        assertThat(registered).isEqualTo(distinctSegments);
        assertThat(orchestrator.getRegisteredSegmentCount()).isEqualTo(distinctSegments);

        // A second pass has nothing to do.
        assertThat(orchestrator.syncSegmentsFromStorage()).isZero();

        // A segment the engine no longer holds is retired.
        orchestrator.registerSegment(9999L, storageEngine.getSegment(0L), null, null);
        assertThat(orchestrator.syncSegmentsFromStorage()).isEqualTo(1);
        assertThat(orchestrator.getRegisteredSegmentCount()).isEqualTo(distinctSegments);
    }

    @Test
    @DisplayName("Compaction of a segment the orchestrator never registered still swaps the directory")
    void compactionOfUnregisteredSegmentStillSwapsTheDirectory() throws Exception {
        Segment original = storageEngine.getSegment(0L);
        // Known to the compaction worker but never registered with the orchestrator.
        orchestrator.getCompactionWorker().registerSegment(original);

        // compact() fails unless the storage directory stops resolving to the old
        // instance, which the compaction worker closes as soon as the listener returns.
        Segment replacement = compact(original);

        assertThat(storageEngine.getSegment(0L)).isSameAs(replacement);
        assertThat(replacement.path()).isEqualTo(tempDir.resolve("segment_0.dat"));
    }

    /**
     * Compacts a segment and waits for the swap to be published.
     *
     * @param segment The segment to compact
     * @return The replacement installed in the storage directory
     */
    private Segment compact(Segment segment) throws IOException, InterruptedException {
        orchestrator.getCompactionWorker().requestCompaction(segment);

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        Segment current = storageEngine.getSegment(0L);
        while (current == segment && System.nanoTime() < deadline) {
            Thread.sleep(10);
            current = storageEngine.getSegment(0L);
        }
        assertThat(current).isNotSameAs(segment);

        // The registry swap is not the end of the swap. The orchestrator quiesces the old
        // registration before it touches anything the integration worker shares - that worker
        // holds the same tracker and the old segment's still-populated log, and takes no lock -
        // and only re-registers the replacement afterwards. So a test that stops looking as soon
        // as the directory changed can observe the gap in between and read a count of zero for a
        // swap that is proceeding normally.
        awaitRegistrationsSettled(deadline);
        return current;
    }

    /**
     * Waits for the orchestrator to finish re-pointing its workers at the replacement.
     *
     * @param deadline Nanotime after which to stop waiting
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitRegistrationsSettled(long deadline) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            if (orchestrator.getHnswWorker().getRegisteredSegmentCount() > 0
                && orchestrator.getCompactionWorker().getRegisteredSegmentCount() > 0) {
                return;
            }
            Thread.sleep(10);
        }
    }
    @Test
    @DisplayName("A split child's inherited checkpoint is rebased onto its own empty write log")
    void splitChildCheckpointIsRebased() throws Exception {
        // The left child of a split takes over the parent's file names, the HNSW file among
        // them, and with it the parent's integration checkpoint -- while prepareSplitChild
        // hands it a brand new, empty write log. The integration worker only looks for work
        // when lastIntegratedOffset < writeLogHead, so an inherited checkpoint above an empty
        // log means it never looks again: everything written to that child from then on is
        // stored, acknowledged, and never indexed. Nothing throws; the segment just stops
        // answering vector queries about its own newest data.
        Segment parent = storageEngine.getSegment(0L);
        assertThat(parent).isNotNull();

        Path scratch = Files.createDirectories(tempDir.resolve("split-child-stale"));
        Segment child = new Segment(scratch.resolve("segment_1.dat"), 1024 * 1024, arena);
        HnswGraph inherited = new HnswGraph(scratch.resolve("segment_1.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1000);
        TailOffsetTracker tracker = new TailOffsetTracker(inherited);
        // Simulate the parent's persisted progress: the graph's own header carries it, and
        // resetTo is the only way in or out of it.
        tracker.resetTo(40_000_000L);
        child.attachHnswGraph(inherited);
        child.attachTailOffsetTracker(tracker);

        assertThat(tracker.getLastIntegratedOffset()).isEqualTo(40_000_000L);
        assertThat(child.getWriteLogHead()).isZero();

        orchestrator.onSplitComplete(parent, child, child);

        assertThat(tracker.getLastIntegratedOffset())
            .as("rebased onto the child's own log head, so appends are integrable again")
            .isEqualTo(child.getWriteLogHead());

        child.close();
    }

    @Test
    @DisplayName("A split child whose checkpoint already fits its log is left alone")
    void splitChildWithValidCheckpointIsUntouched() throws Exception {
        // Only a checkpoint that outran its log is meaningless. One that fits is real progress
        // and rewinding it would re-integrate everything below it.
        Segment parent = storageEngine.getSegment(0L);

        Path scratch = Files.createDirectories(tempDir.resolve("split-child-valid"));
        Segment child = new Segment(scratch.resolve("segment_2.dat"), 1024 * 1024, arena);
        HnswGraph graph = new HnswGraph(scratch.resolve("segment_2.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1000);
        TailOffsetTracker tracker = new TailOffsetTracker(graph);
        child.attachHnswGraph(graph);
        child.attachTailOffsetTracker(tracker);

        assertThat(tracker.getLastIntegratedOffset()).isZero();

        orchestrator.onSplitComplete(parent, child, child);

        assertThat(tracker.getLastIntegratedOffset()).isZero();

        child.close();
    }

    @Test
    @DisplayName("A split child's own entries are indexed, not just carried as data")
    void splitChildEntriesReachTheGraph() throws Exception {
        // A split hands each child a share of the parent's entries and a brand new, empty write
        // log. Vectors only ever reach a graph through that log - nothing reads a segment's data
        // file into one - so a child's entries stayed retrievable by key and invisible to vector
        // search, with no error and nothing that would ever repair it.
        Segment parent = storageEngine.getSegment(0L);
        assertThat(parent).isNotNull();

        Path scratch = Files.createDirectories(tempDir.resolve("split-child-entries"));
        Segment child = new Segment(scratch.resolve("segment_3.dat"), 1024 * 1024, arena);
        for (int i = 0; i < 12; i++) {
            child.put(chunkKey(i), com.eonmux.excalibur.core.codec.ChunkPayloadCodec.encode(
                "chunk " + i, unitVector(i)));
        }

        HnswGraph graph = new HnswGraph(scratch.resolve("segment_3.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1000);
        TailOffsetTracker tracker = new TailOffsetTracker(graph);
        child.attachHnswGraph(graph);
        child.attachTailOffsetTracker(tracker);

        assertThat(graph.nodeCount()).isZero();

        orchestrator.onSplitComplete(parent, child, child);

        assertThat(graph.nodeCount())
            .as("every live entry of the child is in its graph")
            .isEqualTo(12);
        assertThat(graph.getDocumentId(0L))
            .as("and each node knows which document it came from")
            .isNotNull();

        child.close();
    }

    @Test
    @DisplayName("A split child that is already indexed is not indexed twice")
    void splitChildWithAPopulatedGraphIsLeftAlone() throws Exception {
        Segment parent = storageEngine.getSegment(0L);

        Path scratch = Files.createDirectories(tempDir.resolve("split-child-populated"));
        Segment child = new Segment(scratch.resolve("segment_4.dat"), 1024 * 1024, arena);
        child.put(chunkKey(0), com.eonmux.excalibur.core.codec.ChunkPayloadCodec.encode(
            "chunk 0", unitVector(0)));

        HnswGraph graph = new HnswGraph(scratch.resolve("segment_4.hnsw"),
            HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1000);
        graph.insert(unitVector(0));
        TailOffsetTracker tracker = new TailOffsetTracker(graph);
        child.attachHnswGraph(graph);
        child.attachTailOffsetTracker(tracker);

        orchestrator.onSplitComplete(parent, child, child);

        assertThat(graph.nodeCount())
            .as("a graph that already describes the child is not rebuilt on top of itself")
            .isEqualTo(1);

        child.close();
    }

    private static byte[] chunkKey(int i) {
        return ("src:doc-" + i + ":chunk:0").getBytes(StandardCharsets.UTF_8);
    }

    private static float[] unitVector(int index) {
        float[] vector = new float[DIMENSION];
        vector[Math.floorMod(index, DIMENSION)] = 1.0f;
        return vector;
    }
}
