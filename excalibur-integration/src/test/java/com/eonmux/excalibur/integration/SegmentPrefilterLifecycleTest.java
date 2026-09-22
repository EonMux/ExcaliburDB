package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.ingest.lsh.LshIngestionService;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A segment's vector pre-filter has to have the same lifetime as the graph it is keyed against.
 *
 * <p>The pre-filter holds HNSW node ids, and stage 2 of the query pipeline restricts the graph
 * search to the ids it returns. Its files are named after the segment, but node ids belong to the
 * graph - so any point where a name outlives the graph stored under it is a point where the
 * pre-filter starts describing data that is no longer there. A split is exactly that point: the
 * left child takes its parent's canonical name and starts a brand new graph whose ids begin again
 * at zero.</p>
 *
 * <p>Neither side of that arrangement can detect the mismatch. The index answers, the graph
 * accepts the ids, and the query returns the wrong entries or - when the inherited index is well
 * populated enough to be treated as authoritative - none at all.</p>
 */
@DisplayName("Segment pre-filter lifecycle")
class SegmentPrefilterLifecycleTest {

    private static final int DIMENSIONS = 16;
    private static final int PARENT_VECTORS = 40;

    @Test
    @DisplayName("a split child does not inherit the pre-filter describing its parent")
    void aSplitChildStartsWithoutItsParentsPrefilter(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("split"));
        LshIngestionService prefilter = new LshIngestionService(DIMENSIONS);

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.setVectorPrefilter(prefilter);
            engine.start();

            Segment parent = engine.getSegment(0);
            assertThat(parent).isNotNull();

            // The parent as the integration worker would have left it: a pre-filter holding the
            // node ids of the parent's own graph, made durable beside the segment file.
            assertThat(parent.lshIndex())
                .as("the engine attaches a pre-filter to every segment it makes queryable")
                .isNotNull();
            Random random = new Random(20260907L);
            for (int nodeId = 0; nodeId < PARENT_VECTORS; nodeId++) {
                prefilter.indexVector(parent, vector(random), nodeId);
            }
            prefilter.flush(parent);
            assertThat(parent.lshIndex().entryCount()).isEqualTo(PARENT_VECTORS);

            for (int i = 0; i < 500; i++) {
                engine.put(key(i), value(i));
            }

            splitAndAwait(engine, parent);

            Segment leftChild = engine.getSegment(0);
            assertThat(leftChild)
                .as("the left child takes the parent's slot and its canonical file name")
                .isNotNull()
                .isNotSameAs(parent);

            LshIndex childIndex = leftChild.lshIndex();
            assertThat(childIndex)
                .as("a split child is queryable, so it gets a pre-filter like any other segment")
                .isNotNull();
            assertThat(childIndex.entryCount())
                .as("the child starts a new graph at node id zero, so every id the parent's "
                    + "pre-filter holds names a different vector in the child, or none at all")
                .isZero();
        }
    }

    @Test
    @DisplayName("a recovered segment regains the pre-filter that was persisted beside it")
    void aRestartRegainsThePersistedPrefilter(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("restart"));
        LshIngestionService prefilter = new LshIngestionService(DIMENSIONS);

        try (StorageEngine engine = new StorageEngine(dataDir)) {
            engine.setVectorPrefilter(prefilter);
            engine.start();

            Segment segment = engine.getSegment(0);
            Random random = new Random(20260908L);
            for (int nodeId = 0; nodeId < PARENT_VECTORS; nodeId++) {
                prefilter.indexVector(segment, vector(random), nodeId);
            }
            prefilter.flush(segment);
        }

        try (StorageEngine reopened = new StorageEngine(dataDir)) {
            reopened.setVectorPrefilter(prefilter);
            reopened.start();

            Segment recovered = reopened.getSegment(0);
            assertThat(recovered).isNotNull();
            assertThat(recovered.lshIndex())
                .as("the pre-filter persisted beside the segment is worth nothing if nothing "
                    + "re-opens it; the query pipeline reads it off the segment")
                .isNotNull();
            assertThat(recovered.lshIndex().entryCount())
                .as("a restart restores what was indexed, not an empty index")
                .isEqualTo(PARENT_VECTORS);
        }
    }

    private static void splitAndAwait(StorageEngine engine, Segment target) throws Exception {
        int widthBefore = engine.getSegmentCount();
        engine.getSplitWorker().requestSplit(target);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (engine.getSegmentCount() == widthBefore && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(engine.getSegmentCount())
            .as("the split must widen the routing table")
            .isEqualTo(widthBefore * 2);
    }

    private static float[] vector(Random random) {
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    private static byte[] key(int i) {
        return ("key-" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
