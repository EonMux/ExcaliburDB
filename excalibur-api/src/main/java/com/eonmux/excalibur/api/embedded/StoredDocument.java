package com.eonmux.excalibur.api.embedded;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One document the node holds, as its document registry records it.
 *
 * @param sourceId      The source it was ingested under
 * @param documentId    Its identifier within that source
 * @param chunkIds      The chunks it was split into, in order - the identifiers a search returns
 * @param contentLength Its size in bytes when it was ingested
 * @param indexedAt     When it was ingested
 */
public record StoredDocument(String sourceId, String documentId, List<String> chunkIds,
                             long contentLength, Instant indexedAt) {

    /**
     * @throws NullPointerException if any reference is null
     */
    public StoredDocument {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        Objects.requireNonNull(documentId, "documentId cannot be null");
        Objects.requireNonNull(indexedAt, "indexedAt cannot be null");
        chunkIds = List.copyOf(chunkIds);
    }
}
