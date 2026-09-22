package com.eonmux.excalibur.query.payload;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;

import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Attaches the stored chunk text to query results so relevancy scoring has something to
 * score.
 *
 * <h2>Why this exists</h2>
 * <p>A vector search result identifies a node, not a document body. Stage 5 compares the
 * query against candidate <em>text</em>, so between selection and scoring the surviving
 * results have to be resolved back to the values they were ingested from. Without this
 * step every candidate is blank and the LLM is asked to rank a list of empty strings.</p>
 *
 * <h2>Key shape</h2>
 * <p>Ingestion writes one storage entry per chunk under
 * {@code sourceId:documentId:chunk:index} ({@code ChunkIdentifiers.chunkId}), but the HNSW
 * integration worker registers only the owning document key {@code sourceId:documentId}
 * against the node. A graph result therefore identifies a document, not the chunk inside
 * it, so this resolver:</p>
 * <ol>
 *   <li>tries the result's document ID as a storage key directly - which is what it is
 *       for a write-log tail-scan hit or a single-key corpus; and</li>
 *   <li>failing that, reads the document's chunks in index order and concatenates their
 *       text, bounded by {@link #MAX_CHUNK_PROBES} chunks and
 *       {@link ChunkValueCodec#MAX_TEXT_BYTES} bytes.</li>
 * </ol>
 *
 * <h2>Read scope</h2>
 * <p>The result's own segment is read first: that is the segment whose write log the
 * vector was integrated from, so it is where the chunk lives even when ingestion wrote it
 * outside the engine's hash routing. The routed lookup is only a fallback.</p>
 *
 * <h2>Cost</h2>
 * <p>Each resolution is one or more point lookups, so this belongs after selection has
 * narrowed the candidate set - never over every result every segment returned.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 11.0
 */
public final class ResultPayloadResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultPayloadResolver.class);

    /** Marker introducing the chunk index in a storage key. */
    private static final String CHUNK_MARKER = ":chunk:";

    /**
     * Maximum number of chunks read for one document.
     *
     * <p>Chunk indexes are dense from zero, so probing stops at the first gap; this bound
     * only caps how much of a very long document is assembled.</p>
     */
    static final int MAX_CHUNK_PROBES = 32;

    /**
     * Maximum number of value bytes copied out of a stored entry before decoding.
     *
     * <p>The text is truncated anyway; this keeps a pathologically large value from being
     * copied onto the heap in full first.</p>
     */
    private static final long MAX_VALUE_BYTES = 1L << 20;

    /** Separator between the text of consecutive chunks of one document. */
    private static final byte[] CHUNK_SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);

    private final StorageEngine storageEngine;

    /**
     * Creates a resolver reading from the given storage engine.
     *
     * @param storageEngine The engine holding the ingested chunks (must not be null)
     * @throws NullPointerException if storageEngine is null
     */
    public ResultPayloadResolver(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    /**
     * Returns the results with their stored chunk text attached.
     *
     * <p>Results that already carry a payload - a tail-scan hit, whose value was in hand
     * when it was scanned - are left alone, as are results whose text cannot be found.
     * The input list is returned unchanged when nothing was resolved.</p>
     *
     * @param results The results to enrich (must not be null)
     * @return The results, with payloads attached where they could be resolved
     */
    public List<QueryResultItem> attachPayloads(List<QueryResultItem> results) {
        Objects.requireNonNull(results, "results cannot be null");
        if (results.isEmpty()) {
            return results;
        }

        List<QueryResultItem> resolved = new ArrayList<>(results.size());
        int attached = 0;

        for (QueryResultItem item : results) {
            byte[] text = item.hasPayload() ? null : resolveText(item);
            if (text == null || text.length == 0) {
                resolved.add(item);
                continue;
            }
            resolved.add(item.withPayload(text));
            attached++;
        }

        if (attached == 0) {
            return results;
        }

        LOGGER.debug("Attached stored text to {} of {} results", attached, results.size());
        return resolved;
    }

    /**
     * Resolves the stored text for one result, or null when it cannot be found.
     */
    private byte[] resolveText(QueryResultItem item) {
        String documentId = item.documentIdStr();
        if (documentId == null || documentId.isEmpty()) {
            return null;
        }

        byte[] value = readValue(item.segmentId(), documentId);
        if (value != null) {
            return ChunkValueCodec.truncateUtf8(ChunkValueCodec.textOf(value), ChunkValueCodec.MAX_TEXT_BYTES);
        }

        if (documentId.contains(CHUNK_MARKER)) {
            // Already a chunk key: there is no document key to expand it into.
            return null;
        }

        return readDocumentText(item.segmentId(), documentId);
    }

    /**
     * Assembles the text of a document's chunks, in index order.
     */
    private byte[] readDocumentText(long segmentId, String documentKey) {
        List<byte[]> chunks = new ArrayList<>();
        int totalBytes = 0;

        for (int chunkIndex = 0; chunkIndex < MAX_CHUNK_PROBES; chunkIndex++) {
            byte[] value = readValue(segmentId, documentKey + CHUNK_MARKER + chunkIndex);
            if (value == null) {
                // Chunk indexes are dense from zero, so the first gap ends the document.
                break;
            }

            byte[] text = ChunkValueCodec.textOf(value);
            if (text == null || text.length == 0) {
                continue;
            }

            chunks.add(text);
            totalBytes += text.length + CHUNK_SEPARATOR.length;
            if (totalBytes >= ChunkValueCodec.MAX_TEXT_BYTES) {
                break;
            }
        }

        return ChunkValueCodec.truncateUtf8(join(chunks), ChunkValueCodec.MAX_TEXT_BYTES);
    }

    /**
     * Concatenates chunk texts, separated by a newline.
     */
    private static byte[] join(List<byte[]> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        if (chunks.size() == 1) {
            return chunks.get(0);
        }

        int size = 0;
        for (byte[] chunk : chunks) {
            size += chunk.length + CHUNK_SEPARATOR.length;
        }

        byte[] joined = new byte[size - CHUNK_SEPARATOR.length];
        int offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                System.arraycopy(CHUNK_SEPARATOR, 0, joined, offset, CHUNK_SEPARATOR.length);
                offset += CHUNK_SEPARATOR.length;
            }
            byte[] chunk = chunks.get(i);
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        return joined;
    }

    /**
     * Reads a stored value, preferring the segment the result came from.
     */
    private byte[] readValue(long segmentId, String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        byte[] fromSegment = readFromSegment(segmentId, keyBytes);
        if (fromSegment != null) {
            return fromSegment;
        }
        return readFromEngine(keyBytes);
    }

    /**
     * Reads a key from the segment that produced the result, or null.
     */
    private byte[] readFromSegment(long segmentId, byte[] key) {
        if (segmentId < 0) {
            return null;
        }
        try {
            Segment segment = storageEngine.getSegment(segmentId);
            return segment == null ? null : copyOf(segment.get(key));
        } catch (RuntimeException e) {
            LOGGER.debug("Could not read payload from segment {}: {}", segmentId, e.toString());
            return null;
        }
    }

    /**
     * Reads a key through the engine's hash routing, or null.
     */
    private byte[] readFromEngine(byte[] key) {
        try {
            return copyOf(storageEngine.get(key));
        } catch (RuntimeException e) {
            // The engine may not be started (or may be shutting down); payload enrichment
            // is best effort and must never fail the query it is enriching.
            LOGGER.debug("Could not read payload from storage: {}", e.toString());
            return null;
        }
    }

    /**
     * Copies a stored value slice onto the heap, bounded by {@link #MAX_VALUE_BYTES}.
     */
    private static byte[] copyOf(MemorySegment slice) {
        if (slice == null) {
            return null;
        }
        long size = Math.min(slice.byteSize(), MAX_VALUE_BYTES);
        if (size <= 0) {
            return null;
        }
        return slice.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE);
    }
}
