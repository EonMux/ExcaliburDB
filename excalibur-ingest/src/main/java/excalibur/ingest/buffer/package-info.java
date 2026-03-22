/**
 * Buffer package for document-atomic chunk buffering in the ingestion pipeline.
 *
 * <p>This package provides the mechanism for buffering document chunks before
 * committing them atomically to the write log, ensuring document-atomic visibility.</p>
 *
 * <h2>Document-Atomic Visibility</h2>
 * <p>ExcaliburDB guarantees that queries never see partially-ingested documents.
 * This is achieved by:</p>
 * <ol>
 *   <li>Buffering all chunks for a document in memory</li>
 *   <li>Writing all chunks to the write log with contiguous offsets</li>
 *   <li>Publishing to the visibility epoch only after all chunks are written</li>
 * </ol>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link excalibur.ingest.buffer.DocumentChunkBuffer} - Buffers chunks and commits atomically</li>
 *   <li>{@link excalibur.ingest.buffer.AtomicDocumentBatch} - Result of atomic commit with offset range</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create buffer with write log
 * DocumentChunkBuffer buffer = new DocumentChunkBuffer(writeLog);
 *
 * // Buffer chunks for a document
 * for (TextChunk chunk : chunker.chunk(documentId, text)) {
 *     buffer.bufferChunk(chunk);
 * }
 *
 * // Commit atomically
 * AtomicDocumentBatch batch = buffer.commitDocument(documentId);
 *
 * // Sync and advance visibility epoch
 * writeLog.sync();
 * visibilityTracker.advanceEpoch();
 * }</pre>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunks for one document have contiguous write log offsets</li>
 *   <li>Start offset &lt; end offset for committed batches</li>
 *   <li>Chunk IDs are unique per document: documentId:chunkIndex</li>
 *   <li>All offsets are non-negative</li>
 *   <li>Document atomicity is preserved across crashes</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
package excalibur.ingest.buffer;
