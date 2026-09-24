/**
 * Document-atomic visibility tracking for the ingestion pipeline.
 *
 * <p>This package provides the mechanism that keeps queries from seeing partially-ingested
 * documents. The {@link com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker}
 * is it: one per node, built on the node's visibility clock, and consulted by the query
 * pipeline's atomicity filter through a
 * {@link com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker}
 * ({@link com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter}).</p>
 *
 * <h2>Document Atomicity Contract</h2>
 * <p>A document's chunks become visible to queries atomically, whether ingestion or a reindex
 * wrote them:</p>
 * <ol>
 *   <li>The document is marked in flight ({@code beginIngestion}); the mark is journalled by
 *       the {@link com.eonmux.excalibur.ingest.visibility.InFlightDocumentJournal} before the
 *       first chunk is written, so a restart keeps a half-written document hidden</li>
 *   <li>Every chunk is written to storage, each one durable in its segment's write-ahead log
 *       before the write returns</li>
 *   <li>The node visibility epoch is advanced</li>
 *   <li>The document is registered with its complete chunk set at the new epoch, which clears
 *       the in-flight mark</li>
 * </ol>
 *
 * <h2>Query Integration</h2>
 * <p>The atomicity filter derives a document key from each result and asks the checker:</p>
 * <ul>
 *   <li>a document registered at an epoch no later than the query's is returned whole;</li>
 *   <li>a document registered after the query's epoch, or still in flight, is withheld whole -
 *       including one published earlier whose new version is being written over it;</li>
 *   <li>a document the tracker does not know - written by a previous process, or forgotten to
 *       retention once long published - passes through, because a document that is whole is
 *       the only kind that can be unknown.</li>
 * </ul>
 * <pre>{@code
 * long queryEpoch = nodeVisibility.captureEpoch();
 * List<QueryResultItem> whole = atomicityFilter.filterPartialDocuments(results, queryEpoch);
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
package com.eonmux.excalibur.ingest.visibility;
