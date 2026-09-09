package excalibur.shard.worker;

import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.store.Segment;
import io.excalibur.store.VectorPrefilter;
import io.excalibur.store.WriteLog;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.HnswSearchResult;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vector pre-filter is keyed by the ids the graph assigns, and is written where they exist.
 *
 * <p>Stage 2 of the query pipeline hands a pre-filter's answer to
 * {@code HnswGraph.search(query, k, candidateIds)}, which keeps only the results whose node id is
 * in that set. A pre-filter populated from any other id space - a chunk id hash, a write log
 * offset - therefore does not narrow a search, it empties it: every candidate names a node the
 * graph does not have, so the segment returns nothing and its vectors become unreachable.</p>
 *
 * <p>Node ids are assigned as the integration worker absorbs entries from the write log, which is
 * why the worker is the component that writes the pre-filter.</p>
 */
@DisplayName("Vector pre-filter mirroring")
class VectorPrefilterMirroringTest {

    private static final int DIMENSION = 16;
    private static final int VECTORS = 40;

    @TempDir
    Path tempDir;

    /** Records what the worker offers, without interpreting it. */
    private static final class RecordingPrefilter implements VectorPrefilter {
        private final Map<Long, float[]> indexed = new LinkedHashMap<>();
        private int flushes;

        @Override
        public void initialize(Segment segment) {
            // Attachment is the storage engine's business; this test drives the worker directly.
        }

        @Override
        public void discardPersistedState(Segment segment) {
            // Nothing is persisted.
        }

        @Override
        public void indexVector(Segment segment, float[] vector, long nodeId) {
            indexed.put(nodeId, vector);
        }

        @Override
        public void flush(Segment segment) {
            flushes++;
        }
    }

    @Nested
    @DisplayName("Integration populates the pre-filter")
    class Population {

        @Test
        @DisplayName("every integrated vector is offered under the node id the graph assigned it")
        void mirrorsUnderGraphNodeIds() throws IOException {
            RecordingPrefilter prefilter = new RecordingPrefilter();

            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(tempDir.resolve("segment.dat"), 4L * 1024 * 1024, arena);
                 WriteLog writeLog = new WriteLog(tempDir.resolve("segment.wal"));
                 HnswGraph graph = new HnswGraph(tempDir.resolve("segment.graph"),
                     HnswConfig.create(DIMENSION), L2Distance.getInstance(), VECTORS * 4L)) {

                segment.attachWriteLog(writeLog);
                List<float[]> vectors = writeChunks(writeLog);

                HnswIntegrationWorker worker = new HnswIntegrationWorker(new io.excalibur.store.SegmentCatalog(), (s, c) -> { });
                worker.setVectorPrefilter(prefilter);

                TailOffsetTracker tracker = new TailOffsetTracker(graph);
                int integrated = worker.drainPendingIntegration(segment, graph, tracker);

                assertThat(integrated)
                    .as("every written chunk carries a vector, so all of them integrate")
                    .isEqualTo(VECTORS);

                assertThat(prefilter.indexed.keySet())
                    .as("the pre-filter is offered exactly the node ids the graph assigned")
                    .containsExactlyInAnyOrderElementsOf(nodeIdsOf(graph));

                assertThat(prefilter.flushes)
                    .as("the pre-filter is made durable with the checkpoint that vouches for it")
                    .isGreaterThan(0);

                // The property all of this exists for: the ids are usable as a candidate set.
                for (int i = 0; i < vectors.size(); i++) {
                    Set<Long> candidates = prefilter.indexed.keySet();
                    List<HnswSearchResult> restricted =
                        graph.search(vectors.get(i), 5, candidates);
                    assertThat(restricted)
                        .as("a search restricted to the pre-filter's ids still finds vector %d", i)
                        .isNotEmpty();
                }
            }
        }

        @Test
        @DisplayName("integration proceeds normally when no pre-filter is wired")
        void integratesWithoutPrefilter() throws IOException {
            try (Arena arena = Arena.ofShared();
                 Segment segment = new Segment(tempDir.resolve("bare.dat"), 4L * 1024 * 1024, arena);
                 WriteLog writeLog = new WriteLog(tempDir.resolve("bare.wal"));
                 HnswGraph graph = new HnswGraph(tempDir.resolve("bare.graph"),
                     HnswConfig.create(DIMENSION), L2Distance.getInstance(), VECTORS * 4L)) {

                segment.attachWriteLog(writeLog);
                writeChunks(writeLog);

                HnswIntegrationWorker worker = new HnswIntegrationWorker(new io.excalibur.store.SegmentCatalog(), (s, c) -> { });
                TailOffsetTracker tracker = new TailOffsetTracker(graph);

                assertThat(worker.drainPendingIntegration(segment, graph, tracker))
                    .isEqualTo(VECTORS);
            }
        }
    }

    /**
     * Writes one chunk payload per vector, in the layout the integration worker decodes.
     */
    private List<float[]> writeChunks(WriteLog writeLog) throws IOException {
        Random random = new Random(20260907L);
        List<float[]> vectors = new ArrayList<>(VECTORS);
        for (int i = 0; i < VECTORS; i++) {
            float[] vector = new float[DIMENSION];
            for (int d = 0; d < DIMENSION; d++) {
                vector[d] = (float) random.nextGaussian();
            }
            vectors.add(vector);
            byte[] key = ("source:doc:chunk:" + i).getBytes(StandardCharsets.UTF_8);
            writeLog.appendPut(key, ChunkPayloadCodec.encode("chunk " + i, vector));
        }
        return vectors;
    }

    /**
     * The ids the graph handed out, read back from the graph rather than assumed.
     */
    private static List<Long> nodeIdsOf(HnswGraph graph) {
        List<Long> ids = new ArrayList<>();
        for (long id = 0; id < graph.nodeCount(); id++) {
            ids.add(id);
        }
        return ids;
    }
}
