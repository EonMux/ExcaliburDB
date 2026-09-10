package excalibur.integration;

import excalibur.ingest.pipeline.DocumentBatchBuffer;
import excalibur.ingest.processor.TextChunk;
import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.store.WriteLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes one document's chunks to the write log and records where each of them landed.
 *
 * <p>Ingestion stores a chunk as a {@link ChunkPayloadCodec} value keyed by chunk id and hands
 * the resulting write-log offsets to a {@link DocumentBatchBuffer}, which is what lets the
 * document be published as a single unit. These tests need that shape without standing up a
 * whole orchestrator, and a document written any other way would not be readable by the tail
 * scanner or the HNSW integration worker they query through.</p>
 *
 * <p>A document's appends are serialised here, so its chunks occupy a contiguous run of the log
 * even when several threads ingest at once. That is what makes "two documents never interleave
 * in the log" an assertion about the writer rather than about thread timing.</p>
 */
final class AtomicDocumentWriter {

    /** The ingestion source recorded on every batch these tests write. */
    static final String SOURCE_ID = "test-source";

    private final WriteLog writeLog;
    private final Object appendLock = new Object();

    AtomicDocumentWriter(WriteLog writeLog) {
        this.writeLog = Objects.requireNonNull(writeLog, "writeLog must not be null");
    }

    /**
     * Writes a document whose chunks carry text and no embedding.
     *
     * @param documentKey The key the chunk ids are derived from
     * @param chunks The document's chunks, in order
     * @return A sealed batch carrying every chunk id and the offset it was written at
     * @throws IOException if an append fails
     */
    DocumentBatchBuffer write(String documentKey, List<TextChunk> chunks) throws IOException {
        return write(documentKey, chunks, Map.of());
    }

    /**
     * Writes a document, embedding those chunks that have an embedding.
     *
     * @param documentKey The key the chunk ids are derived from
     * @param chunks The document's chunks, in order
     * @param embeddings Chunk index to embedding; a chunk absent from the map is stored as text
     * @return A sealed batch carrying every chunk id and the offset it was written at
     * @throws IllegalArgumentException if the document has no chunks
     * @throws IOException if an append fails
     */
    DocumentBatchBuffer write(String documentKey, List<TextChunk> chunks,
                              Map<Integer, float[]> embeddings) throws IOException {
        Objects.requireNonNull(documentKey, "documentKey must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        Objects.requireNonNull(embeddings, "embeddings must not be null");
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("A document needs at least one chunk: " + documentKey);
        }

        // Everything is encoded before the first append. An encoding failure discovered halfway
        // through would leave the document half-written, with nothing recorded to roll back.
        List<String> chunkIds = new ArrayList<>(chunks.size());
        List<byte[]> values = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            chunkIds.add(ChunkIdentifiers.chunkIdForDocumentKey(documentKey, chunk.getChunkIndex()));
            float[] embedding = embeddings.get(chunk.getChunkIndex());
            values.add(embedding == null
                ? ChunkPayloadCodec.encodeTextOnly(chunk.getText())
                : ChunkPayloadCodec.encode(chunk.getText(), embedding));
        }

        DocumentBatchBuffer batch = new DocumentBatchBuffer(documentKey, SOURCE_ID);
        synchronized (appendLock) {
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk chunk = chunks.get(i);
                long offset = writeLog.writeOffset();
                writeLog.appendPut(chunkIds.get(i).getBytes(StandardCharsets.UTF_8), values.get(i));
                batch.addChunk(chunkIds.get(i), chunk, offset, embeddings.get(chunk.getChunkIndex()));
            }
        }
        batch.seal();
        return batch;
    }
}
