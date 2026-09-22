package com.eonmux.excalibur.shard.worker;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write log tail the worker cannot read past.
 *
 * <p>Entries reach a graph one at a time from the checkpoint, and an entry that will not parse
 * stops the read where it is. The checkpoint cannot move past it without declaring entries
 * integrated that are not, so it stays - and every cycle after that reads the same byte and gets
 * the same answer. The read itself says nothing above debug, so left alone this is a segment
 * whose vectors quietly stop becoming searchable while ingestion goes on reporting success.</p>
 */
@DisplayName("Stalled integration tail")
class HnswIntegrationStallTest {

    private static final int DIMENSION = 8;
    private static final int ENTRIES = 4;

    @TempDir
    Path tempDir;

    /** A log whose entries stop parsing from a given offset, as a failed CRC would. */
    private static final class UnreadableTailLog extends WriteLog {
        private volatile long unreadableFrom;

        UnreadableTailLog(Path path, long unreadableFrom) throws IOException {
            super(path);
            this.unreadableFrom = unreadableFrom;
        }

        @Override
        public LogEntry readEntryAt(long offset) {
            return offset >= unreadableFrom ? null : super.readEntryAt(offset);
        }
    }

    private static float[] vectorOf(int seed) {
        float[] vector = new float[DIMENSION];
        for (int d = 0; d < DIMENSION; d++) {
            vector[d] = seed + d;
        }
        return vector;
    }

    private static void write(WriteLog log) throws IOException {
        for (int i = 0; i < ENTRIES; i++) {
            log.appendPut(("source:doc:chunk:" + i).getBytes(StandardCharsets.UTF_8),
                ChunkPayloadCodec.encode("chunk " + i, vectorOf(i)));
        }
    }

    @Test
    @DisplayName("a tail that cannot be read is reported, not spun on in silence")
    void anUnreadableTailIsReported() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("stalled.dat"), 4L * 1024 * 1024, arena);
             UnreadableTailLog writeLog = new UnreadableTailLog(tempDir.resolve("stalled.wal"), 0);
             HnswGraph graph = new HnswGraph(tempDir.resolve("stalled.graph"),
                 HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024L * 1024L)) {

            segment.attachWriteLog(writeLog);
            write(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            HnswIntegrationWorker worker =
                new HnswIntegrationWorker(new SegmentCatalog(), (s, c) -> { });

            int integrated = worker.drainPendingIntegration(segment, graph, tracker);

            assertThat(integrated).isZero();
            assertThat(tracker.getLastIntegratedOffset())
                .as("nothing may be declared integrated that was never read")
                .isZero();
            assertThat(worker.stalledTails())
                .as("the segment whose vectors stopped arriving is named")
                .containsEntry(segment.path(), 0L);
        }
    }

    @Test
    @DisplayName("a tail that starts reading again is no longer reported as stuck")
    void aRecoveredTailIsForgotten() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("recovered.dat"), 4L * 1024 * 1024, arena);
             UnreadableTailLog writeLog = new UnreadableTailLog(tempDir.resolve("recovered.wal"), 0);
             HnswGraph graph = new HnswGraph(tempDir.resolve("recovered.graph"),
                 HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024L * 1024L)) {

            segment.attachWriteLog(writeLog);
            write(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            HnswIntegrationWorker worker =
                new HnswIntegrationWorker(new SegmentCatalog(), (s, c) -> { });
            worker.drainPendingIntegration(segment, graph, tracker);
            assertThat(worker.stalledTails()).isNotEmpty();

            writeLog.unreadableFrom = Long.MAX_VALUE;
            int integrated = worker.drainPendingIntegration(segment, graph, tracker);

            assertThat(integrated).isEqualTo(ENTRIES);
            assertThat(worker.stalledTails()).isEmpty();
        }
    }

    @Test
    @DisplayName("a healthy segment is never listed as stuck")
    void aHealthyTailIsNotReported() throws IOException {
        try (Arena arena = Arena.ofShared();
             Segment segment = new Segment(tempDir.resolve("healthy.dat"), 4L * 1024 * 1024, arena);
             WriteLog writeLog = new WriteLog(tempDir.resolve("healthy.wal"));
             HnswGraph graph = new HnswGraph(tempDir.resolve("healthy.graph"),
                 HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024L * 1024L)) {

            segment.attachWriteLog(writeLog);
            write(writeLog);
            TailOffsetTracker tracker = new TailOffsetTracker(graph);
            HnswIntegrationWorker worker =
                new HnswIntegrationWorker(new SegmentCatalog(), (s, c) -> { });

            assertThat(worker.drainPendingIntegration(segment, graph, tracker)).isEqualTo(ENTRIES);
            assertThat(worker.stalledTails()).isEmpty();
        }
    }
}
