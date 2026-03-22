/**
 * Document registry for tracking indexed documents and detecting changes during re-crawl.
 *
 * <p>This package provides the core change detection functionality for Phase 8 ingestion:
 * <ul>
 *   <li>{@link excalibur.ingest.registry.DocumentMetadata} - Metadata for an indexed document</li>
 *   <li>{@link excalibur.ingest.registry.DocumentRegistry} - Registry tracking all indexed documents</li>
 *   <li>{@link excalibur.ingest.registry.CrawlDiff} - Result of comparing a crawl against the registry</li>
 * </ul>
 *
 * <h2>Change Detection</h2>
 * <p>The registry detects three types of changes during re-crawl:</p>
 * <ul>
 *   <li><strong>New documents:</strong> Documents that didn't exist in the previous crawl</li>
 *   <li><strong>Changed documents:</strong> Documents with different lastModified, content length, or hash</li>
 *   <li><strong>Deleted documents:</strong> Documents that existed before but are no longer present</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All classes in this package are thread-safe:</p>
 * <ul>
 *   <li>{@code DocumentMetadata} - Immutable</li>
 *   <li>{@code CrawlDiff} - Immutable</li>
 *   <li>{@code DocumentRegistry} - Read-write locked</li>
 * </ul>
 *
 * @see excalibur.ingest.registry.DocumentRegistry
 * @see excalibur.ingest.registry.CrawlDiff
 */
package excalibur.ingest.registry;
