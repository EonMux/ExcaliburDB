package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.core.text.ChunkSources;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vector search answers a request scoped to some sources from their chunks alone, ranked as an
 * unscoped search ranks them, and fills the answer from below the chunks it refused.
 */
@DisplayName("Vector search scoped to sources")
class SourceScopedVectorSearchTest {

    private static final int DIMENSION = 8;
    private static final int VECTORS = 40;
    private static final long SEGMENT = 1L;

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private Segment segment;
    private HnswGraph graph;
    private StorageEngine storage;
    private VisibilityTracker visibility;
    private QueryPipeline pipeline;

    /**
     * Every fourth document is source {@code team:wiki}'s, the rest {@code team}'s; the ids of the
     * first begin with the id of the second, so only the registry can tell them apart.
     */
    private static final ChunkSources REGISTRY = chunkId -> {
        String key = ChunkIdentifiers.documentKeyOfChunk(chunkId);
        for (int i = 0; i < VECTORS; i++) {
            if (key.equals(key(i))) {
                return source(i);
            }
        }
        return null;
    };

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        visibility = new VisibilityTracker(catalog);
        catalog.add(SEGMENT, tempDir.resolve("vol.dat"));
        catalog.completeIndexing(SEGMENT);
        catalog.completeWarmup(SEGMENT);
        visibility.registerSegment(SEGMENT, 0L);
        segment = new Segment(tempDir.resolve("s.dat"), 1024 * 1024, arena);

        graph = new HnswGraph(tempDir.resolve("g.hnsw"), HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), 1024);
        graph.setNodeDocumentMapping(new NodeDocumentMapping());
        for (int i = 0; i < VECTORS; i++) {
            long node = graph.insert(vector(i));
            graph.registerDocumentId(node, chunk(i));
        }
        segment.attachHnswGraph(graph);

        storage = mock(StorageEngine.class);
        when(storage.getCatalog()).thenReturn(catalog);
        when(storage.getSegment(anyLong())).thenReturn(segment);

        pipeline = new QueryPipeline(storage, visibility, null,
            id -> id == SEGMENT ? graph : null, L2Distance.getInstance())
            .withChunkSources(REGISTRY);
    }

    @AfterEach
    void tearDown() throws Exception {
        pipeline.shutdown(Duration.ofSeconds(5));
        graph.close();
        segment.close();
        catalog.close();
        arena.close();
    }

    @Test
    @DisplayName("answers from the named source only, in the order an unscoped search ranks it")
    void answersFromTheNamedSource() {
        QueryResponse response = search(5, SourceScope.of(List.of("team:wiki")));

        assertThat(names(response)).containsExactly(chunk(0), chunk(4), chunk(8), chunk(12),
            chunk(16));
    }

    @Test
    @DisplayName("does not hand one source the chunks of another whose id extends it")
    void tellsTheSourcesApart() {
        QueryResponse response = search(6, SourceScope.of(List.of("team")));

        assertThat(names(response)).containsExactly(chunk(1), chunk(2), chunk(3), chunk(5),
            chunk(6), chunk(7));
    }

    @Test
    @DisplayName("comes back short, and says it ran out, when the source holds no more")
    void shortWhenTheSourceIsExhausted() {
        QueryResponse response = search(20, SourceScope.of(List.of("team:wiki")));

        assertThat(names(response)).containsExactlyElementsOf(IntStream.range(0, VECTORS)
            .filter(i -> i % 4 == 0).mapToObj(SourceScopedVectorSearchTest::chunk).toList());
        assertThat(response.retrievalExhausted()).isTrue();
    }

    @Test
    @DisplayName("comes back empty, and exhausted, for a source that holds nothing")
    void emptyForAnUnknownSource() {
        QueryResponse response = search(5, SourceScope.of(List.of("nobody")));

        assertThat(response.results()).isEmpty();
        assertThat(response.retrievalExhausted()).isTrue();
    }

    @Test
    @DisplayName("reports a result with no chunk identity as damage, not as out of scope")
    void unnamedResultsStillDegradeAScopedAnswer() throws IOException {
        // A node the mapping has no entry for, ranked first: the index-integrity fault an
        // unscoped search reports, and a scoped one must not hide by calling it another source.
        graph.insert(vector(0));

        QueryResponse response = search(3, SourceScope.of(List.of("team")));

        assertThat(names(response)).containsExactly(chunk(1), chunk(2), chunk(3));
        assertThat(response.isDegraded()).isTrue();
        assertThat(search(3, SourceScope.ALL).isDegraded()).isTrue();
    }

    @Test
    @DisplayName("an unscoped search is untouched")
    void unscopedIsUntouched() {
        QueryResponse response = search(3, SourceScope.ALL);

        assertThat(names(response)).containsExactly(chunk(0), chunk(1), chunk(2));
    }

    @Test
    @DisplayName("a pipeline that cannot attribute chunks refuses a scoped request")
    void refusesWithoutAttribution() throws Exception {
        QueryPipeline blind = new QueryPipeline(storage, visibility, null,
            id -> id == SEGMENT ? graph : null, L2Distance.getInstance());
        try {
            assertThatThrownBy(() -> blind.execute(request(5)
                    .sourceScope(SourceScope.of(List.of("team"))).build(),
                new CancellationToken()))
                .as("answering it from every source would hand back results it excluded, and "
                    + "failing inside a stage would read as nothing in the source matching")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("this pipeline cannot attribute chunks to sources, so it cannot "
                    + "answer a request scoped to [team]");
        } finally {
            blind.shutdown(Duration.ofSeconds(5));
        }
    }

    private QueryResponse search(int topK, SourceScope scope) {
        return pipeline.executeSync(request(topK).sourceScope(scope).build(),
            new CancellationToken());
    }

    private static QueryRequest.Builder request(int topK) {
        return QueryRequest.builder()
            .queryVector(vector(0))
            .topK(topK)
            .enableLlmScoring(false)
            .requireNamedResults(true);
    }

    private static List<String> names(QueryResponse response) {
        return response.results().stream().map(QueryResultItem::documentIdStr).toList();
    }

    private static float[] vector(int position) {
        float[] vector = new float[DIMENSION];
        Arrays.fill(vector, position / 10.0f);
        return vector;
    }

    private static String source(int document) {
        return document % 4 == 0 ? "team:wiki" : "team";
    }

    private static String key(int document) {
        return document % 4 == 0
            ? String.format("team:wiki:doc-%02d.md", document)
            : String.format("team:doc-%02d.md", document);
    }

    private static String chunk(int document) {
        return key(document) + ":chunk:0";
    }
}
