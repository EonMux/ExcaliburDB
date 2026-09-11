package excalibur.shard.worker;

import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.store.Segment;
import io.excalibur.store.WriteLog;
import io.excalibur.vector.distance.L2Distance;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the rebuild is allowed to claim.
 *
 * <p>The checkpoint is the system's record of which entries the graph already speaks for, and
 * everything behind it is never read again. Advancing it to the log head says the graph describes
 * the whole data file, so a rebuild that stopped partway must not advance it: the entries it never
 * reached would be retrievable by key and absent from vector search, with nothing left to repair
 * them and nothing on the endpoint to say so.</p>
 */
@DisplayName("Segment graph rebuild")
class SegmentGraphRebuilderTest {

    private static final int DIMENSION = 8;
    private static final int ENTRIES = 5;

    @TempDir
    Path tempDir;

    /** A graph whose file fills up partway through the rebuild. */
    private static final class FailingGraph extends HnswGraph {
        private final int insertsBeforeFailure;
        private int inserts;

        FailingGraph(Path path, int insertsBeforeFailure) throws IOException {
            super(path, HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024L * 1024L);
            this.insertsBeforeFailure = insertsBeforeFailure;
        }

        @Override
        public long insert(float[] vector) {
            if (inserts++ >= insertsBeforeFailure) {
                throw new IllegalStateException("graph file is full");
            }
            return super.insert(vector);
        }
    }

    private static float[] vectorOf(int seed) {
        float[] vector = new float[DIMENSION];
        for (int d = 0; d < DIMENSION; d++) {
            vector[d] = seed + d;
        }
        return vector;
    }

    private void fill(Segment segment) {
        for (int i = 0; i < ENTRIES; i++) {
            segment.put(("source:doc:chunk:" + i).getBytes(StandardCharsets.UTF_8),
                ChunkPayloadCodec.encode("chunk " + i, vectorOf(i)));
        }
    }

    @Test
    @DisplayName("a rebuild that stopped partway does not mark the rest integrated")
    void aFailedRebuildLeavesTheCheckpointAlone() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("partial.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("partial.wal"));
             FailingGraph graph = new FailingGraph(tempDir.resolve("partial.graph"), 2)) {

            segment.attachWriteLog(writeLog);
            fill(segment);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            long checkpointBefore = tracker.getLastIntegratedOffset();
            long head = segment.getWriteLogHead();
            assertThat(head).as("the entries have to be behind the head for this to mean anything")
                .isGreaterThan(checkpointBefore);

            int inserted = SegmentGraphRebuilder.rebuild(segment, graph, tracker);

            assertThat(inserted).isEqualTo(2);
            assertThat(tracker.getLastIntegratedOffset())
                .as("the three entries the rebuild never reached are still to integrate")
                .isEqualTo(checkpointBefore);
            assertThat(graph.getLastIntegratedOffset()).isEqualTo(checkpointBefore);
        }
    }

    @Test
    @DisplayName("a rebuild that finished moves the checkpoint to the log head")
    void aCompleteRebuildAdvancesTheCheckpoint() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("whole.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("whole.wal"));
             HnswGraph graph = new HnswGraph(tempDir.resolve("whole.graph"),
                 HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024L * 1024L)) {

            segment.attachWriteLog(writeLog);
            fill(segment);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            long head = segment.getWriteLogHead();

            int inserted = SegmentGraphRebuilder.rebuild(segment, graph, tracker);

            assertThat(inserted).isEqualTo(ENTRIES);
            assertThat(tracker.getLastIntegratedOffset()).isEqualTo(head);
        }
    }
}
