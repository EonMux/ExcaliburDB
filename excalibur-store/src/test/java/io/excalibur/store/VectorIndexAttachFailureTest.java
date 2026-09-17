package io.excalibur.store;

import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.hnsw.HnswConfigFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment that comes up without its vector index must be countable.
 *
 * <p>An HNSW file that cannot be opened - wrong magic, a version this build does not know, a
 * dimension or a metric that does not match the one configured - is a permanent condition for
 * that segment: nothing retries it and nothing repairs it. The engine keeps serving, because
 * the segment's key-value data is untouched and stopping the node over one stale index file
 * would take the whole keyspace down to protect part of it. What it must not do is keep serving
 * silently, because a vector search over a keyspace missing one segment returns fewer results
 * and no error - a confident, incomplete answer.</p>
 *
 * <p>These tests pin the surface that makes it visible. They are the reason the count exists:
 * without an accessor, the only trace is a log line nothing reads back.</p>
 */
@DisplayName("Segments that come up without a vector index")
class VectorIndexAttachFailureTest {

    private static final int DIMENSION = 8;

    @TempDir
    Path tempDir;

    private static HnswInitializer initializer() {
        return new HnswInitializer(HnswConfigFactory.withDefaults(),
            CosineSimilarity.getInstance(), DIMENSION, 1000L, null);
    }

    /**
     * Writes a file at the graph path that is long enough to be probed and carries a magic
     * number no build of this graph format ever wrote.
     */
    private static void writeUnopenableGraph(Path graphPath) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(0x0BADC0DEDEADBEEFL);
        Files.write(graphPath, header.array());
    }

    @Test
    @DisplayName("a segment whose graph file cannot be opened is counted, not swallowed")
    void anUnopenableGraphIsCounted() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("unopenable"));

        // A first run creates the base keyspace, so the segment files exist to be re-discovered.
        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.start();
            engine.put("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        }

        HnswInitializer initializer = initializer();
        Path segmentZero = dataDir.resolve("segment_0.dat");
        assertThat(segmentZero).exists();
        writeUnopenableGraph(initializer.computeGraphPath(segmentZero));

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.setHnswInitializer(initializer);
            reopened.start();

            assertThat(reopened.segmentsWithoutVectorIndexCount())
                .as("one segment came up with no vector index and an operator has to be able "
                    + "to see it")
                .isEqualTo(1);
            assertThat(reopened.segmentsWithoutVectorIndex())
                .as("and has to be able to see which one, and why")
                .containsKey(0L);
            assertThat(reopened.segmentsWithoutVectorIndex().get(0L))
                .containsIgnoringCase("magic");

            assertThat(reopened.get("k".getBytes(StandardCharsets.UTF_8)))
                .as("the key-value side of the segment is untouched and still served")
                .isNotNull();
        }
    }

    @Test
    @DisplayName("a healthy store reports no segments without a vector index")
    void aHealthyStoreReportsNone() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("healthy"));

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.setHnswInitializer(initializer());
            engine.start();

            assertThat(engine.segmentsWithoutVectorIndexCount()).isZero();
            assertThat(engine.segmentsWithoutVectorIndex()).isEmpty();
        }
    }
}
