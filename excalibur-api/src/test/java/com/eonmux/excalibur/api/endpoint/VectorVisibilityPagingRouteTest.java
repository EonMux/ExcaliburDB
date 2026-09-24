package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pages of a vector answer that withholds documents stay contiguous, and the total stays true.
 *
 * <p>Drives {@code POST /v1/query} over a real query pipeline and graph, with the nearest
 * documents in flight. The route ranks as deep as the page reaches and slices the page off the
 * end; a pipeline that dropped withheld results without ranking deeper returned short rankings
 * that shifted every later page and ended the walk early with an exact total that was too
 * small.</p>
 */
@DisplayName("Paging a vector answer that withholds documents")
class VectorVisibilityPagingRouteTest {

    private static final int DIMENSION = 8;
    private static final int DOCUMENTS = 10;
    private static final long SEGMENT = 1L;

    /** Withheld documents, among the nearest so that every page is affected. */
    private static final int[] HIDDEN = {0, 2, 3, 6};

    @TempDir
    Path dataDir;

    private Arena arena;
    private SegmentCatalog catalog;
    private Segment segment;
    private HnswGraph graph;
    private QueryPipeline pipeline;
    private QueryEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        arena = Arena.ofShared();
        catalog = new SegmentCatalog(dataDir.resolve("catalog.dat"));
        VisibilityTracker visibility = new VisibilityTracker(catalog);
        catalog.add(SEGMENT, dataDir.resolve("vol.dat"));
        catalog.completeIndexing(SEGMENT);
        catalog.completeWarmup(SEGMENT);
        visibility.registerSegment(SEGMENT, 0L);
        segment = new Segment(dataDir.resolve("s.dat"), 1024 * 1024, arena);
        graph = new HnswGraph(dataDir.resolve("g.hnsw"), HnswConfig.create(DIMENSION),
            L2Distance.getInstance(), 1024);
        graph.setNodeDocumentMapping(new NodeDocumentMapping());
        for (int d = 0; d < DOCUMENTS; d++) {
            graph.registerDocumentId(graph.insert(vector(d)), chunkOf(d));
        }
        segment.attachHnswGraph(graph);

        StorageEngine storage = mock(StorageEngine.class);
        when(storage.getCatalog()).thenReturn(catalog);
        when(storage.getSegment(anyLong())).thenReturn(segment);

        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        for (int d : HIDDEN) {
            tracker.beginIngestion(ChunkIdentifiers.documentKey("docs", nameOf(d)), 1);
        }
        pipeline = new QueryPipeline(storage, visibility,
            new DocumentVisibilityTrackerAdapter(tracker), id -> id == SEGMENT ? graph : null,
            L2Distance.getInstance());

        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyString(), anyString())).thenReturn(vector(0));
        when(embeddings.embed(anyString())).thenReturn(vector(0));
        endpoint = new QueryEndpoint(pipeline, null, null, null, embeddings);
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
    @DisplayName("walks every visible result once, in rank order, page by page")
    void pagesAreContiguous() throws Exception {
        List<String> walked = new ArrayList<>();
        Integer offset = 0;
        while (offset != null) {
            JsonNode page = ask(offset, 3);
            for (JsonNode result : page.get("results")) {
                walked.add(result.get("document_id").asText());
            }
            JsonNode next = page.path("page").path("next_offset");
            offset = next.isNull() || next.isMissingNode() ? null : next.asInt();
        }

        assertThat(walked).containsExactly(chunkOf(1), chunkOf(4), chunkOf(5), chunkOf(7),
            chunkOf(8), chunkOf(9));
    }

    @Test
    @DisplayName("fills a page from below the results it withheld")
    void pageIsFull() throws Exception {
        JsonNode first = ask(0, 3);

        assertThat(first.path("page").path("returned").asInt()).isEqualTo(3);
        assertThat(first.path("result_total").path("relation").asText())
            .as("more visible matches remain past a full page").isEqualTo("at_least");
    }

    @Test
    @DisplayName("reports an exact total of the visible matches when the corpus runs out")
    void exactTotalCountsOnlyVisibleMatches() throws Exception {
        JsonNode all = ask(0, 50);

        assertThat(all.path("result_total").path("value").asInt())
            .isEqualTo(DOCUMENTS - HIDDEN.length);
        assertThat(all.path("result_total").path("relation").asText()).isEqualTo("exact");
    }

    private JsonNode ask(int offset, int topK) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        endpoint.handle(exchange("{\"query\": \"near\", \"top_k\": " + topK
            + ", \"offset\": " + offset + ", \"enable_llm_scoring\": false}", body));
        JsonNode json = new ObjectMapper().readTree(body.toString(StandardCharsets.UTF_8));
        assertThat(json.path("retrieval").asText()).as(json.toString()).isEqualTo("vector");
        return json;
    }

    private static HttpExchange exchange(String request, ByteArrayOutputStream response) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn("POST");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/query"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(response);
        return exchange;
    }

    private static float[] vector(int position) {
        float[] vector = new float[DIMENSION];
        Arrays.fill(vector, position / 10.0f);
        return vector;
    }

    private static String nameOf(int document) {
        return String.format("doc%02d", document);
    }

    private static String chunkOf(int document) {
        return ChunkIdentifiers.chunkId("docs", nameOf(document), 0);
    }
}
