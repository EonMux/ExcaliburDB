package com.eonmux.excalibur.ingest.erasure;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reaching every segment's graph for one erased document.
 *
 * <p>A document's chunks are routed by key and moved by splits, re-ingestion and compaction, so
 * there is no segment its nodes are confined to. An eraser that stopped at the first segment
 * holding some of them would leave the rest rankable, and a query would still say the document
 * matched.</p>
 */
@DisplayName("Retiring an erased document's vectors")
class SegmentVectorEraserTest {

    private static final int DIMENSION = 8;

    @TempDir
    Path tempDir;

    private StorageEngine storage;
    private DocumentErasureService.VectorEraser eraser;
    private final List<HnswGraph> graphs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageEngine(Files.createDirectories(tempDir.resolve("data")));
        storage.start();
        eraser = SegmentVectorEraser.of(storage);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ignored) {
                // Tidy-up only.
            }
        }
    }

    /**
     * Attaches a graph to every segment the engine holds, and returns them in that order.
     *
     * @return One graph per segment
     */
    private List<HnswGraph> attachGraphs() throws IOException {
        int index = 0;
        for (Segment segment : storage.getSegments().values()) {
            HnswGraph graph = new HnswGraph(tempDir.resolve("graph-" + index++ + ".hnsw"),
                HnswConfig.create(DIMENSION), L2Distance.getInstance(), 100);
            segment.attachHnswGraph(graph);
            graphs.add(graph);
        }
        assertThat(graphs).as("the engine has to hold more than one segment for this to prove anything")
            .hasSizeGreaterThan(1);
        return graphs;
    }

    /** Inserts one node attributed to a chunk, as the integration worker does, and returns it. */
    private static long insert(HnswGraph graph, String chunkId, int vectorIndex)
        throws IOException {
        long nodeId = graph.insert(unitVector(vectorIndex));
        graph.attributeChunk(nodeId, chunkId);
        return nodeId;
    }

    private static float[] unitVector(int index) {
        float[] vector = new float[DIMENSION];
        vector[Math.floorMod(index, DIMENSION)] = 1.0f;
        return vector;
    }

    @Test
    @DisplayName("nodes are retired in every segment that holds any of the document")
    void retiresAcrossEverySegment() throws Exception {
        List<HnswGraph> attached = attachGraphs();
        List<Long> erased = new ArrayList<>();
        List<Long> kept = new ArrayList<>();
        for (int i = 0; i < attached.size(); i++) {
            erased.add(insert(attached.get(i), "docs:secret.md:chunk:" + i, i));
            kept.add(insert(attached.get(i), "docs:public.md:chunk:" + i, i + 1));
        }

        assertThat(eraser.erase("docs:secret.md")).isEqualTo(attached.size());

        for (int i = 0; i < attached.size(); i++) {
            assertThat(attached.get(i).isTombstoned(erased.get(i)))
                .as("segment %s still ranks the erased document", i)
                .isTrue();
            assertThat(attached.get(i).isTombstoned(kept.get(i)))
                .as("segment %s lost a document nobody asked to erase", i)
                .isFalse();
        }
    }

    @Test
    @DisplayName("the tombstone is written to the graph file, not held beside it")
    void tombstonesReachTheGraphFile() throws Exception {
        HnswGraph graph = attachGraphs().get(0);
        long nodeId = insert(graph, "docs:secret.md:chunk:0", 0);
        graph.flush();
        Path graphPath = tempDir.resolve("graph-0.hnsw");

        assertThat(eraser.erase("docs:secret.md")).isEqualTo(1);

        try (HnswGraph reopened = HnswGraph.open(graphPath, L2Distance.getInstance())) {
            assertThat(reopened.isTombstoned(nodeId))
                .as("an erasure kept anywhere but the file is one a restart undoes")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a document no segment holds retires nothing")
    void anUnknownDocumentRetiresNothing() throws Exception {
        HnswGraph graph = attachGraphs().get(0);
        insert(graph, "docs:public.md:chunk:0", 0);

        assertThat(eraser.erase("docs:never-ingested.md")).isZero();
        assertThat(eraser.erase(null)).isZero();
    }

    @Test
    @DisplayName("named chunks are retired in every segment, and the rest of the document is not")
    void retiresNamedChunksAcrossEverySegment() throws Exception {
        List<HnswGraph> attached = attachGraphs();
        List<Long> surplus = new ArrayList<>();
        List<Long> kept = new ArrayList<>();
        List<String> surplusIds = new ArrayList<>();
        for (int i = 0; i < attached.size(); i++) {
            String surplusId = "docs:a.md:chunk:" + (attached.size() + i);
            surplusIds.add(surplusId);
            surplus.add(insert(attached.get(i), surplusId, i));
            kept.add(insert(attached.get(i), "docs:a.md:chunk:" + i, i + 1));
        }

        assertThat(eraser.eraseChunks(surplusIds)).isEqualTo(attached.size());
        assertThat(eraser.eraseChunks(List.of())).isZero();

        for (int i = 0; i < attached.size(); i++) {
            assertThat(attached.get(i).isTombstoned(surplus.get(i))).isTrue();
            assertThat(attached.get(i).isTombstoned(kept.get(i)))
                .as("the chunks the replacement still holds keep their vectors")
                .isFalse();
        }
    }

    @Test
    @DisplayName("segments with no graph are passed over, not treated as a failure")
    void segmentsWithoutGraphsAreSkipped() {
        storage.put("docs:a.md:chunk:0".getBytes(StandardCharsets.UTF_8),
            "content".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> assertThat(eraser.erase("docs:a.md")).isZero())
            .as("a node that keeps no vector index still erases from storage")
            .doesNotThrowAnyException();
        assertThat(eraser.eraseChunks(List.of("docs:a.md:chunk:0"))).isZero();
    }
}
