package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.L2Distance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nodes attributed to the chunk they are the vector of.
 *
 * <p>A chunk id outlives its value: re-posting a document writes new text under the same ids,
 * and the integration worker inserts each new value as a new node. What these tests pin down is
 * that the graph then holds exactly one searchable vector per chunk - the newest - that a chunk or
 * a whole document can be retired by name, and that a graph an older build attributed by document
 * is re-keyed from its segment rather than served as it is.</p>
 */
@DisplayName("Chunk attribution in the graph")
class ChunkAttributionTest {

    private static final int DIMENSION = 4;

    @TempDir
    Path tempDir;

    private HnswGraph newGraph(String name) throws IOException {
        return new HnswGraph(tempDir.resolve(name), HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), 1000);
    }

    private static float[] vector(float x) {
        return new float[] {x, 1f, 0f, 0f};
    }

    private static List<Long> ids(List<HnswSearchResult> results) {
        List<Long> ids = new ArrayList<>();
        for (HnswSearchResult result : results) {
            ids.add(result.nodeId());
        }
        return ids;
    }

    @Nested
    @DisplayName("supersession and retirement")
    class Supersession {

        @Test
        @DisplayName("a chunk's new value retires its old one, and only that")
        void newValueRetiresTheOld() throws IOException {
            try (HnswGraph graph = newGraph("supersede.hnsw")) {
                long old = graph.insert(vector(1));
                long other = graph.insert(vector(2));
                assertThat(graph.attributeChunk(old, "src:a.md:chunk:0")).isZero();
                assertThat(graph.attributeChunk(other, "src:a.md:chunk:1")).isZero();

                long replacement = graph.insert(vector(3));
                assertThat(graph.attributeChunk(replacement, "src:a.md:chunk:0")).isOne();

                assertThat(graph.isTombstoned(old)).isTrue();
                assertThat(graph.isTombstoned(replacement)).isFalse();
                assertThat(graph.isTombstoned(other)).isFalse();
                assertThat(ids(graph.search(vector(1), 10)))
                    .as("the old text's vector must not rank under the reused id")
                    .doesNotContain(old)
                    .contains(replacement, other);
                assertThat(graph.getDocumentId(replacement)).isEqualTo("src:a.md:chunk:0");
            }
        }

        @Test
        @DisplayName("named chunks are retired, and their neighbours are not")
        void deleteChunksRetiresOnlyThoseNamed() throws IOException {
            try (HnswGraph graph = newGraph("chunks.hnsw")) {
                long first = graph.insert(vector(1));
                long second = graph.insert(vector(2));
                graph.attributeChunk(first, "src:a.md:chunk:0");
                graph.attributeChunk(second, "src:a.md:chunk:1");

                assertThat(graph.deleteChunks(List.of("src:a.md:chunk:1", "src:none:chunk:0")))
                    .isOne();
                assertThat(graph.deleteChunks(List.of("src:a.md:chunk:1")))
                    .as("already retired").isZero();
                assertThat(graph.isTombstoned(second)).isTrue();
                assertThat(graph.isTombstoned(first)).isFalse();
            }
        }

        @Test
        @DisplayName("a document is retired through its chunks' ids")
        void deleteDocumentRetiresEveryChunk() throws IOException {
            try (HnswGraph graph = newGraph("document.hnsw")) {
                long a0 = graph.insert(vector(1));
                long a1 = graph.insert(vector(2));
                long other = graph.insert(vector(3));
                graph.attributeChunk(a0, "src:a.md:chunk:0");
                graph.attributeChunk(a1, "src:a.md:chunk:1");
                graph.attributeChunk(other, "src:a.md.bak:chunk:0");

                assertThat(graph.deleteDocument("src:a.md")).isEqualTo(2);
                assertThat(graph.isTombstoned(other))
                    .as("a document whose name only starts with the erased one's").isFalse();
            }
        }

        @Test
        @DisplayName("a graph with no mapping attributes and retires nothing")
        void noMappingNoAttribution() throws IOException {
            try (HnswGraph graph = newGraph("unmapped.hnsw")) {
                long node = graph.insert(vector(1));
                graph.nodeDocumentMapping = null;
                assertThat(graph.attributeChunk(node, "src:a.md:chunk:0")).isZero();
                assertThat(graph.deleteChunks(List.of("src:a.md:chunk:0"))).isZero();
                assertThat(graph.attributionKeying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
            }
        }
    }

    @Nested
    @DisplayName("upgrading a document-keyed graph")
    class Upgrade {

        /** A graph whose mapping names documents, as a build before chunk attribution left it. */
        private HnswGraph legacyGraph(String name) throws IOException {
            HnswGraph graph = newGraph(name);
            graph.getNodeDocumentMapping().useKeying(NodeDocumentMapping.Keying.DOCUMENT);
            return graph;
        }

        private static Iterator<ChunkAttributionUpgrade.LiveChunk> live(Object... pairs) {
            List<ChunkAttributionUpgrade.LiveChunk> chunks = new ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) {
                chunks.add(new ChunkAttributionUpgrade.LiveChunk((String) pairs[i],
                    (float[]) pairs[i + 1]));
            }
            return chunks.iterator();
        }

        @Test
        @DisplayName("nodes are re-keyed to the chunk holding their vector; the rest are retired")
        void rekeysAndRetiresStaleVectors() throws IOException {
            Path path = tempDir.resolve("legacy.hnsw");
            long current0;
            long current1;
            long stale;
            long duplicate;
            long otherDocument;
            try (HnswGraph graph = legacyGraph("legacy.hnsw")) {
                stale = graph.insert(vector(1));          // chunk 0 of a version since replaced
                current1 = graph.insert(vector(2));       // chunk 1, unchanged since
                current0 = graph.insert(vector(3));       // chunk 0 of the current version
                duplicate = graph.insert(vector(2));      // chunk 1 re-posted with the same text
                otherDocument = graph.insert(vector(9));  // another document's chunk
                for (long node : List.of(stale, current1, current0, duplicate)) {
                    graph.registerDocumentId(node, "src:a.md");
                }
                graph.registerDocumentId(otherDocument, "src:b.md");
                graph.flush();

                ChunkAttributionUpgrade.Outcome outcome = ChunkAttributionUpgrade.run(graph, live(
                    "src:a.md:chunk:0", vector(3),
                    "src:a.md:chunk:1", vector(2),
                    "src:b.md:chunk:0", vector(9),
                    "src:c.md:chunk:0", vector(7)));

                assertThat(outcome.ran()).isTrue();
                assertThat(outcome.retry()).isFalse();
                assertThat(outcome.rekeyed()).isEqualTo(3);
                assertThat(outcome.retired()).isEqualTo(2);
                assertThat(graph.attributionKeying()).isEqualTo(NodeDocumentMapping.Keying.CHUNK);
                assertThat(graph.getDocumentId(current0)).isEqualTo("src:a.md:chunk:0");
                assertThat(graph.getDocumentId(current1)).isEqualTo("src:a.md:chunk:1");
                assertThat(graph.getDocumentId(otherDocument)).isEqualTo("src:b.md:chunk:0");
                assertThat(graph.isTombstoned(stale))
                    .as("the replaced version's vector has no live chunk to be named as").isTrue();
                assertThat(graph.isTombstoned(duplicate)).isTrue();

                assertThat(ChunkAttributionUpgrade.run(graph, live()))
                    .as("a chunk-keyed graph needs nothing")
                    .isEqualTo(ChunkAttributionUpgrade.Outcome.NOT_NEEDED);
            }

            try (HnswGraph reopened = HnswGraph.open(path, L2Distance.getInstance())) {
                assertThat(reopened.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.CHUNK);
                assertThat(reopened.getDocumentId(current0)).isEqualTo("src:a.md:chunk:0");
                assertThat(reopened.isTombstoned(stale)).isTrue();
            }
        }

        @Test
        @DisplayName("a compaction keeps a document-keyed mapping document-keyed")
        void compactionPreservesKeying() throws IOException {
            Path path = tempDir.resolve("compacted.hnsw");
            try (HnswGraph graph = legacyGraph("compacted.hnsw")) {
                long dead = graph.insert(vector(1));
                long kept = graph.insert(vector(2));
                graph.registerDocumentId(dead, "src:a.md");
                graph.registerDocumentId(kept, "src:a.md");
                graph.delete(dead);

                graph.compact(tempDir);

                assertThat(graph.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
                assertThat(graph.getNodeDocumentMapping().getAllMappings())
                    .containsExactly(Map.entry(0L, "src:a.md"));
            }
            try (HnswGraph reopened = HnswGraph.open(path, L2Distance.getInstance())) {
                assertThat(reopened.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
            }
        }

        @Test
        @DisplayName("a copy keeps the keying of the graph it was copied from")
        void copyPreservesKeying() throws IOException {
            Path copy = tempDir.resolve("copy.hnsw");
            try (HnswGraph graph = legacyGraph("original.hnsw")) {
                graph.registerDocumentId(graph.insert(vector(1)), "src:a.md");
                graph.compactTo(copy);
            }
            try (HnswGraph reopened = HnswGraph.open(copy, L2Distance.getInstance())) {
                assertThat(reopened.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
            }
        }

        @Test
        @DisplayName("a compaction during the pass leaves the graph for the next one")
        void renumberingAbandonsThePass() throws IOException {
            try (HnswGraph graph = legacyGraph("renumbered.hnsw")) {
                long node = graph.insert(vector(1));
                graph.registerDocumentId(node, "src:a.md");

                Iterator<ChunkAttributionUpgrade.LiveChunk> renumberingMidway = new Iterator<>() {
                    private boolean done;

                    @Override
                    public boolean hasNext() {
                        return !done;
                    }

                    @Override
                    public ChunkAttributionUpgrade.LiveChunk next() {
                        done = true;
                        graph.generation++;
                        return new ChunkAttributionUpgrade.LiveChunk("src:a.md:chunk:0", vector(1));
                    }
                };

                assertThat(ChunkAttributionUpgrade.run(graph, renumberingMidway))
                    .isEqualTo(ChunkAttributionUpgrade.Outcome.RENUMBERED);
                assertThat(graph.attributionKeying())
                    .isEqualTo(NodeDocumentMapping.Keying.DOCUMENT);
                assertThat(graph.isTombstoned(node)).isFalse();
            }
        }
    }
}
