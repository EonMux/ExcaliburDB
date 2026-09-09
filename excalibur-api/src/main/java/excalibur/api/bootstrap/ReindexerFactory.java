package excalibur.api.bootstrap;

import excalibur.filter.HierarchicalCuckooFilter;
import excalibur.ingest.lexical.ChunkIndex;
import excalibur.ingest.lexical.StoredChunkText;
import excalibur.ingest.pipeline.DocumentBatchPublisher;
import excalibur.ingest.pipeline.DocumentReindexer;
import excalibur.ingest.pipeline.OffsetAwareStorageWriter;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.llm.client.LLMClientConfig;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.allocator.VolumeManager;
import io.excalibur.config.Config;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Builds the {@link DocumentReindexer} and everything it writes through.
 *
 * <p>Kept separate from the application bootstrap because the reindex path has its
 * own correctness requirements: superseded chunks must actually be tombstoned, and
 * chunks must land in the segment their key routes to (or, during rolling
 * replacement, in the new segment the caller nominated).</p>
 *
 * <p>The lexical index is part of that. A reindex is the one operation that removes content a
 * query could still be matching, so a reindexer that wrote to storage alone would leave deleted
 * documents answering BM25 queries under identifiers whose chunks are gone.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the components it returns carry their own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 */
public final class ReindexerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexerFactory.class);

    /** Segment ID the batch publisher uses until rolling replacement overrides it. */
    private static final String DEFAULT_PUBLISH_SEGMENT_ID = "0";

    private ReindexerFactory() {
        // Utility class
    }

    /**
     * Creates a fully wired document reindexer.
     *
     * @param config             Application configuration (LLM settings)
     * @param storageEngine      Storage engine chunks are written to and tombstoned in
     * @param volumeManager      Volume manager, enabling rolling replacement when present
     * @param indexOrchestrator  Orchestrator supplying the visibility tracker
     * @param hierarchicalFilter Filter that must see both inserts and tombstones
     * @param registry           Document registry shared with the ingestion pipeline
     * @param chunkIndex         Lexical index to keep in step with storage, or null for none
     * @return A configured reindexer
     * @throws NullPointerException if any required argument is null
     */
    public static DocumentReindexer create(Config config,
                                           StorageEngine storageEngine,
                                           VolumeManager volumeManager,
                                           IndexOrchestrator indexOrchestrator,
                                           HierarchicalCuckooFilter hierarchicalFilter,
                                           DocumentRegistry registry,
                                           ChunkIndex chunkIndex) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(indexOrchestrator, "indexOrchestrator cannot be null");
        Objects.requireNonNull(registry, "registry cannot be null");

        TextChunker chunker = new TextChunker();

        LLMClientConfig llmClientConfig = LLMClientConfig.fromConfig(config.llm());
        LLMHttpClient llmClient = new LLMHttpClient(llmClientConfig);

        UpsertHandler upsertHandler = new UpsertHandler(
            registry,
            hierarchicalFilter,
            StorageChunkWriter.tombstoneAdapter(storageEngine)
        );

        OffsetAwareStorageWriter offsetWriter = new StorageChunkWriter(storageEngine);

        DocumentBatchPublisher batchPublisher = new DocumentBatchPublisher(
            indexOrchestrator.getVisibilityTracker(),
            registry,
            hierarchicalFilter,
            DEFAULT_PUBLISH_SEGMENT_ID,
            () -> { /* write log sync is performed by the storage engine */ },
            null
        );

        boolean rollingReplacement = volumeManager != null && storageEngine.getCatalog() != null;
        DocumentReindexer reindexer;
        if (rollingReplacement) {
            LOGGER.info("DocumentReindexer configured with rolling replacement support");
            reindexer = new DocumentReindexer(
                chunker,
                llmClient,
                upsertHandler,
                registry,
                offsetWriter,
                batchPublisher,
                storageEngine.getCatalog(),
                storageEngine.getLifecycleManager(),
                indexOrchestrator.getVisibilityTracker()
            );
        } else {
            LOGGER.info("DocumentReindexer configured with atomic publication (no rolling replacement)");
            reindexer = new DocumentReindexer(
                chunker,
                llmClient,
                upsertHandler,
                registry,
                offsetWriter,
                batchPublisher
            );
        }

        if (chunkIndex != null) {
            // The text supplier reads through the same storage engine the reindexer writes to,
            // so what compaction rebuilds a segment from is what a query would have resolved.
            reindexer.setLexicalIndex(chunkIndex, StoredChunkText.of(storageEngine));
        }
        return reindexer;
    }
}
