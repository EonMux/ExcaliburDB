package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.retrieval.LexicalQueryService;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.store.StorageEngine;

import java.util.Objects;

/**
 * The collaborators a node's routes are built from, handed to a caller in the same process.
 *
 * <p>What {@link ExcaliburDBServer#startInProcess()} returns: the node as assembly left it, with
 * the one difference that nothing is listening. Every field is the instance the HTTP routes would
 * have used - the query pipeline after the atomicity filter was installed on it, the embedding
 * client ingestion embeds with - so a caller that searches through these searches exactly what
 * {@code POST /v1/query} would.</p>
 *
 * @param config              The node's configuration
 * @param storageEngine       Where chunk text is read back from
 * @param queryPipeline       The vector search pipeline, as the routes would have used it
 * @param embeddingClient     Embeds queries with the model ingestion used, or null on a node
 *                            that keeps no vectors
 * @param lexicalQueryService BM25 over the chunk index, or null on a node that keeps none
 * @param ingestionOrchestrator Chunks, embeds, writes and publishes documents
 * @param documentRegistry    Which documents the node holds, and under which chunk ids
 * @param documentErasure     Removes documents from storage, the lexical index and the vectors
 * @param sourceCatalog       The crawl registrations the data directory records, or null
 */
public record InProcessServices(Config config,
                                StorageEngine storageEngine,
                                QueryPipeline queryPipeline,
                                EmbeddingClient embeddingClient,
                                LexicalQueryService lexicalQueryService,
                                IngestionOrchestrator ingestionOrchestrator,
                                DocumentRegistry documentRegistry,
                                DocumentErasureService documentErasure,
                                SourceCatalog sourceCatalog) {

    /**
     * @throws NullPointerException if a collaborator every assembled node has is null
     */
    public InProcessServices {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        Objects.requireNonNull(queryPipeline, "queryPipeline cannot be null");
        Objects.requireNonNull(ingestionOrchestrator, "ingestionOrchestrator cannot be null");
        Objects.requireNonNull(documentRegistry, "documentRegistry cannot be null");
        Objects.requireNonNull(documentErasure, "documentErasure cannot be null");
    }
}
