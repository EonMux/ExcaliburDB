package com.eonmux.excalibur.config;

import java.util.Map;
import java.util.Objects;

/**
 * Ingestion pipeline configuration.
 *
 * <p>This class is IMMUTABLE and thread-safe.</p>
 */
public final class IngestConfig {

    // HNSW integration queue depth limit

    // Document atomicity timeout in milliseconds
    public static final int DEFAULT_DOCUMENT_TIMEOUT_MS = 2000;

    // Default for document atomicity enabled
    public static final boolean DEFAULT_DOCUMENT_ATOMICITY = true;

    // Default reindex interval in milliseconds (1 hour)
    public static final long DEFAULT_REINDEX_INTERVAL_MS = 3600000L;

    /**
     * The floor a node puts under the gap between one crawl's requests to its host.
     *
     * <p>200ms, or five requests a second. A crawl is a sequential walk that issues two requests
     * per page - one to read its links, one to fetch it - and nothing else paced it, so a host
     * behind a fast link was asked for pages as quickly as it would answer, up to the ten
     * million a registration may ask for. Five a second is slower than that and faster than any
     * page this is worth crawling can be written.</p>
     */
    public static final long DEFAULT_CRAWL_DELAY_MS = 200L;

    /** The longest gap a deployment may put between requests: one minute. */
    public static final long MAX_CRAWL_DELAY_MS = 60000L;

    // Minimum and maximum values for validation (used by ConfigValidation)
    public static final int MIN_DOCUMENT_TIMEOUT_MS = 1000;
    public static final int MAX_DOCUMENT_TIMEOUT_MS = 30000;

    // Reindex interval bounds. These mirror the window the shard module's
    // SegmentReindexScheduler enforces (1 minute to 24 hours); validating them
    // here turns a misconfiguration into a startup failure instead of a
    // half-completed source registration inside the /v1/ingest request path.
    public static final long MIN_REINDEX_INTERVAL_MS = 60000L;
    public static final long MAX_REINDEX_INTERVAL_MS = 86400000L;

    /**
     * Which engine indexes chunk text.
     *
     * <p>"native" is the engine's own inverted index, which stores postings and reads a
     * chunk's text back from storage when it needs it. "lucene" keeps a second copy of every
     * chunk's text in a Lucene index. They are alternatives, not layers: running both would
     * write the corpus twice.</p>
     */
    public static final String LEXICAL_ENGINE_NATIVE = "native";

    /** The Lucene-backed chunk index. */
    public static final String LEXICAL_ENGINE_LUCENE = "lucene";

    /** The default engine. */
    public static final String DEFAULT_LEXICAL_ENGINE = LEXICAL_ENGINE_NATIVE;

    /** Tombstoned fraction of the chunk index at which a compaction repays its rewrite. */
    public static final double DEFAULT_LEXICAL_TOMBSTONE_RATIO = 0.2;

    /** How long an erased chunk may remain on disk before cost stops being a consideration. */
    public static final long DEFAULT_LEXICAL_MAX_LATENCY_SECONDS = 3600L;

    /** Chunk index segments tolerated before the smallest are merged. */
    public static final int DEFAULT_LEXICAL_MAX_SEGMENTS = 16;

    /** Tokens one chunk aims for. */
    public static final int DEFAULT_CHUNK_TARGET_TOKENS = 512;

    /** Tokens a chunk repeats from the one before it. */
    public static final int DEFAULT_CHUNK_OVERLAP_TOKENS = 64;

    /** BM25's term-frequency saturation point. */
    public static final double DEFAULT_BM25_K1 = 1.2;

    /** How much BM25 discounts a match for the length of the chunk it was found in. */
    public static final double DEFAULT_BM25_B = 0.75;

    // All fields final for immutability
    private final int documentTimeoutMs;
    private final boolean documentAtomicity;
    private final long reindexIntervalMs;
    private final long crawlDelayMs;
    private final String lexicalEngine;
    private final double lexicalTombstoneRatio;
    private final long lexicalMaxLatencySeconds;
    private final int lexicalMaxSegments;
    private final int chunkTargetTokens;
    private final int chunkOverlapTokens;
    private final double bm25K1;
    private final double bm25B;

    /**
     * Creates an Ingest config from properties.
     *
     * @param props The properties map
     */
    public IngestConfig(Map<String, String> props) {
        this.documentTimeoutMs = PropertyParser.parseInt(props, "excalibur.ingest.document-atomicity-timeout-ms", DEFAULT_DOCUMENT_TIMEOUT_MS);
        this.documentAtomicity = PropertyParser.parseBoolean(props, "excalibur.ingest.document-atomicity", DEFAULT_DOCUMENT_ATOMICITY);
        this.reindexIntervalMs = PropertyParser.parseLong(props, "excalibur.ingest.reindex-interval-ms", DEFAULT_REINDEX_INTERVAL_MS);
        this.crawlDelayMs = PropertyParser.parseLong(props,
            "excalibur.ingest.crawl-delay-ms", DEFAULT_CRAWL_DELAY_MS);
        // Refused at startup rather than clamped. A value outside the range is an operator who
        // meant something, and a crawl silently paced at a rate they did not choose is the one
        // outcome that teaches them nothing.
        if (crawlDelayMs < 0 || crawlDelayMs > MAX_CRAWL_DELAY_MS) {
            throw new IllegalArgumentException("excalibur.ingest.crawl-delay-ms must be between "
                + "0 and " + MAX_CRAWL_DELAY_MS + ", got: " + crawlDelayMs);
        }
        this.lexicalEngine = PropertyParser.parseString(props,
            "excalibur.ingest.lexical-engine", DEFAULT_LEXICAL_ENGINE)
            .trim().toLowerCase(java.util.Locale.ROOT);
        if (!LEXICAL_ENGINE_NATIVE.equals(lexicalEngine)
                && !LEXICAL_ENGINE_LUCENE.equals(lexicalEngine)) {
            // Refused at startup rather than fallen back from. An unrecognised engine name is
            // an operator expecting one index and getting another, which they would only find
            // out by noticing their queries answered from a corpus they did not build.
            throw new IllegalArgumentException("excalibur.ingest.lexical-engine must be "
                + LEXICAL_ENGINE_NATIVE + " or " + LEXICAL_ENGINE_LUCENE
                + ", got: " + lexicalEngine);
        }
        this.lexicalTombstoneRatio = ConfigBounds.unitRatio(props,
            "excalibur.ingest.lexical-tombstone-ratio", DEFAULT_LEXICAL_TOMBSTONE_RATIO);
        this.lexicalMaxLatencySeconds = PropertyParser.parseLong(props,
            "excalibur.ingest.lexical-max-latency-seconds",
            DEFAULT_LEXICAL_MAX_LATENCY_SECONDS);
        if (lexicalMaxLatencySeconds <= 0) {
            throw new IllegalArgumentException("excalibur.ingest.lexical-max-latency-seconds "
                + "must be positive, got: " + lexicalMaxLatencySeconds);
        }
        this.lexicalMaxSegments = PropertyParser.parseInt(props,
            "excalibur.ingest.lexical-max-segments", DEFAULT_LEXICAL_MAX_SEGMENTS);
        if (lexicalMaxSegments < 1) {
            throw new IllegalArgumentException("excalibur.ingest.lexical-max-segments must be "
                + "at least 1, got: " + lexicalMaxSegments);
        }
        this.chunkTargetTokens = PropertyParser.parseInt(props,
            "excalibur.ingest.chunk-target-tokens", DEFAULT_CHUNK_TARGET_TOKENS);
        if (chunkTargetTokens < 1) {
            throw new IllegalArgumentException("excalibur.ingest.chunk-target-tokens must be "
                + "at least 1, got: " + chunkTargetTokens);
        }
        this.chunkOverlapTokens = PropertyParser.parseInt(props,
            "excalibur.ingest.chunk-overlap-tokens", DEFAULT_CHUNK_OVERLAP_TOKENS);
        if (chunkOverlapTokens < 0) {
            throw new IllegalArgumentException("excalibur.ingest.chunk-overlap-tokens must be "
                + "non-negative, got: " + chunkOverlapTokens);
        }
        // Refused at startup rather than at the first document. An overlap at or past the
        // target makes every chunk begin where the last one began, so chunking never
        // advances; the chunker itself rejects it, and finding that out mid-ingestion means
        // a crawl that registered cleanly and then failed document by document.
        if (chunkOverlapTokens >= chunkTargetTokens) {
            throw new IllegalArgumentException("excalibur.ingest.chunk-overlap-tokens must be "
                + "less than excalibur.ingest.chunk-target-tokens, got: " + chunkOverlapTokens
                + " >= " + chunkTargetTokens);
        }
        this.bm25K1 = PropertyParser.parseDouble(props,
            "excalibur.ingest.bm25-k1", DEFAULT_BM25_K1);
        if (bm25K1 < 0) {
            throw new IllegalArgumentException("excalibur.ingest.bm25-k1 must be "
                + "non-negative, got: " + bm25K1);
        }
        this.bm25B = PropertyParser.parseDouble(props,
            "excalibur.ingest.bm25-b", DEFAULT_BM25_B);
        if (bm25B < 0 || bm25B > 1) {
            throw new IllegalArgumentException("excalibur.ingest.bm25-b must be in [0, 1], "
                + "got: " + bm25B);
        }
    }

    /** Tokens one chunk aims for. */
    public int getChunkTargetTokens() {
        return chunkTargetTokens;
    }

    /** Tokens a chunk repeats from the one before it. */
    public int getChunkOverlapTokens() {
        return chunkOverlapTokens;
    }

    /**
     * BM25's term-frequency saturation point.
     *
     * <p>Higher values keep rewarding a term that appears again; 0 reduces term frequency
     * to a yes-or-no. It is recorded in the index at build time, because the per-block
     * score bounds that let a search skip blocks are computed from it: a scorer using one
     * value against bounds written with another can prune a chunk it should have kept.</p>
     *
     * @return the k1 parameter, non-negative
     */
    public double getBm25K1() {
        return bm25K1;
    }

    /**
     * How much BM25 discounts a match for the length of the chunk it was found in.
     *
     * <p>1 normalises fully by length, 0 not at all. Recorded in the index for the same
     * reason as {@link #getBm25K1()}.</p>
     *
     * @return the b parameter, in [0, 1]
     */
    public double getBm25B() {
        return bm25B;
    }

    /** Which engine indexes chunk text: {@code native} or {@code lucene}. */
    public String getLexicalEngine() {
        return lexicalEngine;
    }

    /** Tombstoned fraction at which a chunk-index compaction repays its rewrite. */
    public double getLexicalTombstoneRatio() {
        return lexicalTombstoneRatio;
    }

    /** How long an erased chunk may remain on disk. */
    public long getLexicalMaxLatencySeconds() {
        return lexicalMaxLatencySeconds;
    }

    /** Chunk index segments tolerated before merging. */
    public int getLexicalMaxSegments() {
        return lexicalMaxSegments;
    }

    /**
     * Gets the document atomicity timeout.
     */
    public int getDocumentTimeoutMs() {
        return documentTimeoutMs;
    }

    /**
     * Checks if document atomicity is enabled.
     *
     * <p>When enabled, a document's chunks are buffered and published
     * to the visibility epoch as a batch, ensuring queries never see
     * partially-ingested documents.</p>
     *
     * @return true if document atomicity is enabled
     */
    public boolean isDocumentAtomicityEnabled() {
        return documentAtomicity;
    }

    /**
     * Gets the reindex interval in milliseconds.
     *
     * <p>This is the default interval between automatic reindex operations
     * for sources registered via the /v1/ingest endpoint.</p>
     *
     * @return Reindex interval in milliseconds
     */
    public long getReindexIntervalMs() {
        return reindexIntervalMs;
    }

    /**
     * The shortest gap this node allows between a crawl's requests to its host.
     *
     * <p>A floor rather than a setting a registration replaces: a source may ask to be crawled
     * more slowly than this and never faster, because the rate a host is asked to bear is the
     * operator's decision and not the caller's.</p>
     *
     * @return Milliseconds; zero when a deployment has turned pacing off
     */
    public long getCrawlDelayMs() {
        return crawlDelayMs;
    }

    @Override
    public String toString() {
        return "Ingest{documentTimeoutMs=" + documentTimeoutMs +
               ", documentAtomicity=" + documentAtomicity +
               ", reindexIntervalMs=" + reindexIntervalMs +
               ", crawlDelayMs=" + crawlDelayMs + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IngestConfig other = (IngestConfig) o;
        return documentTimeoutMs == other.documentTimeoutMs
            && documentAtomicity == other.documentAtomicity
            && reindexIntervalMs == other.reindexIntervalMs
            && crawlDelayMs == other.crawlDelayMs
            && Double.compare(lexicalTombstoneRatio, other.lexicalTombstoneRatio) == 0
            && lexicalMaxLatencySeconds == other.lexicalMaxLatencySeconds
            && lexicalMaxSegments == other.lexicalMaxSegments
            && chunkTargetTokens == other.chunkTargetTokens
            && chunkOverlapTokens == other.chunkOverlapTokens
            && Double.compare(bm25K1, other.bm25K1) == 0
            && Double.compare(bm25B, other.bm25B) == 0
            && Objects.equals(lexicalEngine, other.lexicalEngine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentTimeoutMs, documentAtomicity, reindexIntervalMs, crawlDelayMs,
            lexicalEngine, lexicalTombstoneRatio, lexicalMaxLatencySeconds, lexicalMaxSegments,
            chunkTargetTokens, chunkOverlapTokens, bm25K1, bm25B);
    }
}
