/**
 * Document-atomic visibility tracking for the ingestion pipeline.
 *
 * <p>This package provides the mechanism to ensure queries never see
 * partially-ingested documents. When a document's chunks are published
 * to the visibility epoch, the {@link com.eonmux.excalibur.ingest.visibility.DocumentVisibilityRegistry}
 * records the epoch at which the entire document becomes visible.</p>
 *
 * <h2>Document Atomicity Contract</h2>
 * <p>A document's chunks become visible to queries atomically:</p>
 * <ol>
 *   <li>All chunks are written to the write log with contiguous offsets</li>
 *   <li>All chunks are inserted into the Cuckoo filter hierarchy</li>
 *   <li>Write log is synced (fsync)</li>
 *   <li>Visibility epoch is advanced</li>
 *   <li>Document is registered in the visibility registry at the new epoch</li>
 * </ol>
 *
 * <h2>Query Integration</h2>
 * <p>Queries use the visibility registry to filter out partial documents:</p>
 * <pre>{@code
 * // In query pipeline:
 * long queryEpoch = visibilityTracker.captureEpoch();
 * 
 * // For each chunk found in write log or HNSW:
 * String documentId = visibilityRegistry.getDocumentForChunk(chunkId);
 * if (visibilityRegistry.isVisibleAt(documentId, queryEpoch)) {
 *     // Include all chunks of this document
 * } else {
 *     // Exclude all chunks - document not fully visible yet
 * }
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
package com.eonmux.excalibur.ingest.visibility;
