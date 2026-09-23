package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.processor.TextContent;
import com.eonmux.excalibur.ingest.erasure.DocumentErasureService;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes one document to storage and publishes it, as a unit.
 *
 * <p>Every chunk is embedded and written before the visibility epoch is advanced, and the
 * document is registered with the {@link DocumentVisibilityTracker} under its
 * {@code sourceId:documentId} key - the same key excalibur-shard derives from the storage key and
 * excalibur-query's atomicity filter asks about. A document whose chunks could not all be
 * embedded or written is rolled back and left unregistered, so a query can never observe half of
 * it.</p>
 *
 * <p>Stateless beyond its collaborators, so concurrent documents may be ingested through one
 * instance.</p>
 */
final class DocumentIngestor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestor.class);

    /**
     * Chunks sent to the embedding endpoint in one request.
     *
     * <p>Sixteen 512-token chunks is roughly 8k tokens, which sits inside a typical server's
     * physical batch, keeps a failed request cheap to retry, and keeps the request body small
     * enough to be unremarkable.</p>
     */
    static final int EMBED_BATCH_SIZE = 16;

    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final StorageEngine storageEngine;
    private final DocumentVisibilityTracker visibilityTracker; // optional, nullable - visibility epoch

    /**
     * Registry that records what was ingested so a later reindex can detect changes and
     * tombstone the previous version. Derived from the visibility tracker, which owns it.
     * Null when no visibility tracker is configured.
     */
    private final DocumentRegistry documentRegistry;

    /**
     * Tombstones what a document's previous version held and its new version does not, or null
     * when there is no registry to say what a previous version held.
     */
    private final UpsertHandler upsertHandler;

    private final LexicalSideIndex lexicalSideIndex;
    private final SegmentTermRecorder termRecorder;
    private final GraphReporter graphReporter;

    /**
     * @param textChunker         Splits a fetched document into chunks
     * @param embeddingClient     Embeds those chunks, or null for lexical-only ingestion
     * @param storageEngine       Where chunks are written
     * @param visibilityTracker   Publishes documents at an epoch, or null for no atomicity
     * @param documentRegistry    Records what was ingested, or null when nothing is recorded
     * @param lexicalSideIndex    The BM25 index kept beside storage
     * @param termRecorder        Files written terms under their segment
     * @param graphReporter       Reports published documents onwards
     */
    DocumentIngestor(TextChunker textChunker,
                     EmbeddingClient embeddingClient,
                     StorageEngine storageEngine,
                     DocumentVisibilityTracker visibilityTracker,
                     DocumentRegistry documentRegistry,
                     LexicalSideIndex lexicalSideIndex,
                     SegmentTermRecorder termRecorder,
                     GraphReporter graphReporter) {
        this.textChunker = Objects.requireNonNull(textChunker, "TextChunker cannot be null");
        // Nullable, deliberately. A null client is lexical-only ingestion: chunks are written,
        // term-indexed and published with no vector, which is what lets Lucene and the graph
        // serve retrieval without a corpus-wide embedding pass first. Vector search is then
        // simply a signal this deployment does not have.
        this.embeddingClient = embeddingClient;
        this.storageEngine = Objects.requireNonNull(storageEngine, "StorageEngine cannot be null");
        this.visibilityTracker = visibilityTracker; // may be null
        this.documentRegistry = documentRegistry; // may be null
        // Over the same registry and the same storage the reindex path tombstones through, so a
        // document replaced here retires its previous version exactly as a re-crawl would.
        this.upsertHandler = documentRegistry == null ? null
            : new UpsertHandler(documentRegistry, (CuckooFilter) null, this::tombstone);
        this.lexicalSideIndex = Objects.requireNonNull(lexicalSideIndex);
        this.termRecorder = Objects.requireNonNull(termRecorder);
        this.graphReporter = Objects.requireNonNull(graphReporter);
    }

    /**
     * Retires a rolled back document's graph nodes, or null on a node that keeps no vector index.
     *
     * <p>The integration worker inserts asynchronously from the write that triggered it, so a
     * document whose chunks were written and then rolled back may already be in a graph. The node
     * carries no text, but a search that ranks it says the document matched - and nothing else
     * can reach it, because a node is attributed to a document and not to a chunk id.</p>
     */
    private volatile DocumentErasureService.VectorEraser vectorEraser;

    /**
     * Wires the eraser that retires a rolled back document's graph nodes.
     *
     * @param vectorEraser The eraser, or null on a node that keeps no vector index
     */
    void setVectorEraser(DocumentErasureService.VectorEraser vectorEraser) {
        this.vectorEraser = vectorEraser;
    }

    /**
     * Ingests one document as an atomic unit.
     *
     * <p>Every chunk is embedded and written before the document is published. A failure at any
     * step rolls back the chunks already written and leaves the document unregistered, so no
     * query can observe a fragment of it.</p>
     *
     * <p>A stop of the run may interrupt the fetch and the embedding requests, and nothing else:
     * those wait on the source and the embedding endpoint and touch no file this node shares.
     * The writes that follow run with the thread's interrupt flag clear, because an interrupt
     * reaching a write closes the storage channel it was writing through - for every writer of
     * that segment, for the life of the process. A stop that lands after the fetch and the
     * embedding have finished lets the document be written whole; the run reads the stop
     * before its next document.</p>
     *
     * @param source     The source to fetch the document from
     * @param sourceId    The source's id, which qualifies the document key
     * @param documentId The document to fetch
     * @param stop       The run's stop signal
     * @return What the document produced, and whether it was dropped rather than simply empty
     * @throws SourceException if the document could not be fetched
     */
    DocumentOutcome ingest(Source source, String sourceId, String documentId, StopSignal stop)
            throws SourceException {
        FetchedDocument doc = stop.interruptibly(() -> source.fetch(documentId));

        // The sources this node builds never read past this bound, so a document reaching it
        // here came from a source that does not know the limit. It is already in memory by now,
        // which is why the sources check first; this is what keeps it from going further.
        if (doc.getContentLength() > DocumentLimits.MAX_DOCUMENT_BYTES) {
            LOGGER.warn("Skipping document {}: {} bytes exceeds the {} byte ingest limit",
                documentId, doc.getContentLength(), DocumentLimits.MAX_DOCUMENT_BYTES);
            return DocumentOutcome.DROPPED;
        }

        String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
        // Decoded with the encoding the source declared, not with an assumed UTF-8: decoding a
        // windows-1252 or UTF-16 document as UTF-8 substitutes U+FFFD silently, and the terms
        // indexed from it are then not the terms it contains. A crawl with no extension filter
        // lists every file under its root, so this is also where a PNG is told from a document:
        // decoded leniently it is kilobytes of replacement characters, and left unchecked those
        // are chunked, term-indexed, paid for at the embedding endpoint and returned as a search
        // result.
        String text = TextContent.decode(doc.getContent(), doc.getCharset());
        if (text == null) {
            LOGGER.info("Skipping document {}: its {} bytes are not text in {}",
                documentId, doc.getContentLength(), doc.getCharset());
            return DocumentOutcome.NOT_TEXT;
        }
        List<TextChunk> chunks = textChunker.chunk(documentKey, text);

        if (chunks.isEmpty()) {
            // Nothing in it to ingest, which is not the same as having failed to ingest it.
            LOGGER.debug("Document {} produced no chunks", documentId);
            return DocumentOutcome.EMPTY;
        }
        if (chunks.size() > DocumentLimits.MAX_CHUNKS_PER_DOCUMENT) {
            LOGGER.warn("Skipping document {}: {} chunks exceeds the per-document limit of {}",
                documentId, chunks.size(), DocumentLimits.MAX_CHUNKS_PER_DOCUMENT);
            return DocumentOutcome.DROPPED;
        }

        // Null means this node embeds nothing at all; a null return from embedAll means this
        // document could not be embedded and must not be published in part.
        Map<Integer, float[]> embeddings =
            embeddingClient == null ? null
                : stop.interruptibly(() -> embedAll(chunks, documentId));
        if (embeddingClient != null && embeddings == null) {
            return DocumentOutcome.DROPPED;
        }

        // Everything that rewrites what this node holds of the document happens under the
        // document's lock: the chunks, the registry entry, the visibility entry, and the
        // rollback that undoes them. An erasure running in the gap between the chunks landing
        // and the document being registered would delete chunks no entry names yet, and the
        // registration would then put an entry back for content that has gone; the opposite
        // order leaves the chunks with no entry at all, which no later erasure can find.
        DocumentOutcome outcome = DocumentLocks.shared().inDocument(documentKey,
            () -> writeAndPublish(sourceId, documentId, documentKey, doc, chunks, embeddings));
        if (outcome.dropped()) {
            return outcome;
        }

        // Outside the lock: it neither reads nor writes the registry, the visibility entry or
        // the chunks, and the graph sink may be slow.
        graphReporter.report(documentKey, doc);
        return outcome;
    }

    /** Writes every chunk and publishes the document, undoing the writes if anything fails. */
    private DocumentOutcome writeAndPublish(String sourceId, String documentId, String documentKey,
                                            FetchedDocument doc, List<TextChunk> chunks,
                                            Map<Integer, float[]> embeddings) {
        List<String> writtenKeys = new ArrayList<>(chunks.size());
        // Read under the lock and before anything is written: this is the version the new one
        // replaces, and it decides which of its chunks become surplus on success and which of
        // this attempt's writes are orphans on failure.
        DocumentMetadata previous =
            documentRegistry == null ? null : documentRegistry.get(sourceId, documentId);
        try {
            // Marked in flight before the first chunk reaches storage. This is what the
            // atomicity filter excludes on: a chunk written but not yet published is reachable
            // by a tail scan, and without the marker the filter has no way to tell that from a
            // document it simply has not heard of, so it either shows half a document or drops
            // whole ones. Publishing clears it; so does rollback.
            beginIngestion(documentKey, chunks);
            writeChunks(documentKey, chunks, embeddings, writtenKeys);
            publishDocument(sourceId, documentId, documentKey, doc, chunks);
        } catch (Exception e) {
            rollbackChunks(sourceId, documentId, documentKey, previous, writtenKeys, e);
            return DocumentOutcome.DROPPED;
        }
        lexicalSideIndex.addDocument(documentKey, lexicalChunks(documentKey, chunks));
        retireSupersededVersion(documentKey, previous, writtenKeys);
        return DocumentOutcome.published(chunks.size());
    }

    /**
     * Retires what the previous version held past the end of the one just published.
     *
     * <p>A client posting a document id again, and the first crawl of a source registered again
     * after its registration was removed, both publish here over a version this node already
     * holds. The registry entry now names only the new chunk ids, and it is the only map from a
     * document to its chunks, so the previous version's surplus tail would otherwise stay stored
     * and lexically searchable with nothing able to name it - not a re-crawl, not an erasure.
     * Retired as a reindex retires a changed document, after publication, and here under the
     * document's lock, so a query sees one version or the other and never neither.</p>
     */
    private void retireSupersededVersion(String documentKey, DocumentMetadata previous,
                                         List<String> publishedChunkIds) {
        if (previous == null) {
            return;
        }
        int retired = SupersededVersion.retire(previous.getChunkIds(), publishedChunkIds,
            lexicalSideIndex::retire, upsertHandler);
        if (retired > 0) {
            LOGGER.debug("Document {} replaced: {} chunk(s) of its previous version retired",
                documentKey, retired);
        }
    }

    /**
     * Tombstones one chunk in storage, reporting rather than throwing a refusal.
     *
     * @return true if storage held the chunk and released it
     */
    private boolean tombstone(byte[] key) {
        try {
            return storageEngine.delete(key);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to tombstone chunk {}: {}",
                new String(key, StandardCharsets.UTF_8), e.getMessage());
            return false;
        }
    }

    /**
     * What one document's ingestion produced.
     *
     * <p>The three zero cases are not the same and the run has to tell them apart: a document
     * with nothing in it was listed and had nothing to give; a dropped one was listed, had
     * something to give and did not give it; a document that is not text was listed and was
     * never a document at all. Only the second is a failure. The third is worth a count of its
     * own rather than silence, because an operator who pointed the node at a directory of
     * photographs and saw nothing indexed has no other way to learn why.</p>
     *
     * @param chunks      Chunks published, zero when none were
     * @param disposition What became of the document
     */
    record DocumentOutcome(int chunks, Disposition disposition) {

        /** What became of one document. */
        enum Disposition {
            /** Its chunks were written and published. */
            PUBLISHED,
            /** It was listed and held nothing to ingest. */
            EMPTY,
            /** It held something, and that something could not be ingested. */
            DROPPED,
            /** Its bytes are not text, so there was nothing in it to index. */
            NOT_TEXT
        }

        /** A document that held nothing to ingest. */
        static final DocumentOutcome EMPTY = new DocumentOutcome(0, Disposition.EMPTY);

        /** A document that was listed, held something, and could not be ingested. */
        static final DocumentOutcome DROPPED = new DocumentOutcome(0, Disposition.DROPPED);

        /** A document whose bytes are not text in the encoding its source declared. */
        static final DocumentOutcome NOT_TEXT = new DocumentOutcome(0, Disposition.NOT_TEXT);

        /**
         * A document whose chunks were published.
         *
         * @param chunks How many
         * @return The outcome
         */
        static DocumentOutcome published(int chunks) {
            return new DocumentOutcome(chunks, Disposition.PUBLISHED);
        }

        /**
         * Whether the document could not be ingested, rather than being empty or not text.
         *
         * @return true when this is a failure the caller needs to hear about
         */
        boolean dropped() {
            return disposition == Disposition.DROPPED;
        }

        /**
         * Whether the document was skipped because its bytes are not text.
         *
         * @return true when nothing was indexed and nothing failed
         */
        boolean notText() {
            return disposition == Disposition.NOT_TEXT;
        }
    }

    /**
     * Generates embeddings for every chunk, a batch of {@link #EMBED_BATCH_SIZE} at a time.
     *
     * <p>One request per chunk was one HTTP round trip, one queue wait and one scheduler pass
     * per 512 tokens of work. Measured against a local GPU server, request overhead rather than
     * the model was the ceiling: three different sub-1B models landed within 6% of each other,
     * which is the signature of a bottleneck outside the weights. Batching amortises that
     * overhead over a whole document, and it is also what makes a document's chunks visible to
     * the embedding endpoint as a unit, which is the precondition for any context-aware
     * embedding model to be able to see across chunk boundaries at all.</p>
     *
     * <p>Bounded rather than one request per document: a document may hold up to
     * {@link DocumentLimits#MAX_CHUNKS_PER_DOCUMENT} chunks, and a single request carrying all of
     * them would be a multi-megabyte body whose failure costs the whole document.</p>
     *
     * @return The embeddings keyed by chunk index, or null if any chunk failed
     */
    private Map<Integer, float[]> embedAll(List<TextChunk> chunks, String documentId) {
        Map<Integer, float[]> embeddings = new HashMap<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EMBED_BATCH_SIZE) {
            List<TextChunk> batch =
                chunks.subList(start, Math.min(start + EMBED_BATCH_SIZE, chunks.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (TextChunk chunk : batch) {
                texts.add(chunk.getText());
            }
            try {
                List<float[]> vectors = embeddingClient.embedBatch(texts);
                if (vectors.size() != batch.size()) {
                    // The client already checks this, so reaching here means the contract moved.
                    // Pairing vectors with the wrong chunks is undetectable downstream, so the
                    // document is abandoned rather than written from a guess.
                    LOGGER.warn("Skipping document {}: embedding endpoint returned {} vectors "
                        + "for {} chunks", documentId, vectors.size(), batch.size());
                    return null;
                }
                for (int i = 0; i < batch.size(); i++) {
                    embeddings.put(batch.get(i).getChunkIndex(), vectors.get(i));
                }
            } catch (LLMException e) {
                LOGGER.warn("Skipping document {}: failed to embed chunks {}-{}: {}",
                    documentId, batch.get(0).getChunkIndex(),
                    batch.get(batch.size() - 1).getChunkIndex(), e.getMessage());
                return null;
            }
        }
        return embeddings;
    }

    /**
     * Marks a document in flight, with the chunk ids it is about to write.
     *
     * <p>No-op without a visibility tracker: the atomicity guarantee is the tracker's, and a
     * deployment without one has already opted out of it.
     */
    private void beginIngestion(String documentKey, List<TextChunk> chunks) {
        if (visibilityTracker == null) {
            return;
        }
        visibilityTracker.beginIngestion(documentKey, chunks.size());
        for (TextChunk chunk : chunks) {
            visibilityTracker.addPendingChunk(documentKey,
                ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()));
        }
    }

    /**
     * Writes every chunk to the storage engine, recording the keys written so a later failure
     * can undo them.
     *
     * <p>Each chunk's terms are worked out once and handed both to the engine, which counts
     * them into the segment's frequency sketch, and to the membership filter.</p>
     */
    private void writeChunks(String documentKey, List<TextChunk> chunks,
                             Map<Integer, float[]> embeddings, List<String> writtenKeys) {
        for (TextChunk chunk : chunks) {
            String chunkId = ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex());
            float[] vector = embeddings == null ? null : embeddings.get(chunk.getChunkIndex());
            byte[] value = vector == null
                ? ChunkPayloadCodec.encodeTextOnly(chunk.getText())
                : ChunkPayloadCodec.encode(chunk.getText(), vector);
            List<String> terms = chunk.terms();
            long segmentId = storageEngine.putReportingSegment(
                chunkId.getBytes(StandardCharsets.UTF_8), value, terms);
            termRecorder.record(segmentId, chunkId, terms);
            writtenKeys.add(chunkId);
        }
    }

    /** A published document's chunks, keyed by identifier, in chunk order. */
    private static Map<String, String> lexicalChunks(String documentKey, List<TextChunk> chunks) {
        Map<String, String> byId = new LinkedHashMap<>(chunks.size() * 2);
        for (TextChunk chunk : chunks) {
            byId.put(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()),
                chunk.getText());
        }
        return byId;
    }

    /**
     * Advances the visibility epoch and registers the document so queries can see it.
     *
     * <p>Registration uses the {@code sourceId:documentId} key, which is what excalibur-shard
     * derives from the storage key and what excalibur-query's atomicity filter looks up. It also
     * records the document in the {@link DocumentRegistry} so a later reindex can detect the
     * change and tombstone exactly these chunk ids.</p>
     */
    private void publishDocument(String sourceId, String documentId, String documentKey,
                                 FetchedDocument doc, List<TextChunk> chunks) {
        byte[] content = doc.getContent();
        List<String> chunkIds = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            chunkIds.add(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()));
        }

        DocumentMetadata metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(doc.getLastModifiedMillis())
            // Kept so the next re-crawl can ask the source whether this version is still
            // current instead of transferring it again to find out.
            .entityTag(doc.getEntityTag())
            .contentHash(DocumentRegistry.computeContentHash(content))
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();

        if (visibilityTracker != null) {
            long epoch = visibilityTracker.advanceEpoch();
            visibilityTracker.registerFromMetadata(metadata, epoch);
            LOGGER.debug("Document {} published at epoch {} with {} chunks",
                documentKey, epoch, chunkIds.size());
        }

        if (documentRegistry != null) {
            documentRegistry.register(metadata);
        }
    }

    /**
     * Removes chunks written for a document that failed to publish.
     *
     * <p>A document with no previous version loses everything this attempt wrote, graph nodes
     * included. One that replaces a published version keeps it: the ids the surviving registry
     * entry names are content the corpus is expected to have, so only the writes past them are
     * undone, as a failed reindex undoes them - see {@link ReindexRollback}. Its graph nodes stay
     * too, because they are attributed to the document and the document is still published.</p>
     */
    private void rollbackChunks(String sourceId, String documentId, String documentKey,
                                DocumentMetadata previous, List<String> writtenKeys,
                                Exception cause) {
        LOGGER.error("Rolling back document {} after publication failure: {}",
            documentKey, cause.getMessage(), cause);

        // Clear the in-flight marker. A document left pending is invisible to every query
        // for the life of the process, and never released.
        if (visibilityTracker != null) {
            visibilityTracker.abortIngestion(documentKey);
        }

        if (previous != null) {
            new ReindexRollback(documentRegistry, upsertHandler, lexicalSideIndex::retire)
                .undo(sourceId, documentId, writtenKeys);
            return;
        }
        for (String chunkId : writtenKeys) {
            tombstone(chunkId.getBytes(StandardCharsets.UTF_8));
        }
        lexicalSideIndex.retire(writtenKeys);
        retireVectors(documentKey);
    }

    /**
     * Retires the graph nodes of a document that was rolled back.
     *
     * <p>The integration worker inserts from what storage received rather than from this call, so
     * a chunk written and then deleted may already have become a node. A node carries no text,
     * but a search that ranks one reports the document and a distance - a document this node
     * decided not to publish - and nothing else can reach it, because the registry names chunks
     * and a node is attributed to a document.</p>
     */
    private void retireVectors(String documentKey) {
        DocumentErasureService.VectorEraser eraser = this.vectorEraser;
        if (eraser == null) {
            return;
        }
        try {
            int retired = eraser.erase(documentKey);
            if (retired > 0) {
                LOGGER.debug("Rolled back document {}: {} graph node(s) retired",
                    documentKey, retired);
            }
        } catch (RuntimeException e) {
            // Reported rather than thrown, as the rest of the rollback is: the chunks leaving
            // storage is the part that can always be completed, and a graph that will not take
            // the write must not cost that.
            LOGGER.warn("Rolled back document {} stays rankable by vector search: {}",
                documentKey, e.getMessage());
        }
    }
}
