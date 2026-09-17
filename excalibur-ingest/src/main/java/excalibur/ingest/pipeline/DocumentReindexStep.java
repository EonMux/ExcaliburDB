package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentLocks;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;

import excalibur.ingest.pipeline.DocumentReindexer.DocumentResult;

import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <h2>Thread Safety</h2>
 * <p>An instance is a plain holder of collaborators and carries no mutable state, so it is safe
 * to share; the collaborators carry their own guarantees. It is built per call, which is also
 * what keeps it reading the lexical index and eraser wired now rather than at construction.</p>
 */
final class DocumentReindexStep {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentReindexStep.class);

    /** The largest document body this step will chunk, guarding against a runaway fetch. */
    static final int MAX_CONTENT_LENGTH = 100 * 1024 * 1024;

    /**
     * Stores one chunk, wherever this step's target happens to be.
     *
     * <p>The loop that drives it is the same for every target, and the only thing that varies is
     * where the bytes land and whether an offset comes back, so the difference is expressed here
     * rather than by a second copy of the loop.</p>
     */
    @FunctionalInterface
    private interface ChunkSink {

        /**
         * @param chunkId   The chunk's deterministic identifier
         * @param chunk     The chunk itself
         * @param embedding Its vector, or null on a node that keeps none
         * @return The write log offset, or a negative value if the write failed
         */
        long store(String chunkId, TextChunk chunk, float[] embedding);
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

    /**
     * Where an atomically published document goes, and how the run says so.
     *
     * <p>Rolling replacement writes into a segment it created and the ordinary path writes
     * wherever the routing directory sends it. The mechanism is identical; only the operator
     * facing wording distinguishes them, and it is carried here so the two paths cannot drift
     * apart in anything else.</p>
     *
     * @param writer          Returns the write log offset of each chunk
     * @param publisher       Makes the document's chunks visible together
     * @param segmentTargeted Whether this is a replacement segment rather than the default target
     */
    record AtomicTarget(OffsetAwareStorageWriter writer, DocumentBatchPublisher publisher,
                        boolean segmentTargeted) {

        /**
         * @throws NullPointerException if the writer or publisher is null
         */
        AtomicTarget {
            Objects.requireNonNull(writer, "writer must not be null");
            Objects.requireNonNull(publisher, "publisher must not be null");
        }

        /** The phrase that names this target in a log line or an error message. */
        String where() {
            return segmentTargeted ? " to target segment" : "";
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
     * @param target        Where the chunks go and how they are made visible
     */
    static DocumentReindexStep publishing(TextChunker chunker, LLMHttpClient llmClient,
                                          DocumentRegistry registry, UpsertHandler upsertHandler,
                                          ReindexLexicalIndex lexical, AtomicTarget target) {
        return new DocumentReindexStep(chunker, llmClient, registry, upsertHandler, lexical,
            Objects.requireNonNull(target, "target must not be null"), null);
    }

    /**
     * Builds a step that writes straight to storage, for a node with no batch publisher.
     *
     * @param chunker       Splits the document into chunks
     * @param llmClient     Embeds them, or null on a node that indexes lexically
     * @param registry      Records what the document's new version consists of
     * @param upsertHandler Removes chunks an abandoned attempt left behind
     * @param lexical       The lexical index to keep in step with storage
     * @param storageWriter Persists one chunk
     */
    static DocumentReindexStep writingDirectly(TextChunker chunker, LLMHttpClient llmClient,
                                               DocumentRegistry registry,
                                               UpsertHandler upsertHandler,
                                               ReindexLexicalIndex lexical,
                                               DocumentReindexer.StorageWriter storageWriter) {
        return new DocumentReindexStep(chunker, llmClient, registry, upsertHandler, lexical, null,
            Objects.requireNonNull(storageWriter, "storageWriter must not be null"));
    }

    private DocumentReindexStep(TextChunker chunker, LLMHttpClient llmClient,
                                DocumentRegistry registry, UpsertHandler upsertHandler,
                                ReindexLexicalIndex lexical, AtomicTarget atomic,
                                DocumentReindexer.StorageWriter storageWriter) {
        this.chunker = chunker;
        this.llmClient = llmClient;
        this.registry = registry;
        this.upsertHandler = upsertHandler;
        this.lexical = lexical;
        this.atomic = atomic;
        this.storageWriter = storageWriter;
    }

    /**
     * Re-indexes the document: chunks it, embeds where this node embeds at all, writes every
     * chunk, and publishes the lot or none of it.
     *
     * @param sourceId   The source the document belongs to
     * @param documentId The document
     * @param fetched    What the source handed back
     * @return The outcome, carrying the chunk ids the new version consists of
     * @throws IllegalArgumentException if content exceeds maximum length
     */
    DocumentResult reindex(String sourceId, String documentId, FetchedDocument fetched) {
        long startTime = System.currentTimeMillis();

        byte[] content = fetched.getContent();
        if (content == null || content.length == 0) {
            return new DocumentResult(documentId, 0, 0, 0,
                System.currentTimeMillis() - startTime, "Empty content");
        }

        if (content.length > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                "Content length " + content.length + " exceeds maximum " + MAX_CONTENT_LENGTH);
        }

        String text = fetched.getText();

        List<TextChunk> chunks = chunker.chunk(documentId, text);
        if (chunks.isEmpty()) {
            return new DocumentResult(documentId, 0, 0, 0,
                System.currentTimeMillis() - startTime, "No chunks produced");
        }

        if (atomic != null) {
            return publishAtomically(sourceId, documentId, content, chunks,
                fetched.getLastModifiedMillis(), startTime);
        }
        return writeDirectly(sourceId, documentId, content, chunks,
            fetched.getLastModifiedMillis(), startTime);
    }

    /**
     * Writes every chunk, accumulates them in a batch buffer and publishes them together.
     *
     * <p>The buffer is sealed and the publication made in one step under the document's lock:
     * the registry and visibility entries written there are the ones an erasure of the same
     * document removes, and between them they decide whether a chunk in storage is still named
     * by anything.</p>
     */
    private DocumentResult publishAtomically(String sourceId, String documentId, byte[] content,
                                             List<TextChunk> chunks, long lastModifiedMillis,
                                             long startTime) {

        DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, sourceId);

        ChunkWrites writes = writeChunks(sourceId, documentId, chunks,
            (chunkId, chunk, embedding) -> {
                long writeLogOffset =
                    atomic.writer().writeChunkWithOffset(chunkId, chunk.getText(), embedding);
                if (writeLogOffset >= 0) {
                    batchBuffer.addChunk(chunkId, chunk, writeLogOffset, embedding);
                }
                return writeLogOffset;
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

        DocumentMetadata metadata = metadataOf(sourceId, documentId, content, lastModifiedMillis,
            writes.chunkIds());

        try {
            DocumentBatchPublisher.PublicationResult result = DocumentLocks.shared().inDocument(
                ChunkIdentifiers.documentKey(sourceId, documentId),
                () -> atomic.publisher().publish(batchBuffer, metadata));

            long elapsed = System.currentTimeMillis() - startTime;
            String errorMsg = writes.errors().isEmpty() ? null
                : String.join("; ", writes.errors());

            LOG.info("Document {} published atomically{} at epoch {}: {} chunks ({} successful, "
                    + "{} failed) in {}ms", documentId, atomic.where(), result.epoch(),
                chunks.size(), writes.successes(), writes.failures(), elapsed);

            lexical.index(writes.chunkIds(), chunks);
            return new DocumentResult(documentId, chunks.size(), writes.successes(),
                writes.failures(), elapsed, errorMsg, writes.chunkIds());

        } catch (DocumentBatchPublisher.PublicationException e) {
            // Publication failed - document should not be visible
            String errorMsg = "Atomic publication" + atomic.where() + " failed: " + e.getMessage();
            if (!writes.errors().isEmpty()) {
                errorMsg += "; " + String.join("; ", writes.errors());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.error("Atomic publication{} failed for document {}: {}", atomic.where(),
                documentId, e.getMessage());

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
    private DocumentResult writeDirectly(String sourceId, String documentId, byte[] content,
                                         List<TextChunk> chunks, long lastModifiedMillis,
                                         long startTime) {

        ChunkWrites writes = writeChunks(sourceId, documentId, chunks,
            (chunkId, chunk, embedding) ->
                storageWriter.writeChunk(chunkId, chunk.getText(), embedding) ? 0L : -1L);

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

        DocumentMetadata metadata = metadataOf(sourceId, documentId, content, lastModifiedMillis,
            writes.chunkIds());

        // Under the document's lock, for the reason publishAtomically holds it.
        DocumentLocks.shared().inDocument(ChunkIdentifiers.documentKey(sourceId, documentId),
            () -> registry.register(metadata));

        long elapsed = System.currentTimeMillis() - startTime;
        String errorMsg = writes.errors().isEmpty() ? null : String.join("; ", writes.errors());

        LOG.debug("Re-indexed document {}: {} chunks ({} successful, {} failed) in {}ms",
            documentId, chunks.size(), writes.successes(), writes.failures(), elapsed);

        lexical.index(writes.chunkIds(), chunks);
        return new DocumentResult(documentId, chunks.size(), writes.successes(),
            writes.failures(), elapsed, errorMsg, writes.chunkIds());
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
                                    ChunkSink sink) {
        List<String> chunkIds = new ArrayList<>(chunks.size());
        List<String> errors = new ArrayList<>();
        int successes = 0;
        int failures = 0;

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            String chunkId = generateChunkId(sourceId, documentId, i);

            try {
                float[] embedding = embed(chunk.getText(), sourceId, documentId);

                long writeLogOffset = sink.store(chunkId, chunk, embedding);

                if (writeLogOffset >= 0) {
                    chunkIds.add(chunkId);
                    successes++;
                } else {
                    failures++;
                    errors.add("Storage write failed for chunk " + i);
                    LOG.warn("Failed to write chunk {} for document {}{}", i, documentId, where());
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

    /** The phrase naming this step's target, empty where there is nothing to distinguish. */
    private String where() {
        return atomic == null ? "" : atomic.where();
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
     * hold in full.</p>
     */
    private static DocumentMetadata metadataOf(String sourceId, String documentId, byte[] content,
                                               long lastModifiedMillis, List<String> chunkIds) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(lastModifiedMillis)
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
     * {@link io.excalibur.core.codec.ChunkPayloadCodec} stores a chunk with dimension 0, which
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
     * excalibur-shard parses back into a document id when it registers an HNSW node, and what
     * the ingestion pipeline writes, so a reindex overwrites and tombstones exactly the keys the
     * initial ingest created. See {@link ChunkIdentifiers}.</p>
     *
     * <p>Domain correctness: chunk IDs are unique, deterministic, and URL-safe.</p>
     */
    private static String generateChunkId(String sourceId, String documentId, int chunkIndex) {
        return ChunkIdentifiers.chunkId(sourceId, documentId, chunkIndex);
    }
}
