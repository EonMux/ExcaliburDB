package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.processor.TextContent;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.DocumentValidators;
import com.eonmux.excalibur.ingest.source.FetchedDocument;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer.DocumentResult;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Re-indexes one document, from the bytes a source handed back to the moment the new version
 * becomes visible.
 *
 * <h2>Why this is one unit and not several</h2>
 * <p>A document is indexed whole or not at all, and that promise is enforced by the order of the
 * steps here rather than by any one of them: every chunk is written first, the failure count is
 * consulted second, and only then is anything published or registered. Splitting the write loop
 * away from the decision that follows it would let a caller publish a half-written document
 * simply by calling the two in the wrong order, and the symptom would not be a partial answer -
 * it would be a registry entry carrying the new content hash beside a partial chunk list, which
 * change detection then reads as unchanged forever. The failed chunks would never be retried.</p>
 *
 * <h2>The two ways a document becomes visible</h2>
 * <p>A node with a {@link DocumentBatchPublisher} publishes atomically: the chunks are
 * accumulated in a {@link DocumentBatchBuffer}, the buffer is sealed, and the publication
 * advances the visibility epoch for all of them at once. A node without one registers the
 * document directly. Either way the commit point is a single call under the document's lock, so
 * an erasure of the same document cannot interleave with it and leave a chunk in storage that
 * nothing names.</p>
 *
 * <h2>What the graph is told</h2>
 * <p>A published version is reported to the graph exactly as a first ingest's is, through the
 * {@link GraphReporter} ingestion reports through: numbered under the document's lock, at the
 * commit point, as a replacement when the registry held a version of the document there and as a
 * first publication when it did not; and reported once the lock is released and the version is
 * searchable. Without it a re-crawl that finds a page changed leaves the graph answering with what
 * the page used to say, and one that finds a page new never extracts it at all.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>An instance is a plain holder of collaborators and carries no mutable state, so it is safe
 * to share; the collaborators carry their own guarantees. It is built per call, which is also
 * what keeps it reading the lexical index and eraser wired now rather than at construction.</p>
 */
final class DocumentReindexStep {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReindexStep.class);

    /**
     * Stores one chunk, wherever this step's target happens to be.
     *
     * <p>The loop that drives it is the same for every target, and the only thing that varies is
     * where the bytes land and what comes back, so the difference is expressed here rather than
     * by a second copy of the loop.</p>
     */
    @FunctionalInterface
    private interface ChunkSink {

        /**
         * @param chunkId   The chunk's deterministic identifier
         * @param chunk     The chunk itself
         * @param embedding Its vector, or null on a node that keeps none
         * @return true if the chunk was written
         */
        boolean store(String chunkId, TextChunk chunk, float[] embedding);
    }

    /** What the write loop got through, before anything is published. */
    private record ChunkWrites(List<String> chunkIds, int successes, int failures,
                               List<String> errors) {
    }

    private final TextChunker chunker;

    /** Embeds the chunks this step writes, or null on a node that indexes lexically. */
    private final LLMHttpClient llmClient;

    private final DocumentRegistry registry;
    private final UpsertHandler upsertHandler;
    private final ReindexLexicalIndex lexical;

    /** The atomic target, or null on a node that writes straight to storage. */
    private final AtomicTarget atomic;

    /** The direct writer, used only when there is no atomic target. */
    private final DocumentReindexer.StorageWriter storageWriter;

    /** Tells the graph about each version this step publishes. */
    private final GraphReporter graphReporter;

    /**
     * What publishing a version produced under the document's lock.
     *
     * @param epoch       The visibility epoch it was published at, or -1 when registered
     *                    directly
     * @param publication Its report to the graph, to run once the lock is released
     */
    private record Committed(long epoch, GraphReporter.Publication publication) {
    }

    /**
     * Where an atomically published document goes.
     *
     * @param writer    Returns the write log offset of each chunk
     * @param publisher Makes the document's chunks visible together
     */
    record AtomicTarget(OffsetAwareStorageWriter writer, DocumentBatchPublisher publisher) {

        /**
         * @throws NullPointerException if the writer or publisher is null
         */
        AtomicTarget {
            Objects.requireNonNull(writer, "writer must not be null");
            Objects.requireNonNull(publisher, "publisher must not be null");
        }
    }

    /**
     * Builds a step that publishes atomically.
     *
     * @param chunker       Splits the document into chunks
     * @param llmClient     Embeds them, or null on a node that indexes lexically
     * @param registry      Records what the document's new version consists of
     * @param upsertHandler Removes chunks an abandoned attempt left behind
     * @param lexical       The lexical index to keep in step with storage
     * @param graphReporter Tells the graph about each version published
     * @param target        Where the chunks go and how they are made visible
     */
    static DocumentReindexStep publishing(TextChunker chunker, LLMHttpClient llmClient,
                                          DocumentRegistry registry, UpsertHandler upsertHandler,
                                          ReindexLexicalIndex lexical,
                                          GraphReporter graphReporter, AtomicTarget target) {
        return new DocumentReindexStep(chunker, llmClient, registry, upsertHandler, lexical,
            graphReporter, Objects.requireNonNull(target, "target must not be null"), null);
    }

    /**
     * Builds a step that writes straight to storage, for a node with no batch publisher.
     *
     * @param chunker       Splits the document into chunks
     * @param llmClient     Embeds them, or null on a node that indexes lexically
     * @param registry      Records what the document's new version consists of
     * @param upsertHandler Removes chunks an abandoned attempt left behind
     * @param lexical       The lexical index to keep in step with storage
     * @param graphReporter Tells the graph about each version published
     * @param storageWriter Persists one chunk
     */
    static DocumentReindexStep writingDirectly(TextChunker chunker, LLMHttpClient llmClient,
                                               DocumentRegistry registry,
                                               UpsertHandler upsertHandler,
                                               ReindexLexicalIndex lexical,
                                               GraphReporter graphReporter,
                                               DocumentReindexer.StorageWriter storageWriter) {
        return new DocumentReindexStep(chunker, llmClient, registry, upsertHandler, lexical,
            graphReporter, null,
            Objects.requireNonNull(storageWriter, "storageWriter must not be null"));
    }

    private DocumentReindexStep(TextChunker chunker, LLMHttpClient llmClient,
                                DocumentRegistry registry, UpsertHandler upsertHandler,
                                ReindexLexicalIndex lexical,
                                GraphReporter graphReporter, AtomicTarget atomic,
                                DocumentReindexer.StorageWriter storageWriter) {
        this.chunker = chunker;
        this.llmClient = llmClient;
        this.registry = registry;
        this.upsertHandler = upsertHandler;
        this.lexical = lexical;
        this.graphReporter =
            Objects.requireNonNull(graphReporter, "graphReporter must not be null");
        this.atomic = atomic;
        this.storageWriter = storageWriter;
    }

    /**
     * Re-indexes the document: chunks it, embeds where this node embeds at all, writes every
     * chunk, and publishes the lot or none of it.
     *
     * <p>The stop is not a checkpoint here - a document is written whole or not at all - but it
     * may interrupt the embedding requests, which wait on the endpoint and touch nothing this
     * node shares. A chunk whose embedding a stop cut short fails like any other unembeddable
     * chunk, so the document is left on its previous version and its writes are rolled back,
     * with the thread's interrupt flag clear for every write, rollback included.</p>
     *
     * @param sourceId   The source the document belongs to
     * @param documentId The document
     * @param fetched    What the source handed back
     * @param stop       The run's stop signal
     * @return The outcome, carrying the chunk ids the new version consists of
     * @throws IllegalArgumentException if the content or its chunk count exceeds
     *                                  {@link DocumentLimits}
     */
    DocumentResult reindex(String sourceId, String documentId, FetchedDocument fetched,
                           StopSignal stop) {
        Objects.requireNonNull(stop, "stop must not be null");
        long startTime = System.currentTimeMillis();

        byte[] content = fetched.getContent();
        if (content == null || content.length == 0) {
            return DocumentResult.skipped(documentId, DocumentResult.SkipReason.EMPTY_CONTENT,
                System.currentTimeMillis() - startTime);
        }

        if (content.length > DocumentLimits.MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Content length " + content.length
                + " exceeds maximum " + DocumentLimits.MAX_DOCUMENT_BYTES);
        }

        // The same gate the first crawl applies. Without it a re-crawl is the way binary
        // content gets into an index that refused it once: a source is crawled by
        // DocumentIngestor only the first time, and by this step on every pass after it.
        String text = TextContent.decode(content, fetched.getCharset());
        if (text == null) {
            LOG.info("Skipping document {}: its {} bytes are not text in {}",
                documentId, content.length, fetched.getCharset());
            return DocumentResult.skipped(documentId, DocumentResult.SkipReason.NOT_TEXT,
                System.currentTimeMillis() - startTime);
        }

        List<TextChunk> chunks = chunker.chunk(documentId, text);
        if (chunks.isEmpty()) {
            return DocumentResult.skipped(documentId, DocumentResult.SkipReason.NO_CHUNKS,
                System.currentTimeMillis() - startTime);
        }
        // The bound the first crawl applies. Without it, a document the first crawl refused is
        // "new" to every re-crawl after it, and the re-crawl indexes it whole.
        if (chunks.size() > DocumentLimits.MAX_CHUNKS_PER_DOCUMENT) {
            throw new IllegalArgumentException("Document produced " + chunks.size()
                + " chunks, over the per-document limit of "
                + DocumentLimits.MAX_CHUNKS_PER_DOCUMENT);
        }

        if (atomic != null) {
            return publishAtomically(sourceId, documentId, fetched, content, chunks, startTime,
                stop);
        }
        return writeDirectly(sourceId, documentId, fetched, content, chunks, startTime, stop);
    }

    /**
     * Writes every chunk, accumulates them in a batch buffer and publishes them together.
     *
     * <p>The buffer is sealed and the publication made in one step under the document's lock:
     * the registry and visibility entries written there are the ones an erasure of the same
     * document removes, and between them they decide whether a chunk in storage is still named
     * by anything.</p>
     */
    private DocumentResult publishAtomically(String sourceId, String documentId,
                                             FetchedDocument fetched, byte[] content,
                                             List<TextChunk> chunks, long startTime,
                                             StopSignal stop) {

        DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, sourceId);

        ChunkWrites writes = writeChunks(sourceId, documentId, chunks, stop,
            (chunkId, chunk, embedding) -> {
                long offset = atomic.writer().writeChunkWithOffset(chunkId, chunk.getText(),
                    embedding);
                if (offset < 0) {
                    return false;
                }
                batchBuffer.addChunk(chunkId, chunk, offset, embedding);
                return true;
            });

        // A document is indexed whole or not at all. Publishing the chunks that survived would
        // register the new content hash beside a partial chunk list, and change detection compares
        // exactly that hash: the document would then read as unchanged forever, so the chunks that
        // failed would never be retried and their content would be permanently missing. Leaving
        // the previous registry entry alone keeps the document classified as changed, so the next
        // run picks it up again.
        if (writes.failures() > 0) {
            // Left unchanged means left as it was, which includes the chunks this attempt had
            // already written past the surviving entry: nothing names those, so nothing could
            // ever remove them either.
            rollback().undo(sourceId, documentId, writes.chunkIds());
            return new DocumentResult(documentId, chunks.size(), 0, writes.failures(),
                System.currentTimeMillis() - startTime, leftUnchanged(writes, chunks.size()));
        }

        batchBuffer.seal();

        DocumentMetadata metadata = metadataOf(sourceId, documentId, content,
            fetched.validators(), writes.chunkIds());

        try {
            Committed committed = commit(sourceId, documentId,
                () -> atomic.publisher().publish(batchBuffer, metadata).epoch());

            long elapsed = System.currentTimeMillis() - startTime;
            String errorMsg = writes.errors().isEmpty() ? null
                : String.join("; ", writes.errors());

            LOG.info("Document {} published atomically at epoch {}: {} chunks ({} successful, "
                    + "{} failed) in {}ms", documentId, committed.epoch(),
                chunks.size(), writes.successes(), writes.failures(), elapsed);

            indexAndReport(writes.chunkIds(), chunks, committed, fetched);
            return new DocumentResult(documentId, chunks.size(), writes.successes(),
                writes.failures(), elapsed, errorMsg, writes.chunkIds());

        } catch (DocumentBatchPublisher.PublicationException e) {
            // Publication failed - document should not be visible
            String errorMsg = "Atomic publication failed: " + e.getMessage();
            if (!writes.errors().isEmpty()) {
                errorMsg += "; " + String.join("; ", writes.errors());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.error("Atomic publication failed for document {}: {}", documentId,
                e.getMessage());

            rollback().undo(sourceId, documentId, writes.chunkIds());
            // The failed chunk count is the chunks that could not be written, and here every
            // one of them was written without trouble - it is the publication that failed, once,
            // for the document. Counting the whole document again per chunk inflated the number
            // an operator sizes the problem from; the failure itself is not lost, because the
            // error below is what the run reports and no chunk is counted as created.
            return new DocumentResult(documentId, chunks.size(), 0, writes.failures(),
                elapsed, errorMsg);
        }
    }

    /**
     * Writes every chunk straight to storage and registers the document, for a node with no
     * epoch-based visibility management.
     */
    private DocumentResult writeDirectly(String sourceId, String documentId,
                                         FetchedDocument fetched, byte[] content,
                                         List<TextChunk> chunks, long startTime,
                                         StopSignal stop) {

        ChunkWrites writes = writeChunks(sourceId, documentId, chunks, stop,
            (chunkId, chunk, embedding) ->
                storageWriter.writeChunk(chunkId, chunk.getText(), embedding));

        // As in the atomic paths: the registry entry is what change detection reads, so recording
        // the new hash for anything less than a complete document retires it from ever being
        // reindexed again. This path had no such guard at all - a run with the embedding endpoint
        // down replaced the entry with one naming no chunks, leaving the previous chunks in
        // storage with nothing recording their ids.
        if (writes.failures() > 0) {
            long failedElapsed = System.currentTimeMillis() - startTime;
            LOG.warn("Document {} left unchanged: {} of {} chunks failed",
                documentId, writes.failures(), chunks.size());
            // As in the atomic paths: what was written past the surviving entry is named by
            // nothing and has to go with the rest of the attempt.
            rollback().undo(sourceId, documentId, writes.chunkIds());
            return new DocumentResult(documentId, chunks.size(), 0, writes.failures(),
                failedElapsed, leftUnchanged(writes, chunks.size()));
        }

        DocumentMetadata metadata = metadataOf(sourceId, documentId, content,
            fetched.validators(), writes.chunkIds());

        // Under the document's lock, for the reason publishAtomically holds it.
        Committed committed = commit(sourceId, documentId, () -> {
            registry.register(metadata);
            return -1L;
        });

        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = writes.errors().isEmpty() ? null : String.join("; ", writes.errors());

        LOG.debug("Re-indexed document {}: {} chunks ({} successful, {} failed) in {}ms",
            documentId, chunks.size(), writes.successes(), writes.failures(), elapsed);

        indexAndReport(writes.chunkIds(), chunks, committed, fetched);
        return new DocumentResult(documentId, chunks.size(), writes.successes(),
            writes.failures(), elapsed, errorMsg, writes.chunkIds());
    }

    /**
     * Makes a version visible under the document's lock, and numbers its report to the graph
     * there.
     *
     * <p>Whether the version replaces another is read under the same lock and just before the
     * commit, because the commit is what overwrites the answer. Read any earlier and a concurrent
     * re-post or erasure could change it: a version reported as new would stage its facts beside
     * the ones already staged for the document, and the graph would go on saying what the
     * document used to say. Numbered here rather than after the lock is released for the reason
     * {@link GraphReporter} gives: it is what keeps a slow report of this version from undoing a
     * later one's.</p>
     *
     * @param publish Makes the version visible, returning its epoch or -1 when it has none
     * @throws DocumentBatchPublisher.PublicationException if the publication fails, in which case
     *                                                     nothing is numbered
     */
    private Committed commit(String sourceId, String documentId, LongSupplier publish) {
        String documentKey = ChunkIdentifiers.documentKey(sourceId, documentId);
        return DocumentLocks.shared().inDocument(documentKey, () -> {
            boolean replacing = registry.get(sourceId, documentId) != null;
            long epoch = publish.getAsLong();
            return new Committed(epoch, graphReporter.published(documentKey, replacing));
        });
    }

    /**
     * Indexes the published version lexically and then reports it to the graph.
     *
     * <p>Both outside the lock: neither rewrites what the lock guards, and extraction may call a
     * model and take seconds. The report comes last so its facts are never about a version that
     * is not yet searchable, and in a {@code finally} because a publication numbered and never
     * reported would hold its document's entry in the reporter for good.</p>
     */
    private void indexAndReport(List<String> chunkIds, List<TextChunk> chunks,
                                Committed committed, FetchedDocument fetched) {
        try {
            lexical.index(chunkIds, chunks);
        } finally {
            graphReporter.report(committed.publication(), fetched);
        }
    }

    /**
     * Embeds and stores every chunk, collecting what failed rather than stopping at the first
     * failure.
     *
     * <p>Every chunk is attempted because the decision this feeds is about the document as a
     * whole: a caller that stopped early would report one failure where there were ten, and an
     * operator reading the result would retry against an endpoint that is still down.</p>
     */
    private ChunkWrites writeChunks(String sourceId, String documentId, List<TextChunk> chunks,
                                    StopSignal stop, ChunkSink sink) {
        List<String> chunkIds = new ArrayList<>(chunks.size());
        List<String> errors = new ArrayList<>();
        int successes = 0;
        int failures = 0;

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(sourceId, documentId, i);

            try {
                float[] embedding =
                    stop.interruptibly(() -> embed(chunk.getText(), sourceId, documentId));

                if (sink.store(chunkId, chunk, embedding)) {
                    chunkIds.add(chunkId);
                    successes++;
                } else {
                    failures++;
                    errors.add("Storage write failed for chunk " + i);
                    LOG.warn("Failed to write chunk {} for document {}", i, documentId);
                }
            } catch (LLMException e) {
                failures++;
                errors.add("Embedding failed for chunk " + i + ": " + e.getMessage());
                LOG.error("Failed to generate embedding for chunk {} of document {}: {}",
                    i, documentId, e.getMessage());
            }
        }

        return new ChunkWrites(chunkIds, successes, failures, errors);
    }

    /** The reason a document was left on its previous version, as the caller will read it. */
    private static String leftUnchanged(ChunkWrites writes, int totalChunks) {
        return "Document left unchanged, " + writes.failures() + " of " + totalChunks
            + " chunks failed: " + String.join("; ", writes.errors());
    }

    /**
     * Describes the version this attempt produced.
     *
     * <p>Built only once every chunk has landed: the content hash recorded here is what change
     * detection compares on the next run, so it must never describe a document storage does not
     * hold in full. The same goes for the validators: the next run hands them back to the source,
     * and a source that answers "not modified" to them is never asked for the body again.</p>
     */
    private static DocumentMetadata metadataOf(String sourceId, String documentId, byte[] content,
                                               DocumentValidators version,
                                               List<String> chunkIds) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(version.lastModifiedMillis())
            .entityTag(version.entityTag())
            .contentHash(DocumentRegistry.computeContentHash(content))
            .contentLength(content.length)
            .chunkIds(chunkIds)
            .indexTimeMillis(System.currentTimeMillis())
            .build();
    }

    /**
     * Turns one chunk into a vector, or reports that this node keeps none.
     *
     * <p>Null is the answer on a lexical-only node, and it is a value rather than a failure:
     * {@link com.eonmux.excalibur.core.codec.ChunkPayloadCodec} stores a chunk with dimension 0, which
     * is what ingestion already writes there, and BM25 ranks it exactly as it ranks every other
     * chunk that node holds. Refusing to reindex at all instead - which is what a node with no
     * embedding endpoint used to do - left a corpus that could be indexed once and never
     * followed its source again.</p>
     *
     * <p>Fabricating a zero vector would be the worse answer: it is a real point in the vector
     * space, equidistant from everything, and it would be returned by vector search with a
     * score.</p>
     *
     * @throws LLMException if the embedding endpoint is configured and refuses
     */
    private float[] embed(String text, String sourceId, String documentId) {
        if (llmClient == null) {
            return null;
        }
        return llmClient.generateEmbedding(text, "reindex:" + sourceId + ":" + documentId);
    }

    /**
     * Undoes the writes of a document that could not be published.
     *
     * <p>Built per failure rather than held as a field: it is only reached on the path where a
     * document did not publish, and building it there keeps it reading the lexical index that is
     * wired now rather than the one that was wired at construction.</p>
     */
    private ReindexRollback rollback() {
        return new ReindexRollback(registry, upsertHandler, lexical::retire);
    }

    /**
     * Generates a unique chunk ID from a source ID, document ID and chunk index.
     *
     * <p>Format: {@code sourceId:documentId:chunk:index}. The source-qualified form is what
     * excalibur-shard registers whole as an HNSW node's name, and what the ingestion pipeline
     * writes, so a reindex overwrites and tombstones exactly the keys the
     * initial ingest created. See {@link ChunkIdentifiers}.</p>
     *
     * <p>Domain correctness: chunk IDs are unique, deterministic, and URL-safe.</p>
     */
    private static String generateChunkId(String sourceId, String documentId, int chunkIndex) {
        return ChunkIdentifiers.chunkId(sourceId, documentId, chunkIndex);
    }
}
