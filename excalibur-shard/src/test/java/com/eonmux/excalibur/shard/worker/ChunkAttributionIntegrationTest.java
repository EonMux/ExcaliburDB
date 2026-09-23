package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration worker attributing nodes to chunks as it replays a segment's write log.
 *
 * <p>Storage holds one value per chunk id; the graph gains a node per value it is ever given. What
 * these tests pin down is that replaying the log in order - PUTs as supersessions, DELETEs as
 * retirements - leaves exactly one searchable node for each chunk storage holds and none for a
 * chunk it does not, and that a graph an older build attributed by document is re-keyed from the
 * segment before anything more is integrated into it.</p>
 */
@DisplayName("Chunk attribution during integration")
class ChunkAttributionIntegrationTest {

    private static final int DIMENSION = 8;
    private static final String CHUNK_0 = "source:doc:chunk:0";
    private static final String CHUNK_1 = "source:doc:chunk:1";
    private static final String CHUNK_2 = "source:doc:chunk:2";

    @TempDir
    Path tempDir;

    private static float[] vectorOf(int seed) {
        float[] vector = new float[DIMENSION];
        for (int d = 0; d < DIMENSION; d++) {
            vector[d] = seed * 10f + d;
        }
        return vector;
    }

    private static byte[] key(String chunkId) {
        return chunkId.getBytes(StandardCharsets.UTF_8);
    }

    private static void put(Segment segment, String chunkId, int seed) {
        segment.put(key(chunkId), ChunkPayloadCodec.encode("text " + seed, vectorOf(seed)));
    }

    private static HnswIntegrationWorker worker() {
        return new HnswIntegrationWorker(new SegmentCatalog(), (s, c) -> { });
    }

    private HnswGraph newGraph(String name) throws IOException {
        return new HnswGraph(tempDir.resolve(name), HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), 1024L * 1024L);
    }

    private static Set<Long> liveNodesOf(HnswGraph graph, String chunkId) {
        return graph.getNodeDocumentMapping().getNodesForChunk(chunkId).stream()
            .filter(node -> !graph.isTombstoned(node))
            .collect(Collectors.toSet());
    }

    private static List<String> rankedChunks(HnswGraph graph, float[] query) {
        return graph.search(query, 10).stream().map(HnswSearchResult::documentId).toList();
    }

    @Test
    @DisplayName("a chunk written again keeps only its newest vector searchable")
    void aRewrittenChunkSupersedesItsOldVector() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("repost.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("repost.wal"));
             HnswGraph graph = newGraph("repost.graph")) {
            segment.attachWriteLog(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            HnswIntegrationWorker worker = worker();

            put(segment, CHUNK_0, 1);
            put(segment, CHUNK_1, 2);
            assertThat(worker.drainPendingIntegration(segment, graph, tracker)).isEqualTo(2);
            long first = liveNodesOf(graph, CHUNK_0).iterator().next();

            put(segment, CHUNK_0, 3);
            assertThat(worker.drainPendingIntegration(segment, graph, tracker)).isOne();

            assertThat(graph.isTombstoned(first))
                .as("the old text's vector is retired by the value that replaced it").isTrue();
            assertThat(liveNodesOf(graph, CHUNK_0)).hasSize(1).doesNotContain(first);
            assertThat(rankedChunks(graph, vectorOf(1)))
                .as("a query for the old text ranks each chunk once, by its current vector")
                .containsExactlyInAnyOrder(CHUNK_0, CHUNK_1);
        }
    }

    @Test
    @DisplayName("a deleted chunk's vector is retired, durably, with nothing else to integrate")
    void aDeletedChunkIsRetired() throws IOException {
        Path graphPath = tempDir.resolve("erase.graph");
        long head;
        long erased;
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("erase.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("erase.wal"));
             HnswGraph graph = newGraph("erase.graph")) {
            segment.attachWriteLog(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            HnswIntegrationWorker worker = worker();

            put(segment, CHUNK_0, 1);
            put(segment, CHUNK_1, 2);
            worker.drainPendingIntegration(segment, graph, tracker);
            erased = liveNodesOf(graph, CHUNK_1).iterator().next();

            assertThat(segment.delete(key(CHUNK_1))).isTrue();
            head = segment.getWriteLogHead();
            assertThat(worker.drainPendingIntegration(segment, graph, tracker))
                .as("a deletion inserts nothing").isZero();

            assertThat(graph.isTombstoned(erased)).isTrue();
            assertThat(tracker.getLastIntegratedOffset()).isEqualTo(head);
            assertThat(rankedChunks(graph, vectorOf(2))).containsExactly(CHUNK_0);
        }

        try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
            assertThat(reopened.getLastIntegratedOffset()).isEqualTo(head);
            assertThat(reopened.isTombstoned(erased))
                .as("the checkpoint past the deletion is not durable without its tombstone")
                .isTrue();
        }
    }

    @Test
    @DisplayName("deletions and rewrites in one batch apply in the order the log holds them")
    void oneBatchReplaysInLogOrder() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("order.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("order.wal"));
             HnswGraph graph = newGraph("order.graph")) {
            segment.attachWriteLog(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);

            put(segment, CHUNK_0, 1);
            segment.delete(key(CHUNK_0));
            put(segment, CHUNK_0, 3);       // erased, then written again: this value stands
            put(segment, CHUNK_1, 2);
            segment.delete(key(CHUNK_1));   // written, then erased: nothing stands

            assertThat(worker().drainPendingIntegration(segment, graph, tracker)).isEqualTo(3);

            assertThat(liveNodesOf(graph, CHUNK_0)).hasSize(1);
            assertThat(liveNodesOf(graph, CHUNK_1)).isEmpty();
            assertThat(graph.search(vectorOf(3), 10))
                .extracting(HnswSearchResult::documentId).containsExactly(CHUNK_0);
            assertThat(graph.search(vectorOf(3), 1).getFirst().distance())
                .as("the node standing is the newest value's").isZero();
        }
    }

    /**
     * Rewrites a mapping file as a build that attributed nodes to documents wrote it: the same
     * layout, at version 2, sealed again because the header checksum covers the version.
     */
    private static void markDocumentKeyed(Path mapping) throws IOException {
        byte[] bytes = Files.readAllBytes(mapping);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES, NodeDocumentMapping.VERSION_DOCUMENT_KEYED);
        header.putLong(40, IndexChecksum.of(bytes, 0, 40));
        Files.write(mapping, bytes);
    }

    @Test
    @DisplayName("a graph an older build keyed by document is re-keyed before integrating more")
    void aDocumentKeyedGraphIsUpgradedFirst() throws IOException {
        Path graphPath = tempDir.resolve("legacy.graph");
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("legacy.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("legacy.wal"))) {
            segment.attachWriteLog(writeLog);

            // Storage as the older build left it: chunk 0 re-posted with new text, chunk 1 not.
            put(segment, CHUNK_0, 1);
            put(segment, CHUNK_1, 2);
            put(segment, CHUNK_0, 3);

            long stale;
            try (HnswGraph graph = newGraph("legacy.graph")) {
                // ...and its graph: a node per value ever written, each named by the document.
                stale = graph.insert(vectorOf(1));
                graph.registerDocumentId(stale, "source:doc");
                graph.registerDocumentId(graph.insert(vectorOf(2)), "source:doc");
                graph.registerDocumentId(graph.insert(vectorOf(3)), "source:doc");
                TailOffsetTracker tracker = new TailOffsetTracker(graph);
                tracker.compareAndSetOffset(segment.getWriteLogHead());
                tracker.flush();
            }
            markDocumentKeyed(Path.of(graphPath + HnswGraph.DOCUMENT_MAPPING_SUFFIX));

            put(segment, CHUNK_2, 4);   // written after this build took over

            try (HnswGraph graph = HnswGraph.open(graphPath, L2Distance.getInstance())) {
                assertThat(graph.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
                TailOffsetTracker tracker = new TailOffsetTracker(graph);

                assertThat(worker().drainPendingIntegration(segment, graph, tracker)).isOne();

                assertThat(graph.attributionKeying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
                assertThat(graph.isTombstoned(stale))
                    .as("the replaced text's vector has no live chunk to be named as").isTrue();
                assertThat(liveNodesOf(graph, CHUNK_0)).hasSize(1);
                assertThat(liveNodesOf(graph, CHUNK_1)).hasSize(1);
                assertThat(liveNodesOf(graph, CHUNK_2)).hasSize(1);
                assertThat(rankedChunks(graph, vectorOf(1)))
                    .containsExactlyInAnyOrder(CHUNK_0, CHUNK_1, CHUNK_2);
            }

            try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
                assertThat(reopened.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            }
        }
    }
}
