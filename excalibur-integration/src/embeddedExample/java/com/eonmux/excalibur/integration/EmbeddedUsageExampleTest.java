package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.core.memory.ForeignMemoryBootstrap;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.InlineSource;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.HnswInitializer;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded (in-process) usage example from USAGE.md, compiled and run.
 *
 * <p>USAGE.md's "Programmatic Usage (JVM Embedded)" section shows how an application opens a
 * data directory, ingests documents and queries them without the HTTP server. The body of
 * {@link #ingestAndQuery(Path, String)} is that example: the documentation reproduces it line for
 * line, so this test is what keeps the published snippet compiling against the current public
 * API and producing an answer. A change that breaks this test breaks every embedder who copied
 * the snippet, and the documentation has to change with it; {@link EmbeddedUsageDocumentationTest}
 * fails until it does.</p>
 *
 * <p>It lives in the {@code embeddedExample} source set rather than beside the other integration
 * tests because that source set's classpath is only the seven coordinates USAGE.md tells an
 * embedder to declare. Compiled there, it also proves that list is enough.</p>
 *
 * <p>The example uses only public constructors and methods of the library modules, assembled in
 * the order the server's own bootstrap uses:</p>
 * <ol>
 *   <li>{@link ForeignMemoryBootstrap#ensureInitialized()} before anything can start a thread;</li>
 *   <li>a {@link StorageEngine} on the data directory, with an {@link HnswInitializer} installed
 *       before {@link StorageEngine#start()} so every segment carries a vector index;</li>
 *   <li>an {@link IndexOrchestrator}, which owns the visibility clock the engine, ingestion and
 *       queries share and runs the worker that integrates written vectors into each segment's
 *       HNSW graph;</li>
 *   <li>an {@link IngestionOrchestrator}, which chunks, embeds, writes and publishes documents
 *       atomically; {@link IngestionOrchestrator#ingestNow} returns once they are published;</li>
 *   <li>a {@link QueryPipeline} over the same engine, clock and document visibility, answering
 *       {@link QueryPipeline#executeSync} with a vector the same embedding client produced.</li>
 * </ol>
 *
 * <p>The one thing the test substitutes is the embedding provider. {@link EmbeddingClient} speaks
 * the OpenAI-compatible {@code POST /v1/embeddings} protocol, so the test points it at
 * {@link FakeEmbeddingServer}, an in-process endpoint that embeds text deterministically as a
 * hashed bag of words. The example itself is unchanged: an embedder passes the base URL of a real
 * provider (OpenAI, Ollama, a local inference server) where the test passes the fake's.</p>
 */
@DisplayName("Embedded usage example (USAGE.md)")
class EmbeddedUsageExampleTest {

    /** Width of the vectors the fake endpoint returns; the example configures the same width. */
    private static final int DIMENSION = 1536;

    @Test
    @Timeout(120)
    @DisplayName("documents ingested in-process are returned by an in-process query")
    void embeddedIngestionIsFoundByEmbeddedQuery(@TempDir Path tempDir) throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        List<QueryResultItem> results;
        try (FakeEmbeddingServer embeddings = FakeEmbeddingServer.start(DIMENSION)) {
            results = ingestAndQuery(dataDir, embeddings.baseUrl());
        }

        assertThat(results)
            .as("the query shares most of its words with one document and nothing with another")
            .isNotEmpty();
        assertThat(results.getFirst().documentIdStr())
            .as("the closest result is the chunk about tiering, named as ingestion named it")
            .isEqualTo("handbook:tiering.txt:chunk:0");
        assertThat(results)
            .extracting(QueryResultItem::documentIdStr)
            .as("each chunk is returned once, under the identifier ingestion assigned")
            .doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(id).startsWith("handbook:").contains(":chunk:"));
    }

    /**
     * Opens {@code dataDir}, ingests three documents and returns the results of one query.
     *
     * <p>This method body is the USAGE.md example. Keep it readable as documentation: no test
     * scaffolding, no assertions, nothing a reader would have to strip out.</p>
     *
     * @param dataDir           An existing directory the engine keeps its files in
     * @param embeddingEndpoint Base URL of an OpenAI-compatible embeddings API
     * @return The ranked results, closest first
     * @throws Exception if the engine cannot be opened or a document cannot be ingested
     */
    static List<QueryResultItem> ingestAndQuery(Path dataDir, String embeddingEndpoint)
            throws Exception {
        // First statement, before anything can start a thread.
        ForeignMemoryBootstrap.ensureInitialized();

        // The embedding width and the distance metric are properties of the corpus: ingestion and
        // queries must use the same model, width and metric for the lifetime of dataDir.
        int dimension = 1536;
        DistanceFunction metric = CosineSimilarity.getInstance();
        EmbeddingClient embeddingClient = new EmbeddingClient(LLMClientConfig.builder()
            .baseUrl(embeddingEndpoint)                  // e.g. "https://api.openai.com"
            .apiKey(System.getenv().getOrDefault("OPENAI_API_KEY", ""))
            .embeddingModel("text-embedding-3-small")
            .embeddingDimension(dimension)
            .build());

        try (StorageEngine storage = new StorageEngine(dataDir)) {
            // Installed before start(), so every segment start() opens or creates has a graph.
            storage.setHnswInitializer(new HnswInitializer(HnswConfigFactory.defaults(), metric,
                dimension, HnswInitializer.DEFAULT_MAX_NODES, storage.arena()));
            storage.start();

            try (IndexOrchestrator indexing = new IndexOrchestrator(storage.getCatalog(), storage)) {
                // One visibility clock for storage, ingestion and queries.
                storage.setVisibilityTracker(indexing.getVisibilityTracker());
                indexing.syncSegmentsFromStorage();
                indexing.start();
                storage.startLifecycleManager();

                DocumentVisibilityTracker documents = new DocumentVisibilityTracker(
                    new DocumentRegistry(), indexing.getVisibilityTracker());
                IngestionOrchestrator ingestion = new IngestionOrchestrator(
                    new TextChunker(), embeddingClient, storage, indexing,
                    sourceId -> new IngestionOrchestrator.IngestionStatus() {
                        @Override
                        public void update(String state, double progress, long ingested,
                                           long pending) {
                        }

                        @Override
                        public void setSkipped(long skipped) {
                        }

                        @Override
                        public void setDisallowed(long disallowed) {
                        }

                        @Override
                        public void setError(String error) {
                            System.err.println(sourceId + ": " + error);
                        }
                    },
                    documents);

                QueryPipeline pipeline = new QueryPipeline(
                    storage,
                    indexing.getVisibilityTracker(),
                    new DocumentVisibilityTrackerAdapter(documents),
                    segmentId -> {
                        Segment segment = storage.getSegment(segmentId);
                        return segment != null ? segment.getHnswGraph() : null;
                    },
                    metric);

                try {
                    // Ingest: chunk, embed, write, publish. Returns once the documents are
                    // published; results name the chunk that matched,
                    // "<sourceId>:<documentId>:chunk:<n>".
                    ingestion.ingestNow(InlineSource.of("handbook", List.of(
                        text("tiering.txt",
                            "Cold segments are moved to cheaper disks by the tiering policy."),
                        text("hnsw.txt",
                            "The HNSW graph links every vector to its nearest neighbours."),
                        text("backup.txt",
                            "Backups copy the write log and the catalog to object storage."))));

                    // Query: embed the question with the SAME client, then search.
                    String queryText = "which policy moves cold segments to cheaper disks";
                    QueryRequest request = QueryRequest.builder()
                        .queryText(queryText)
                        .queryVector(embeddingClient.embed(queryText))
                        .topK(3)
                        .timeoutMs(5000)
                        .includePartialSegments(true)
                        .enableLlmScoring(false)
                        .build();
                    QueryResponse response = pipeline.executeSync(request, new CancellationToken());

                    response.results().forEach(item ->
                        System.out.println(item.documentIdStr() + " -> " + item.score()));
                    return response.results();
                } finally {
                    // Stop taking work before the storage underneath it closes.
                    pipeline.shutdown();
                    ingestion.shutdown();
                }
            }
        }
    }

    /**
     * One plain-text document for an {@link InlineSource}.
     *
     * @param documentId The document's identifier within its source
     * @param content    Its text
     * @return The document
     */
    static InlineSource.Document text(String documentId, String content) {
        return new InlineSource.Document(documentId, content.getBytes(StandardCharsets.UTF_8),
            "text/plain", 0L);
    }
}
