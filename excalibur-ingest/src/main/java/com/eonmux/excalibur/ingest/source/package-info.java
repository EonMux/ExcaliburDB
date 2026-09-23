/**
 * Source interfaces and supporting classes for document ingestion.
 *
 * <p>This package provides the core abstraction for data sources that can be
 * crawled and indexed by ExcaliburDB. The {@link Source} interface defines
 * the contract for discovering and fetching documents from external systems.</p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link Source} - Main interface for document sources</li>
 *   <li>{@link FetchedDocument} - Represents a fetched document with content and metadata</li>
 *   <li>{@link DocumentValidators} - What a source reported about a version, which a re-crawl
 *       asks {@link Source#fetchIfChanged} about</li>
 *   <li>{@link TextEncoding} - Settles the encoding of a document's bytes, the same way for
 *       every source</li>
 *   <li>{@link SourceException} - Base exception for source operations</li>
 *   <li>{@link DocumentNotFoundException} - Exception for missing documents</li>
 * </ul>
 *
 * <h2>Source Types</h2>
 * <ul>
 *   <li>{@link FilesystemSource} - Local directory documents</li>
 *   <li>{@link HttpSource} - HTTP(S) web crawl</li>
 *   <li>{@link InlineSource} - Documents supplied in a request rather than crawled</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Source source = new FilesystemSource("docs", Path.of("/data/documents"));
 * try (Stream<String> ids = source.crawl()) {
 *     ids.forEach(id -> {
 *         try {
 *             FetchedDocument doc = source.fetch(id);
 *             processDocument(doc);
 *         } catch (DocumentNotFoundException e) {
 *             // Document was deleted, tombstone in index
 *         } catch (SourceException e) {
 *             log.error("Failed to fetch: {}", id, e);
 *         }
 *     });
 * }
 * }</pre>
 *
 * @see Source
 * @see FetchedDocument
 */
package com.eonmux.excalibur.ingest.source;
