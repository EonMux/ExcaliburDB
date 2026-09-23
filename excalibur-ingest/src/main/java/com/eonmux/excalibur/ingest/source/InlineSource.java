package com.eonmux.excalibur.ingest.source;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A source over documents a client handed in directly, rather than a target to crawl.
 *
 * <h2>Why this is a Source</h2>
 * <p>Everything ingestion does to a document - decoding, chunking, embedding, the lexical index,
 * the graph sink, the registry record that makes erasure and reindexing
 * work - happens inside the orchestrator's run over a {@link Source}. A direct write that reached
 * storage by another path would have to repeat all of it, and would drift from it. So a posted
 * batch is dressed as the thing the pipeline already knows how to ingest, and every stage applies
 * to it unchanged.</p>
 *
 * <h2>What it does not do</h2>
 * <p>It never changes. The documents arrived in one request and are ingested in that request,
 * and there is no target to go back to, so there is nothing for a re-crawl to ask about.
 * Replacing a document means posting it again, which is the same operation as posting it the
 * first time.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and the byte content it holds is copied in and out by {@link FetchedDocument}.</p>
 */
public final class InlineSource implements Source {

    /** The source type recorded for documents supplied directly. */
    public static final String TYPE = "inline";

    /**
     * Longest document identifier a supplied document may carry.
     *
     * <p>Bounded for the reason {@link Source#MAX_ID_LENGTH} is, only more so. A source id is
     * multiplied by the size of the corpus; a document id is multiplied by the document's chunk
     * count, because it is embedded in every chunk id - and a chunk id is a storage key, an entry
     * retained in the document registry for the life of the process, and a line in the registry
     * file. Unbounded, a single request under the body limit can name one document with a
     * half-megabyte identifier and a half-megabyte of text, and turn it into a hundred times
     * that in keys and retained records. A crawl cannot produce such an id; a caller can.</p>
     */
    public static final int MAX_DOCUMENT_ID_LENGTH = 512;

    private final String sourceId;
    private final Map<String, Document> documents;
    private final long totalBytes;

    private InlineSource(String sourceId, Map<String, Document> documents, long totalBytes) {
        this.sourceId = sourceId;
        this.documents = documents;
        this.totalBytes = totalBytes;
    }

    /**
     * A source over one batch of supplied documents.
     *
     * @param sourceId  What the documents are recorded as belonging to, so erasure and reindexing
     *                  can name them later
     * @param documents The documents, in the order they should be ingested
     * @return The source
     * @throws IllegalArgumentException if the id is absent or too long, the batch is empty, or
     *                                  two documents share an identifier
     */
    public static InlineSource of(String sourceId, List<Document> documents) {
        if (sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("Source id must be non-null and non-empty");
        }
        if (sourceId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Source id must be at most " + MAX_ID_LENGTH
                + " characters: " + sourceId.length());
        }
        Objects.requireNonNull(documents, "documents must not be null");
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }
        // Insertion-ordered, so the crawl lists documents in the order the caller sent them and
        // a partially completed batch is a prefix of what was asked for rather than an arbitrary
        // subset of it.
        Map<String, Document> byId = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Document document : documents) {
            Objects.requireNonNull(document, "documents must not contain null");
            // Two documents under one identifier is a caller that has lost track of what it is
            // sending. Keeping the last would index one of them and silently drop the other.
            if (byId.putIfAbsent(document.documentId(), document) != null) {
                throw new IllegalArgumentException(
                    "Duplicate document id in one batch: " + document.documentId());
            }
            totalBytes += document.byteCount();
        }
        // Unmodifiable rather than Map.copyOf: copyOf does not keep insertion order, and the
        // order documents are listed in is the order they are ingested in.
        return new InlineSource(sourceId, Collections.unmodifiableMap(byId), totalBytes);
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public String getSourceType() {
        return TYPE;
    }

    @Override
    public Stream<String> crawl() {
        return documents.keySet().stream();
    }

    @Override
    public FetchedDocument fetch(String documentId) throws DocumentNotFoundException {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("Document ID must be non-null and non-empty");
        }
        Document document = documents.get(documentId);
        if (document == null) {
            throw new DocumentNotFoundException(
                "No such document in this batch: " + documentId);
        }
        return new FetchedDocument(document.documentId(), document.content(),
            document.contentType(), StandardCharsets.UTF_8, document.lastModifiedMillis());
    }

    @Override
    public long getDocumentCount() {
        return documents.size();
    }

    @Override
    public long getEstimatedTotalSize() {
        return totalBytes;
    }

    @Override
    public String getDescription() {
        return TYPE + ":" + sourceId + " (" + documents.size() + " documents)";
    }

    /**
     * One document as it was supplied.
     *
     * @param documentId         What the document is named within its source
     * @param content            The document's bytes, UTF-8
     * @param contentType        The MIME type, which decides how the text is extracted
     * @param lastModifiedMillis When the document last changed, or 0 when the caller did not say
     */
    public record Document(String documentId, byte[] content, String contentType,
                           long lastModifiedMillis) {

        /**
         * @throws IllegalArgumentException if the identifier is absent or the content is null
         */
        public Document {
            if (documentId == null || documentId.isEmpty()) {
                throw new IllegalArgumentException("Document ID must be non-null and non-empty");
            }
            if (documentId.length() > MAX_DOCUMENT_ID_LENGTH) {
                throw new IllegalArgumentException("Document ID must be at most "
                    + MAX_DOCUMENT_ID_LENGTH + " characters: " + documentId.length());
            }
            Objects.requireNonNull(content, "content must not be null");
            content = content.clone();
        }

        /**
         * @return the document's bytes; a copy, so the stored content cannot be altered
         */
        @Override
        public byte[] content() {
            return content.clone();
        }

        /**
         * @return how many bytes the document holds, without copying them to find out
         */
        int byteCount() {
            return content.length;
        }
    }
}
