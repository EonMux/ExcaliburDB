package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.erasure.SegmentVectorEraser;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.StoredChunkText;
import com.eonmux.excalibur.ingest.pipeline.DocumentBatchPublisher;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.OffsetAwareStorageWriter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.LlmConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Builds the {@link DocumentReindexer} and everything it writes through.
 *
 * <p>Kept separate from the application bootstrap because the reindex path has its
 * own correctness requirements: superseded chunks must actually be tombstoned, chunks
 * must land in the segment their key routes to, and each chunk's terms must be filed in
 * the membership filter under that segment.</p>
 *
 * <p>The last is the one a reindex could get wrong without anything failing. Once a
 * segment's level is trusted, stage 1 of the query pipeline rules the segment out for every
 * term the level does not name, so a reindexed-in term filed under any other segment - or
 * under none - hides the chunk that holds it from every query for it until the segment is
 * next rebuilt. The storage engine reports the segment each write landed in, and the
 * reindexer files the chunk's terms there as it writes it, through the same recorder a first
 * ingest uses. The batch publisher is given no filter of its own: a document's chunks span
 * segments, so no one segment id could describe a publication.</p>
 *
 * <p>The lexical index is part of that. A reindex is the one operation that removes content a
 * query could still be matching, so a reindexer that wrote to storage alone would leave deleted
 * documents answering BM25 queries under identifiers whose chunks are gone.</p>
 *
 * <p>The embedding half is optional, because on many deployments it is simply not there. A node
 * with no embedding endpoint chunked, stored and term-indexed its corpus at ingestion; a reindex
 * of that corpus does exactly the same again, and the chunks it writes carry no vector - which
 * is what the ones already in storage carry.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the components it returns carry their own thread-safety guarantees.</p>
 *
 * @author ExcaliburDB
 */
public final class ReindexerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexerFactory.class);

    private ReindexerFactory() {
        // Utility class
    }

    /**
     * Builds the client a reindex re-embeds through, or none on a node that indexes lexically.
     *
     * <p>A reindex is two halves - re-chunk and re-index, and re-embed - and only the second
     * needs an embedding endpoint. Tying the whole operation to that endpoint is what left a
     * BM25-only node answering 503 to every {@code POST /v1/reindex} for the life of the
     * process: its corpus could be indexed once at registration and could never follow a later
     * edit through any documented route.</p>
     *
     * <p>The test is the same one ingestion applied when it built the corpus, so the two cannot
     * disagree about whether this node's chunks carry vectors. A node that declined embeddings
     * stored none, and a reindex that produced them would leave one corpus indexed two ways.</p>
     *
     * @param llm The node's LLM configuration
     * @return A client, or null when this node does not embed its corpus
     */
    static LLMHttpClient embeddingClient(LlmConfig llm) {
        if (!RetrievalCapabilities.from(llm).embedCorpus()) {
            LOGGER.info("Reindexing will re-chunk and re-index without embedding: this node "
                + "does not embed its corpus, and its chunks are stored and ranked lexically.");
            return null;
        }
        return new LLMHttpClient(LLMClientConfig.fromConfig(llm));
    }

    /**
     * Creates a fully wired document reindexer.
     *
     * @param config             Application configuration (LLM settings)
     * @param storageEngine      Storage engine chunks are written to and tombstoned in
     * @param volumeManager      Volume manager, enabling rolling replacement when present
     * @param indexOrchestrator  Orchestrator supplying the visibility tracker
     * @param hierarchicalFilter Filter that must see both inserts and tombstones, and that
     *                           each written chunk's terms are filed in under the segment it
     *                           landed in; may be null on a node that prunes on none
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

        // The same sizing ingestion used. A reindex that chunked differently would republish
        // every document under chunk ids the original pass never wrote, leaving the corpus
        // split between two chunkings of the same text.
        TextChunker chunker = new TextChunker(config.ingest().getChunkTargetTokens(),
            config.ingest().getChunkOverlapTokens());

        LLMHttpClient llmClient = embeddingClient(config.llm());

        UpsertHandler upsertHandler = new UpsertHandler(
            registry,
            hierarchicalFilter,
            StorageChunkWriter.tombstoneAdapter(storageEngine)
        );

        OffsetAwareStorageWriter offsetWriter = new StorageChunkWriter(storageEngine);

        DocumentBatchPublisher batchPublisher = new DocumentBatchPublisher(
            indexOrchestrator.getVisibilityTracker(),
            registry,
            (CuckooFilter) null,
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

        reindexer.setMembershipFilter(hierarchicalFilter);

        if (chunkIndex != null) {
            // The text supplier reads through the same storage engine the reindexer writes to,
            // so what compaction rebuilds a segment from is what a query would have resolved.
            reindexer.setLexicalIndex(chunkIndex, StoredChunkText.of(storageEngine));
        }

        // The counterpart of the lexical retirement above, for the vector signal. A reindex that
        // finds a document deleted at the source tombstones its chunks and retires its terms; a
        // graph node left behind still answers with the document's name and a distance.
        reindexer.setVectorEraser(SegmentVectorEraser.of(storageEngine));
        return reindexer;
    }
}
