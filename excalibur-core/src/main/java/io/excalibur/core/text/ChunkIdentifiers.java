package io.excalibur.core.text;

import java.util.Objects;

/**
 * The single naming convention for documents and chunks across the ingestion pipeline.
 *
 * <h2>Why one convention</h2>
 * <p>Four independent components have to agree on these strings:</p>
 * <ul>
 *   <li>{@code IngestionOrchestrator} and {@code DocumentReindexer} write chunks to storage
 *       under a chunk id</li>
 *   <li>{@code DocumentRegistry} keys metadata by {@code sourceId:documentId} and stores the
 *       chunk ids that {@code UpsertHandler} later tombstones</li>
 *   <li>excalibur-shard's {@code HnswIntegrationWorker} derives a document id from the storage
 *       key by stripping the {@code :chunk:N} suffix, and registers it as the HNSW node's
 *       document id</li>
 *   <li>excalibur-query's {@code DocumentAtomicityFilter} looks that document id up in the
 *       visibility tracker</li>
 * </ul>
 * <p>A mismatch between any two of them is silent: a reindex fails to tombstone the previous
 * version, or a query drops every result because the document was registered under a different
 * key than the one the filter asks for.</p>
 *
 * <h2>Format</h2>
 * <pre>
 *   document key : sourceId ":" documentId
 *   chunk id     : sourceId ":" documentId ":chunk:" chunkIndex
 * </pre>
 * <p>Document ids may themselves contain colons (an HTTP source uses absolute URLs), so a chunk
 * id must be split on the {@code :chunk:} marker and never on the last colon.</p>
 */
public final class ChunkIdentifiers {

    /** Separator between the source id and the document id. */
    private static final String KEY_SEPARATOR = ":";

    /** Marker introducing the chunk index in a chunk id. */
    public static final String CHUNK_MARKER = ":chunk:";

    private ChunkIdentifiers() {
        throw new AssertionError("Utility class");
    }

    /**
     * Builds the document key used by the registry and the visibility tracker.
     *
     * @param sourceId   The source identifier (must not be null)
     * @param documentId The document identifier within the source (must not be null)
     * @return {@code sourceId:documentId}
     * @throws NullPointerException if either argument is null
     */
    public static String documentKey(String sourceId, String documentId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return sourceId + KEY_SEPARATOR + documentId;
    }

    /**
     * Builds the storage key for one chunk of a document.
     *
     * @param sourceId   The source identifier (must not be null)
     * @param documentId The document identifier within the source (must not be null)
     * @param chunkIndex The zero-based chunk index (must not be negative)
     * @return {@code sourceId:documentId:chunk:chunkIndex}
     * @throws NullPointerException     if either identifier is null
     * @throws IllegalArgumentException if chunkIndex is negative
     */
    public static String chunkId(String sourceId, String documentId, int chunkIndex) {
        return chunkIdForDocumentKey(documentKey(sourceId, documentId), chunkIndex);
    }

    /**
     * Builds the storage key for one chunk from an already-qualified document key.
     *
     * @param documentKey The {@code sourceId:documentId} key (must not be null)
     * @param chunkIndex  The zero-based chunk index (must not be negative)
     * @return {@code documentKey:chunk:chunkIndex}
     * @throws NullPointerException     if documentKey is null
     * @throws IllegalArgumentException if chunkIndex is negative
     */
    public static String chunkIdForDocumentKey(String documentKey, int chunkIndex) {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be non-negative: " + chunkIndex);
        }
        return documentKey + CHUNK_MARKER + chunkIndex;
    }

    /**
     * Recovers the document key from a chunk id.
     *
     * <p>Splits on the last {@code :chunk:} marker so that document ids containing colons
     * (URLs, Windows paths) round-trip correctly. A string carrying no marker is returned
     * unchanged: it is already a document key.</p>
     *
     * @param chunkId The chunk id (must not be null)
     * @return The owning document key
     * @throws NullPointerException if chunkId is null
     */
    public static String documentKeyOfChunk(String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        int marker = chunkId.lastIndexOf(CHUNK_MARKER);
        return marker > 0 ? chunkId.substring(0, marker) : chunkId;
    }
}
