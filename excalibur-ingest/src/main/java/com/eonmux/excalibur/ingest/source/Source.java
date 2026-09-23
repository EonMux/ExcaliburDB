package com.eonmux.excalibur.ingest.source;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Interface for document sources that can be crawled and fetched.
 *
 * <p>A Source represents an external data repository that can be indexed
 * by ExcaliburDB. Implementations provide crawling capability to discover
 * document IDs, change detection for incremental updates, and content
 * retrieval for individual documents.</p>
 *
 * <h2>Implementations</h2>
 * <p>Common source types include:</p>
 * <ul>
 *   <li>{@code FilesystemSource} - Local directory with documents</li>
 *   <li>{@code HttpSource} - Web crawl via HTTP(S)</li>
 *   <li>Database sources - JDBC-connected repositories</li>
 *   <li>Cloud storage - S3, GCS, Azure Blob</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple threads may call
 * {@link #crawl()}, {@link #fetch(String)} and {@link #fetchIfChanged} concurrently. The
 * returned {@link Stream} from {@link #crawl()} should be consumed by a single thread but may
 * be processed in parallel.</p>
 *
 * <h2>Resource Management</h2>
 * <p>Sources that hold resources (connections, file handles) should implement
 * {@link Closeable} and release resources in {@link #close()}. Sources that
 * do not hold resources may implement {@link #close()} as a no-op.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>A source's typical lifecycle:</p>
 * <ol>
 *   <li>Source is instantiated with configuration</li>
 *   <li>{@link #crawl()} is called to discover document IDs</li>
 *   <li>{@link #fetch(String)} is called for each document ID</li>
 *   <li>On subsequent runs, {@link #fetchIfChanged} is called for each document already
 *       indexed, so an unchanged one need not be transferred again</li>
 *   <li>{@link #close()} is called when the source is no longer needed</li>
 * </ol>
 *
 * @see FetchedDocument
 */
public interface Source extends Closeable {

    /**
     * Longest source id an implementation accepts, in characters.
     *
     * <p>An id is not free text and not a path. It is copied into every document's registry
     * record, written into the source catalog, and printed with every log line about the source,
     * so its length is multiplied by the size of the corpus rather than by the number of times it
     * is sent. This leaves room for a descriptive name - a team, a repository, a dated archive -
     * and none for a payload.</p>
     */
    int MAX_ID_LENGTH = 256;

    /**
     * Returns a unique identifier for this source.
     *
     * <p>The source ID is used to track which documents came from which source,
     * enabling source-specific re-indexing and deletion cascading.</p>
     *
     * <p>Domain constraints:</p>
     * <ul>
     *   <li>Must be non-null and non-empty</li>
     *   <li>Should be stable across restarts (not randomly generated)</li>
     *   <li>Should be URL-safe (alphanumeric, dash, underscore)</li>
     *   <li>Maximum length: {@value #MAX_ID_LENGTH} characters</li>
     * </ul>
     *
     * @return Unique source identifier (e.g., "filesystem-docs", "https-api-example-com")
     */
    String getSourceId();

    /**
     * Returns the type identifier for this source.
     *
     * <p>The type identifies the source implementation class, used for
     * serialization/deserialization and configuration validation.</p>
     *
     * <p>Standard types:</p>
     * <ul>
     *   <li>{@code filesystem} - Local filesystem directory</li>
     *   <li>{@code http} - HTTP(S) web source</li>
     *   <li>{@code s3} - Amazon S3 bucket</li>
     *   <li>{@code gcs} - Google Cloud Storage bucket</li>
     * </ul>
     *
     * @return Source type identifier
     */
    String getSourceType();

    /**
     * Discovers and returns a stream of document IDs available in this source.
     *
     * <p>The returned stream is lazily evaluated and should be consumed
     * promptly. Large sources may stream IDs incrementally rather than
     * loading all IDs into memory.</p>
     *
     * <p>Domain constraints:</p>
     * <ul>
     *   <li>Document IDs must be unique within this source</li>
     *   <li>Document IDs should be stable across crawls (same document = same ID)</li>
     *   <li>Document IDs should be URL-safe</li>
     *   <li>The stream should be ordered consistently (typically lexicographic or by discovery order)</li>
     * </ul>
     *
     * <p><strong>Resource management:</strong> The returned stream may hold
     * resources (file handles, connections). Callers should close the stream
     * when done, typically via try-with-resources.</p>
     *
     * @return Stream of document IDs (non-null, each ID non-null and non-empty)
     * @throws SourceException if crawling fails (network error, permission denied, etc.)
     */
    Stream<String> crawl() throws SourceException;

    /**
     * Fetches the content and metadata for a specific document.
     *
     * <p>Given a document ID previously returned by {@link #crawl()}, this method
     * retrieves the document's raw bytes and content type.</p>
     *
     * <p>Domain constraints:</p>
     * <ul>
     *   <li>The documentId must have been returned by a previous {@link #crawl()} call</li>
     *   <li>Content type should be a valid MIME type</li>
     *   <li>Content may be empty for zero-length documents</li>
     *   <li>Content should be raw bytes, not pre-decoded text</li>
     * </ul>
     *
     * @param documentId The document ID to fetch (non-null, non-empty)
     * @return FetchedDocument containing content, type, and metadata
     * @throws DocumentNotFoundException if the document does not exist
     * @throws SourceException if fetching fails (I/O error, permission denied, etc.)
     * @throws IllegalArgumentException if documentId is null or empty
     */
    FetchedDocument fetch(String documentId) throws SourceException, DocumentNotFoundException;

    /**
     * Fetches a document unless the source can confirm it has not changed since the version the
     * validators describe.
     *
     * <p>This is how a re-crawl avoids transferring what it already holds. The validators are
     * what the source reported for the version that was indexed - an HTTP {@code ETag} and
     * {@code Last-Modified} - and a source that can put them to its origin asks it
     * conditionally: an HTTP source sends {@code If-None-Match} and {@code If-Modified-Since},
     * and a {@code 304 Not Modified} answer means the indexed version is still current without
     * the body ever being sent.</p>
     *
     * <p>Empty is a positive statement that the document is unchanged, never a guess. A source
     * that cannot ask - a directory, whose modification times survive a copy and are too coarse
     * to trust, or a set of supplied documents - fetches in full and leaves the comparison to
     * the caller, which is what this default does. So does any source handed validators that
     * carry nothing to ask with.</p>
     *
     * @param documentId The document ID to fetch (non-null, non-empty)
     * @param previous   What the source reported for the version already held
     * @return The document, or empty when the source confirmed it is unchanged
     * @throws DocumentNotFoundException if the document does not exist
     * @throws SourceException if fetching fails (I/O error, permission denied, etc.)
     * @throws IllegalArgumentException if documentId is null or empty
     * @throws NullPointerException if previous is null
     */
    default Optional<FetchedDocument> fetchIfChanged(String documentId, DocumentValidators previous)
            throws SourceException, DocumentNotFoundException {
        Objects.requireNonNull(previous, "previous must not be null");
        return Optional.of(fetch(documentId));
    }

    /**
     * Returns the total number of documents in this source, if known.
     *
     * <p>Some sources can efficiently determine document count without
     * crawling (e.g., database COUNT query). For sources where counting
     * is expensive, this method returns -1.</p>
     *
     * @return Document count if known, or -1 if unknown/expensive to compute
     * @throws SourceException if counting fails
     */
    default long getDocumentCount() throws SourceException {
        return -1;
    }

    /**
     * Returns the estimated total content size in bytes, if known.
     *
     * <p>Used for progress tracking and resource estimation during ingestion.
     * For sources where size is expensive to compute, this method returns -1.</p>
     *
     * @return Total content size in bytes if known, or -1 if unknown
     * @throws SourceException if size estimation fails
     */
    default long getEstimatedTotalSize() throws SourceException {
        return -1;
    }

    /**
     * Releases any resources held by this source.
     *
     * <p>After calling this method, the source should not be used. Implementations
     * should be idempotent (safe to call multiple times).</p>
     *
     * <p>Sources that do not hold resources may implement this as a no-op.</p>
     *
     * @throws IOException if closing fails
     */
    @Override
    default void close() throws IOException {
    }

    /**
     * Returns a human-readable description of this source.
     *
     * <p>Used for logging and debugging. Should include enough information
     * to identify the source (e.g., path for filesystem, URL for HTTP).</p>
     *
     * @return Source description (non-null)
     */
    default String getDescription() {
        return getSourceType() + ":" + getSourceId();
    }
}
