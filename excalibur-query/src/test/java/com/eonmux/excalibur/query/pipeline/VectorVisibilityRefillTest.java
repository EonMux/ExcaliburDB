package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
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
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vector search fills its answer from below the documents it withholds.
 *
 * <p>The atomicity filter drops the chunks of documents that are in flight. A search that cut its
 * ranking at the depth it was asked for and then filtered came back short whenever a withheld
 * document ranked inside it, although visible matches lay just below - and a short answer is what
 * callers read as the end of the matches. These pin the refill: it ranks deeper until the answer
 * is full or the candidates run out, only when something was withheld, inside the request's
 * deadline, and never deeper than the request allows.</p>
 */
@DisplayName("Vector search refilling past withheld documents")
class VectorVisibilityRefillTest {

    private static final int DIMENSION = 8;
    private static final int VECTORS = 40;
    private static final long SLOW_SEARCH_MILLIS = 30_000;
    private static final long SEGMENT = 1L;

    @TempDir
    Path tempDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private Segment segment;
    private CountingGraph graph;
    private DocumentVisibilityTracker tracker;
    private QueryPipeline pipeline;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        VisibilityTracker visibility = new VisibilityTracker(catalog);
        catalog.add(SEGMENT, tempDir.resolve("vol.dat"));
        catalog.completeIndexing(SEGMENT);
        catalog.completeWarmup(SEGMENT);
        visibility.registerSegment(SEGMENT, 0L);
        segment = new Segment(tempDir.resolve("s.dat"), 1024 * 1024, arena);

        graph = new CountingGraph(tempDir.resolve("g.hnsw"));
        graph.setNodeDocumentMapping(new NodeDocumentMapping());
        for (int i = 0; i < VECTORS; i++) {
            long node = graph.insert(vector(i));
            graph.registerDocumentId(node, chunk(i));
        }
        segment.attachHnswGraph(graph);

        StorageEngine storage = mock(StorageEngine.class);
        when(storage.getCatalog()).thenReturn(catalog);
        when(storage.getSegment(anyLong())).thenReturn(segment);

        tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        pipeline = new QueryPipeline(storage, visibility,
            new DocumentVisibilityTrackerAdapter(tracker), id -> id == SEGMENT ? graph : null,
            L2Distance.getInstance());
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
    @DisplayName("fills the answer from below the withheld documents, in rank order")
    void refillsPastWithheldDocuments() {
        hide(IntStream.range(0, 12).toArray());

        QueryResponse response = search(5);

        assertThat(names(response)).containsExactly(chunk(12), chunk(13), chunk(14), chunk(15),
            chunk(16));
        assertThat(response.retrievalExhausted()).as("a full answer has more below it").isFalse();
        assertThat(graph.searches()).as("it had to rank deeper to find them").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("pages across withheld documents with no gap and no repeat")
    void pagesAcrossWithheldDocuments() {
        int[] hidden = {0, 1, 2, 4, 7, 8, 9, 10, 15, 20, 21, 22};
        hide(hidden);
        Set<Integer> hiddenSet = IntStream.of(hidden).boxed().collect(Collectors.toSet());
        List<String> visible = IntStream.range(0, VECTORS).filter(i -> !hiddenSet.contains(i))
            .mapToObj(VectorVisibilityRefillTest::chunk).toList();

        int pageSize = 3;
        List<String> walked = new ArrayList<>();
        QueryResponse last;
        int offset = 0;
        do {
            int depth = offset + pageSize;
            last = search(depth);
            List<String> ranked = names(last);
            walked.addAll(ranked.subList(Math.min(offset, ranked.size()),
                Math.min(depth, ranked.size())));
            offset = depth;
        } while (last.results().size() == offset);

        assertThat(walked).containsExactlyElementsOf(visible);
        assertThat(last.retrievalExhausted())
            .as("the short page is the end of the matches, and says so").isTrue();
    }

    @Test
    @DisplayName("comes back short only when there is nothing more to find")
    void shortOnlyWhenExhausted() {
        hide(IntStream.range(0, 30).toArray());

        QueryResponse response = search(20);

        assertThat(names(response)).containsExactlyElementsOf(
            IntStream.range(30, VECTORS).mapToObj(VectorVisibilityRefillTest::chunk).toList());
        assertThat(response.retrievalExhausted()).isTrue();
    }

    @Test
    @DisplayName("searches once when nothing is tracked")
    void noRefillWhenNothingIsTracked() {
        QueryResponse response = search(5);

        assertThat(response.results()).hasSize(5);
        assertThat(graph.searches()).containsExactly(5 * 2);
    }

    @Test
    @DisplayName("searches once when what is withheld does not cost the answer anything")
    void noRefillWhenNothingIsWithheldFromTheAnswer() {
        hide(VECTORS - 1);

        QueryResponse response = search(5);

        assertThat(names(response)).containsExactly(chunk(0), chunk(1), chunk(2), chunk(3),
            chunk(4));
        assertThat(graph.searches()).as("one search, at the depth asked").containsExactly(5 * 2);
    }

    @Test
    @DisplayName("never ranks deeper than the request allows")
    void staysWithinTheDepthCeiling() {
        hide(IntStream.range(0, 30).toArray());

        QueryResponse response = pipeline.executeSync(request(5).maxSearchDepth(10).build(),
            new CancellationToken());

        assertThat(graph.searches()).as("the beam never widens past the ceiling")
            .allSatisfy(k -> assertThat(k).isLessThanOrEqualTo(10 * 2));
        assertThat(response.results()).as("nothing visible lies within that depth").isEmpty();
        assertThat(response.retrievalExhausted())
            .as("a ceiling is not the end of the matches").isFalse();
    }

    @Test
    @DisplayName("stops refilling at the deadline and keeps the answer it already had")
    void refillRespectsTheDeadline() {
        hide(IntStream.range(0, 12).toArray());
        graph.stallAfter(1);

        long start = System.nanoTime();
        QueryResponse response = pipeline.executeSync(request(5).timeoutMs(1_000).build(),
            new CancellationToken());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).as("the stalled refill is abandoned inside the deadline")
            .isLessThan(SLOW_SEARCH_MILLIS / 3);
        assertThat(response.results()).as("the first search found nothing visible").isEmpty();
        assertThat(response.retrievalExhausted())
            .as("an answer the deadline cut short is not the end of the matches").isFalse();
        assertThat(response.timedOut()).as("and it says the deadline cut it short").isTrue();
    }

    @Test
    @DisplayName("stops refilling when the caller withdraws the query")
    void refillRespectsCancellation() {
        hide(IntStream.range(0, 12).toArray());
        CancellationToken token = new CancellationToken();
        graph.onSearch(2, () -> token.cancel("withdrawn"));

        QueryResponse response = pipeline.executeSync(request(5).build(), token);

        assertThat(response.cancelled()).isTrue();
        assertThat(response.results()).isEmpty();
        assertThat(graph.searches()).as("no round begins after the withdrawal").hasSize(2);
    }

    private QueryResponse search(int topK) {
        return pipeline.executeSync(request(topK).build(), new CancellationToken());
    }

    private static QueryRequest.Builder request(int topK) {
        return QueryRequest.builder()
            .queryVector(vector(0))
            .topK(topK)
            .enableLlmScoring(false)
            .requireNamedResults(true);
    }

    private void hide(int... documents) {
        for (int document : documents) {
            tracker.beginIngestion(key(document), 1);
        }
    }

    private static List<String> names(QueryResponse response) {
        return response.results().stream().map(QueryResultItem::documentIdStr).toList();
    }

    private static float[] vector(int position) {
        float[] vector = new float[DIMENSION];
        Arrays.fill(vector, position / 10.0f);
        return vector;
    }

    private static String key(int document) {
        return String.format("src:doc-%02d.md", document);
    }

    private static String chunk(int document) {
        return key(document) + ":chunk:0";
    }

    /** A graph that records how wide each search was, and can stall or act on a given one. */
    private static final class CountingGraph extends HnswGraph {

        private final List<Integer> widths = new CopyOnWriteArrayList<>();
        private volatile int stallAfter = Integer.MAX_VALUE;
        private volatile int actOn = -1;
        private volatile Runnable action;

        CountingGraph(Path path) throws IOException {
            super(path, HnswConfig.create(DIMENSION), L2Distance.getInstance(), 1024);
        }

        void stallAfter(int searches) {
            this.stallAfter = searches;
        }

        void onSearch(int search, Runnable action) {
            this.actOn = search;
            this.action = action;
        }

        List<Integer> searches() {
            return widths;
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            widths.add(k);
            int made = widths.size();
            if (made == actOn) {
                action.run();
            }
            if (made > stallAfter) {
                try {
                    Thread.sleep(SLOW_SEARCH_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Search abandoned", e);
                }
            }
            return super.search(query, k);
        }
    }
}
