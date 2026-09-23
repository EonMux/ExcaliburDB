package com.eonmux.excalibur.ingest.source;

/**
 * The largest single document this node indexes, in bytes and in chunks.
 *
 * <h2>Why one place</h2>
 * <p>The bound is enforced at three points - what a source reads, what a first crawl accepts, and
 * what a re-crawl accepts - and they have to agree. A source allowed to read past what ingestion
 * accepts reads a file whole into memory only to have it discarded and reported as a failure; a
 * re-crawl that accepts what the first crawl refused indexes, as "new", a document the first pass
 * said it could not take. Each point reads its bound from here, so they cannot drift apart.</p>
 *
 * <h2>Why these values</h2>
 * <p>A document is held several times over while it is processed - the fetched bytes, the
 * decoded text, one substring per chunk - and it is published only once every chunk has been
 * embedded and written, so a single document's size is a single request's worth of heap and a
 * single document's chunk count is the work it can schedule before anything is visible. The byte
 * bound keeps the first within reach of an ordinary heap; the chunk bound keeps the second from
 * turning one document into tens of thousands of embedding calls. At the default chunk size
 * (512 tokens, 64 overlapping) the chunk bound is the one a text document meets first, at about
 * 18 MB.</p>
 */
public final class DocumentLimits {

    /** The largest document, in bytes, that is read and indexed: 100 MiB. */
    public static final int MAX_DOCUMENT_BYTES = 100 * 1024 * 1024;

    /** The most chunks one document may produce and still be indexed. */
    public static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

    /**
     * Checks a source's read bound against what ingestion indexes.
     *
     * <p>Refused rather than lowered: a caller who asked for more than can be indexed is told so
     * when the source is built, not by a crawl whose large documents fail one at a time.</p>
     *
     * @param bytes   The bound a source was configured with
     * @param setting The setting's name, for the message
     * @return {@code bytes}, unchanged
     * @throws IllegalArgumentException if {@code bytes} exceeds {@link #MAX_DOCUMENT_BYTES}
     */
    static long requireIndexable(long bytes, String setting) {
        if (bytes > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(setting + " " + bytes
                + " exceeds the largest document that can be indexed, " + MAX_DOCUMENT_BYTES
                + " bytes");
        }
        return bytes;
    }

    private DocumentLimits() {
        throw new AssertionError("Constants only");
    }
}
