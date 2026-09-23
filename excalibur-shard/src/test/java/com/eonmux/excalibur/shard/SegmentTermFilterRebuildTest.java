package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment's level in the membership filter holds exactly the terms of the entries the segment
 * holds, whatever produced the segment.
 *
 * <p>Stage 1 of the query pipeline searches a segment last when the filter "covers" it and none
 * of the query's terms is in its level ({@code TermFilterCascade.mayHoldQueryTerms}). Ingestion files
 * a chunk's terms under the segment the write landed in, and nothing else ever fills a level. So
 * any segment that comes to hold entries without their writes passing through ingestion - the
 * right child of a split, a segment reopened after a restart - holds terms its level never saw,
 * and the first write that lands in it makes the level non-empty, which is all "covers" asks.
 * From then on a query for anything the segment held before searches the segment that holds it
 * behind every other: no error, just the wrong order, and the wrong answer for a query that runs
 * out of time. Each test reproduces one way a segment gets there and asks stage 1's own question
 * for every entry.</p>
 */
@DisplayName("Segment term filter levels after split, restart and compaction")
class SegmentTermFilterRebuildTest {

    private static final int CHUNKS = 400;
    private static final long CORPUS_CAPACITY = 200_000;
    private static final int SEGMENT_CAPACITY = 10_000;
    private static final int FINGERPRINT_BITS = 13;
    private static final long SETTLE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);

    @TempDir
    Path tempDir;

    private Arena arena;
    private StorageEngine engine;
    private IndexOrchestrator orchestrator;
    private HierarchicalCuckooFilter filter;

    @AfterEach
    void tearDown() {
        closeNode();
        if (arena != null) {
            arena.close();
        }
    }

    @Test
    @DisplayName("stage 1 keeps a split's right child for the terms it inherited from its parent")
    void rightChildKeepsInheritedTerms() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, CHUNKS);

        Split split = splitPopulatedSegment();
        Segment right = engine.getSegment(split.rightId());
        List<Chunk> inRight = chunksIn(chunks, right);
        assertThat(inRight).as("the split must move some entries right").isNotEmpty();

        // The write that makes the right child's level non-empty, and with it "covered".
        Chunk fresh = ingestInto(split.rightId(), CHUNKS);
        assertThat(fresh).isNotNull();

        assertEveryChunkInFrontAtStageOne(inRight, split.rightId());
        awaitCovered(split.rightId());
        assertEveryChunkInFrontAtStageOne(inRight, split.rightId());
        assertThat(inFrontAtStageOne(fresh.term(), split.rightId())).isTrue();
    }

    @Test
    @DisplayName("a split's left child stops answering for the terms that moved right")
    void leftChildForgetsTermsThatMovedRight() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, CHUNKS);

        Split split = splitPopulatedSegment();
        Segment left = engine.getSegment(split.leftId());
        Segment right = engine.getSegment(split.rightId());
        List<Chunk> inLeft = chunksIn(chunks, left);
        List<Chunk> inRight = chunksIn(chunks, right);

        awaitCovered(split.leftId());
        assertEveryChunkInFrontAtStageOne(inLeft, split.leftId());
        // Terms are unique per chunk, so a term that moved right is one the left child no longer
        // holds. A cuckoo filter may still answer yes for a few by fingerprint collision; a level
        // still carrying the parent's whole vocabulary answers yes for all of them.
        assertThat(countReported(inRight, split.leftId()))
            .as("terms of entries the left child no longer holds")
            .isLessThanOrEqualTo(Math.max(1, inRight.size() / 20));
    }

    @Test
    @DisplayName("after a restart, a write into a segment does not hide what it held before")
    void restartedSegmentKeepsItsEarlierTerms() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, CHUNKS);
        closeNode();

        openNode();
        Chunk fresh = ingest(CHUNKS, 1).get(0);
        Segment landed = engine.getSegment(fresh.segmentId());
        List<Chunk> sameSegment = chunksIn(chunks, landed);
        assertThat(sameSegment).as("earlier chunks in the segment the new write landed in")
            .isNotEmpty();

        assertEveryChunkInFrontAtStageOne(sameSegment, fresh.segmentId());
        awaitCovered(fresh.segmentId());
        assertEveryChunkInFrontAtStageOne(sameSegment, fresh.segmentId());
        assertThat(inFrontAtStageOne(fresh.term(), fresh.segmentId())).isTrue();
    }

    @Test
    @DisplayName("a compacted segment's level holds its live entries' terms and not the deleted ones")
    void compactionLeavesALevelExactForTheLiveEntries() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, CHUNKS);
        long segmentId = chunks.get(0).segmentId();
        Segment original = engine.getSegment(segmentId);
        List<Chunk> inSegment = chunksIn(chunks, original);

        List<Chunk> deleted = new ArrayList<>();
        List<Chunk> kept = new ArrayList<>();
        for (int i = 0; i < inSegment.size(); i++) {
            Chunk chunk = inSegment.get(i);
            if (i % 2 == 0) {
                assertThat(engine.delete(chunk.key())).isTrue();
                deleted.add(chunk);
            } else {
                kept.add(chunk);
            }
        }

        orchestrator.getCompactionWorker().requestCompaction(original);
        awaitCondition(() -> engine.getSegment(segmentId) != original, "compaction swap");
        awaitCondition(() -> countReported(deleted, segmentId)
            <= Math.max(1, deleted.size() / 20) && filter.coversSegment(key(segmentId)),
            "the compacted segment's level to be rebuilt");

        assertEveryChunkInFrontAtStageOne(kept, segmentId);
    }

    @Test
    @DisplayName("the advance notice of a split stops both children's levels from ruling them out")
    void advanceNoticeMarksBothChildren() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, 50);
        long parentId = chunks.get(0).segmentId();
        long rightId = parentId + engine.getSegmentCount();
        assertThat(orchestrator.awaitTermFilterRebuilds(20, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment(key(parentId))).isTrue();

        orchestrator.onSplitPublishing(engine.getSegment(parentId), parentId, rightId);

        assertThat(filter.coversSegment(key(parentId))).isFalse();
        // A write the right child takes before its bookkeeping runs creates a level that is
        // already stale.
        filter.registerSegment(key(rightId));
        filter.insert("zqfresh".getBytes(StandardCharsets.UTF_8), key(rightId));
        assertThat(filter.coversSegment(key(rightId))).isFalse();
    }

    @Test
    @DisplayName("an abandoned split gives the parent its coverage back")
    void abandonedSplitRestoresTheParent() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, 50);
        long parentId = chunks.get(0).segmentId();
        Segment parent = engine.getSegment(parentId);
        long rightId = parentId + engine.getSegmentCount();
        List<Chunk> inParent = chunksIn(chunks, parent);

        orchestrator.onSplitPublishing(parent, parentId, rightId);
        orchestrator.onSplitAbandoned(parent, parentId, rightId);

        assertThat(orchestrator.awaitTermFilterRebuilds(20, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment(key(parentId))).isTrue();
        assertEveryChunkInFrontAtStageOne(inParent, parentId);
        assertThat(filter.isSegmentStale(key(rightId)))
            .as("nothing serves the right index, so nothing vouches for it")
            .isTrue();
    }

    @Test
    @DisplayName("a filter configured after segments are registered is refilled from them")
    void lateFilterIsRefilled() throws Exception {
        openNode();
        List<Chunk> chunks = ingest(0, 100);
        closeNode();

        engine = new StorageEngine(tempDir, arena);
        engine.start();
        orchestrator = new IndexOrchestrator(engine.getCatalog(), engine);
        for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
            orchestrator.registerSegment(entry.getKey(), entry.getValue(), null, null, null);
        }
        filter = new HierarchicalCuckooFilter(CORPUS_CAPACITY, FINGERPRINT_BITS, SEGMENT_CAPACITY);
        orchestrator.setHierarchicalCuckooFilter(filter);

        assertThat(orchestrator.awaitTermFilterRebuilds(20, TimeUnit.SECONDS)).isTrue();
        for (Chunk chunk : chunks) {
            assertThat(filter.coversSegment(key(chunk.segmentId()))).isTrue();
            assertThat(filter.mightContainInSegment(chunk.term().getBytes(StandardCharsets.UTF_8),
                key(chunk.segmentId()))).as(chunk.term()).isTrue();
        }
    }

    // ==================== Node ====================

    /** Opens the engine, orchestrator and filter the way the API bootstrap does. */
    private void openNode() {
        if (arena == null) {
            arena = Arena.ofShared();
        }
        engine = new StorageEngine(tempDir, arena);
        engine.start();
        orchestrator = new IndexOrchestrator(engine.getCatalog(), engine);
        filter = new HierarchicalCuckooFilter(CORPUS_CAPACITY, FINGERPRINT_BITS, SEGMENT_CAPACITY);
        orchestrator.setHierarchicalCuckooFilter(filter);
        for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
            Segment segment = entry.getValue();
            orchestrator.registerSegment(entry.getKey(), segment, segment.getHnswGraph(), null,
                segment.getTailOffsetTracker());
        }
        orchestrator.start();
    }

    private void closeNode() {
        if (orchestrator != null) {
            orchestrator.close();
            orchestrator = null;
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    // ==================== Ingestion, as SegmentTermRecorder does it ====================

    /** A written chunk: its key, its one distinctive term, and where the write landed. */
    private record Chunk(byte[] key, String term, long segmentId) {
    }

    private record Split(long leftId, long rightId) {
    }

    private List<Chunk> ingest(int from, int count) {
        List<Chunk> written = new ArrayList<>(count);
        for (int i = from; i < from + count; i++) {
            written.add(write(i));
        }
        return written;
    }

    /** Writes chunks until one lands in the given segment, and returns it. */
    private Chunk ingestInto(long segmentId, int from) {
        for (int i = from; i < from + 10_000; i++) {
            Chunk chunk = write(i);
            if (chunk.segmentId() == segmentId) {
                return chunk;
            }
        }
        return null;
    }

    private Chunk write(int i) {
        String term = termFor(i);
        byte[] key = ("doc-" + i + "#0").getBytes(StandardCharsets.UTF_8);
        String text = "shared " + term;
        long segmentId = engine.putReportingSegment(key, ChunkPayloadCodec.encodeTextOnly(text));
        String segment = key(segmentId);
        filter.registerSegment(segment);
        for (String extracted : TermExtractor.extractUniqueTerms(text)) {
            filter.insert(extracted.getBytes(StandardCharsets.UTF_8), segment);
        }
        return new Chunk(key, term, segmentId);
    }

    /** A term unique to one chunk, alphabetic so the extractor keeps it whole. */
    private static String termFor(int i) {
        StringBuilder term = new StringBuilder("zq");
        int n = i;
        do {
            term.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return term.toString();
    }

    // ==================== Split ====================

    private Split splitPopulatedSegment() throws InterruptedException {
        int routing = engine.getSegmentCount();
        Segment parent = null;
        long parentId = -1;
        for (Map.Entry<Long, Segment> entry : engine.getSegments().entrySet()) {
            if (parent == null || entry.getValue().entryCount() > parent.entryCount()) {
                parent = entry.getValue();
                parentId = entry.getKey();
            }
        }
        assertThat(parent).isNotNull();
        Segment splitting = parent;
        engine.getSplitWorker().requestSplit(splitting, Integer.numberOfTrailingZeros(routing));
        awaitCondition(splitting::isSplitComplete, "the split");
        return new Split(parentId, parentId + routing);
    }

    // ==================== Stage 1's question ====================

    /**
     * Whether stage 1 keeps a segment among those searched first, for a query of one term.
     *
     * <p>The rule of {@code TermFilterCascade.mayHoldQueryTerms}: the segment stays in front
     * when the filter names it for the term, or when the filter does not cover it.</p>
     */
    private boolean inFrontAtStageOne(String term, long segmentId) {
        String segment = key(segmentId);
        return filter.findCandidateSegments(term.getBytes(StandardCharsets.UTF_8)).contains(segment)
            || !filter.coversSegment(segment);
    }

    private void assertEveryChunkInFrontAtStageOne(List<Chunk> chunks, long segmentId) {
        List<String> movedBack = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (!inFrontAtStageOne(chunk.term(), segmentId)) {
                movedBack.add(chunk.term());
            }
        }
        assertThat(movedBack)
            .as("terms held by segment %s for which stage 1 would search it last", segmentId)
            .isEmpty();
    }

    private int countReported(List<Chunk> chunks, long segmentId) {
        int reported = 0;
        for (Chunk chunk : chunks) {
            if (filter.mightContainInSegment(chunk.term().getBytes(StandardCharsets.UTF_8),
                    key(segmentId))) {
                reported++;
            }
        }
        return reported;
    }

    private static List<Chunk> chunksIn(List<Chunk> chunks, Segment segment) {
        List<Chunk> inSegment = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (segment.get(chunk.key()) != null) {
                inSegment.add(chunk);
            }
        }
        return inSegment;
    }

    private static String key(long segmentId) {
        return String.valueOf(segmentId);
    }

    private void awaitCovered(long segmentId) throws InterruptedException {
        awaitCondition(() -> filter.coversSegment(key(segmentId)),
            "segment " + segmentId + "'s level to cover it");
    }

    private static void awaitCondition(BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }
}
