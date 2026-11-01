package com.eonmux.excalibur.config;

import java.util.List;
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

    /** Longest a published document waits, while its run goes on, to become lexically searchable. */
    public static final long DEFAULT_LEXICAL_REFRESH_INTERVAL_MS = 30_000L;

    /**
     * Shortest refresh interval accepted.
     *
     * <p>Every refresh on the native engine publishes a segment, and on Lucene reopens a reader.
     * Below a second that is a segment per handful of documents, which the merge policy then has
     * to spend the run rewriting.</p>
     */
    public static final long MIN_LEXICAL_REFRESH_INTERVAL_MS = 1_000L;

    /** Tokens one chunk aims for. */
    public static final int DEFAULT_CHUNK_TARGET_TOKENS = 512;

    /** Tokens a chunk repeats from the one before it. */
    public static final int DEFAULT_CHUNK_OVERLAP_TOKENS = 64;

    /** BM25's term-frequency saturation point. */
    public static final double DEFAULT_BM25_K1 = 1.2;

    /** How much BM25 discounts a match for the length of the chunk it was found in. */
    public static final double DEFAULT_BM25_B = 0.75;

    /** Reads a PDF's text layer as it stands, in process; cannot read a scanned page. */
    public static final String PDF_READER_TEXT_LAYER = "text-layer";

    /**
     * Reads PDFs with Indra OCR, in process: text layers where they can be trusted, OCR
     * elsewhere, in reading order, as markdown. Its models are downloaded on first use and are
     * published under their own licence, which must be accepted first.
     */
    public static final String PDF_READER_INDRA = "indra";

    /** Hands every PDF to the external program {@code excalibur.ingest.pdf-converter-command}. */
    public static final String PDF_READER_COMMAND = "command";

    /**
     * The external program PDFs are converted to text with; empty for none.
     *
     * <p>Empty by default, because a node ships with no such program.</p>
     */
    public static final String DEFAULT_PDF_CONVERTER_COMMAND = "";

    /** Arguments placed between the converter and the PDF's path; none by default. */
    public static final String DEFAULT_PDF_CONVERTER_ARGS = "";

    /** The longest one PDF may take to convert before its document fails: an hour. */
    public static final int DEFAULT_PDF_CONVERTER_TIMEOUT_SECONDS = 3600;

    /** The most a deployment may give one PDF: a day. */
    public static final int MAX_PDF_CONVERTER_TIMEOUT_SECONDS = 86_400;

    /**
     * How many PDFs a node converts at once.
     *
     * <p>One, because a converter that OCRs runs its pages in parallel on every core it may use;
     * two of them at once compete for the same cores and finish no sooner.</p>
     */
    public static final int DEFAULT_PDF_CONVERTER_PARALLELISM = 1;

    /** The most conversions a node may run at once. */
    public static final int MAX_PDF_CONVERTER_PARALLELISM = 64;

    /** Indra reads a text layer where it passes its checks, and OCRs every other page. */
    public static final String INDRA_MODE_AUTO = "auto";

    /** Indra OCRs every page, never reading a text layer. */
    public static final String INDRA_MODE_OCR = "ocr";

    /** Indra reads every text layer, even one that fails its checks; OCRs pages with none. */
    public static final String INDRA_MODE_TEXT = "text";

    /** The resolution pages are rendered at for OCR, Indra's own default. */
    public static final int DEFAULT_INDRA_DPI = 200;

    /** The lowest rendering resolution: one pixel per point. */
    public static final int MIN_INDRA_DPI = 72;

    /** The highest rendering resolution; past it a page's pixels outgrow what OCR gains. */
    public static final int MAX_INDRA_DPI = 600;

    /** The most threads one conversion may be given. */
    public static final int MAX_INDRA_THREADS = 256;

    // All fields final for immutability
    private final int documentTimeoutMs;
    private final boolean documentAtomicity;
    private final long reindexIntervalMs;
    private final long crawlDelayMs;
    private final String lexicalEngine;
    private final double lexicalTombstoneRatio;
    private final long lexicalMaxLatencySeconds;
    private final int lexicalMaxSegments;
    private final long lexicalRefreshIntervalMs;
    private final int chunkTargetTokens;
    private final int chunkOverlapTokens;
    private final double bm25K1;
    private final double bm25B;
    private final String pdfConverterCommand;
    private final List<String> pdfConverterArgs;
    private final int pdfConverterTimeoutSeconds;
    private final int pdfConverterParallelism;
    /** The reader as configured, empty when it is left to follow the converter command. */
    private final String pdfReader;
    private final String indraModelDir;
    private final boolean indraModelDownload;
    private final boolean indraAcceptModelLicense;
    private final String indraMode;
    private final int indraDpi;
    private final int indraThreads;
    private final boolean indraMath;
    private final boolean indraKeepFurniture;
    private final boolean indraImageOcr;

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
        this.lexicalRefreshIntervalMs = PropertyParser.parseLong(props,
            "excalibur.ingest.lexical-refresh-interval-ms", DEFAULT_LEXICAL_REFRESH_INTERVAL_MS);
        if (lexicalRefreshIntervalMs < MIN_LEXICAL_REFRESH_INTERVAL_MS) {
            throw new IllegalArgumentException("excalibur.ingest.lexical-refresh-interval-ms must "
                + "be at least " + MIN_LEXICAL_REFRESH_INTERVAL_MS + ", got: "
                + lexicalRefreshIntervalMs);
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
        this.pdfConverterCommand = PropertyParser.parseString(props,
            "excalibur.ingest.pdf-converter-command", DEFAULT_PDF_CONVERTER_COMMAND).strip();
        String args = PropertyParser.parseString(props,
            "excalibur.ingest.pdf-converter-args", DEFAULT_PDF_CONVERTER_ARGS).strip();
        this.pdfConverterArgs = args.isEmpty() ? List.of() : List.of(args.split("\\s+"));
        // Validated whether or not a converter is named, so a file that is wrong is refused when
        // it is written rather than on the day someone sets the command.
        this.pdfConverterTimeoutSeconds = PropertyParser.parseInt(props,
            "excalibur.ingest.pdf-converter-timeout-seconds",
            DEFAULT_PDF_CONVERTER_TIMEOUT_SECONDS);
        if (pdfConverterTimeoutSeconds < 1
                || pdfConverterTimeoutSeconds > MAX_PDF_CONVERTER_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-converter-timeout-seconds "
                + "must be between 1 and " + MAX_PDF_CONVERTER_TIMEOUT_SECONDS + ", got: "
                + pdfConverterTimeoutSeconds);
        }
        this.pdfConverterParallelism = PropertyParser.parseInt(props,
            "excalibur.ingest.pdf-converter-parallelism", DEFAULT_PDF_CONVERTER_PARALLELISM);
        if (pdfConverterParallelism < 1
                || pdfConverterParallelism > MAX_PDF_CONVERTER_PARALLELISM) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-converter-parallelism must "
                + "be between 1 and " + MAX_PDF_CONVERTER_PARALLELISM + ", got: "
                + pdfConverterParallelism);
        }
        this.pdfReader = parsePdfReader(props, pdfConverterCommand);
        this.indraModelDir = PropertyParser.parseString(props,
            "excalibur.ingest.indra-model-dir", "").strip();
        this.indraModelDownload = PropertyParser.parseBoolean(props,
            "excalibur.ingest.indra-model-download", true);
        this.indraAcceptModelLicense = PropertyParser.parseBoolean(props,
            "excalibur.ingest.indra-accept-model-license", false);
        this.indraMode = PropertyParser.parseString(props, "excalibur.ingest.indra-mode",
            INDRA_MODE_AUTO).toLowerCase(java.util.Locale.ROOT);
        if (!List.of(INDRA_MODE_AUTO, INDRA_MODE_OCR, INDRA_MODE_TEXT).contains(indraMode)) {
            throw new IllegalArgumentException("excalibur.ingest.indra-mode must be "
                + INDRA_MODE_AUTO + ", " + INDRA_MODE_OCR + " or " + INDRA_MODE_TEXT + ", got: "
                + indraMode);
        }
        this.indraDpi = PropertyParser.parseInt(props, "excalibur.ingest.indra-dpi",
            DEFAULT_INDRA_DPI);
        if (indraDpi < MIN_INDRA_DPI || indraDpi > MAX_INDRA_DPI) {
            throw new IllegalArgumentException("excalibur.ingest.indra-dpi must be between "
                + MIN_INDRA_DPI + " and " + MAX_INDRA_DPI + ", got: " + indraDpi);
        }
        this.indraThreads = PropertyParser.parseInt(props, "excalibur.ingest.indra-threads", 0);
        if (indraThreads < 0 || indraThreads > MAX_INDRA_THREADS) {
            throw new IllegalArgumentException("excalibur.ingest.indra-threads must be between "
                + "0 (every core) and " + MAX_INDRA_THREADS + ", got: " + indraThreads);
        }
        this.indraMath = PropertyParser.parseBoolean(props, "excalibur.ingest.indra-math",
            false);
        this.indraKeepFurniture = PropertyParser.parseBoolean(props,
            "excalibur.ingest.indra-keep-furniture", false);
        this.indraImageOcr = PropertyParser.parseBoolean(props,
            "excalibur.ingest.indra-image-ocr", true);
    }

    /**
     * Reads the PDF reader setting and refuses one that contradicts the converter command.
     *
     * <p>Refused at startup rather than resolved either way: a command beside another reader, or
     * the command reader without one, is an operator who meant one of the two, and guessing
     * which reads every PDF the way they did not intend.</p>
     */
    private static String parsePdfReader(Map<String, String> props, String command) {
        String reader = PropertyParser.parseString(props, "excalibur.ingest.pdf-reader", "")
            .toLowerCase(java.util.Locale.ROOT);
        if (!reader.isEmpty() && !List.of(PDF_READER_TEXT_LAYER, PDF_READER_INDRA,
                PDF_READER_COMMAND).contains(reader)) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-reader must be "
                + PDF_READER_TEXT_LAYER + ", " + PDF_READER_INDRA + " or " + PDF_READER_COMMAND
                + ", got: " + reader);
        }
        if (reader.equals(PDF_READER_COMMAND) && command.isEmpty()) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-reader is " + reader
                + ", but excalibur.ingest.pdf-converter-command names no program");
        }
        if (!reader.isEmpty() && !reader.equals(PDF_READER_COMMAND) && !command.isEmpty()) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-converter-command is set, "
                + "but excalibur.ingest.pdf-reader is " + reader + ", which does not run it; "
                + "clear one of the two");
        }
        return reader;
    }

    /**
     * The external program PDFs are converted with.
     *
     * @return A name on the {@code PATH} or a path; empty when PDFs are read by the built-in
     *         text-layer reader
     */
    public String getPdfConverterCommand() {
        return pdfConverterCommand;
    }

    /**
     * The arguments placed between the converter and the PDF's path.
     *
     * @return The arguments, split on whitespace; empty for none
     */
    public List<String> getPdfConverterArgs() {
        return pdfConverterArgs;
    }

    /** The longest one PDF may take to convert, in seconds. */
    public int getPdfConverterTimeoutSeconds() {
        return pdfConverterTimeoutSeconds;
    }

    /** How many PDFs this node converts at once, across every source. */
    public int getPdfConverterParallelism() {
        return pdfConverterParallelism;
    }

    /**
     * How PDFs are read.
     *
     * @return {@link #PDF_READER_TEXT_LAYER}, {@link #PDF_READER_INDRA} or
     *         {@link #PDF_READER_COMMAND}; when none is configured, the command reader if a
     *         converter command is set and the text-layer reader otherwise
     */
    public String getPdfReader() {
        if (!pdfReader.isEmpty()) {
            return pdfReader;
        }
        return pdfConverterCommand.isEmpty() ? PDF_READER_TEXT_LAYER : PDF_READER_COMMAND;
    }

    /**
     * Where Indra OCR's models are kept.
     *
     * @return A directory; empty for the per-user cache directory
     */
    public String getIndraModelDir() {
        return indraModelDir;
    }

    /** Whether models missing from the model directory are downloaded when first needed. */
    public boolean isIndraModelDownload() {
        return indraModelDownload;
    }

    /** Whether the operator has accepted the licence Indra OCR's models are published under. */
    public boolean isIndraAcceptModelLicense() {
        return indraAcceptModelLicense;
    }

    /**
     * Where Indra reads a page from.
     *
     * @return {@link #INDRA_MODE_AUTO}, {@link #INDRA_MODE_OCR} or {@link #INDRA_MODE_TEXT}
     */
    public String getIndraMode() {
        return indraMode;
    }

    /** The resolution pages are rendered at for OCR, in dots per inch. */
    public int getIndraDpi() {
        return indraDpi;
    }

    /** Threads one conversion may use; 0 for every core this process may run on. */
    public int getIndraThreads() {
        return indraThreads;
    }

    /** Whether formulas are read as LaTeX, which takes five more models. */
    public boolean isIndraMath() {
        return indraMath;
    }

    /** Whether page headers and footers are kept in the text. */
    public boolean isIndraKeepFurniture() {
        return indraKeepFurniture;
    }

    /** Whether text inside pictures on text-layer pages is OCRed too. */
    public boolean isIndraImageOcr() {
        return indraImageOcr;
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
     * How long a published document may wait, while its ingestion or reindex run goes on, before
     * a refresh makes it lexically searchable. A run also refreshes when it ends or is stopped.
     *
     * @return The interval in milliseconds
     */
    public long getLexicalRefreshIntervalMs() {
        return lexicalRefreshIntervalMs;
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
               ", crawlDelayMs=" + crawlDelayMs +
               ", pdfReader=" + getPdfReader() +
               ", pdfConverterCommand=" + pdfConverterCommand + '}';
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
            && lexicalRefreshIntervalMs == other.lexicalRefreshIntervalMs
            && chunkTargetTokens == other.chunkTargetTokens
            && chunkOverlapTokens == other.chunkOverlapTokens
            && Double.compare(bm25K1, other.bm25K1) == 0
            && Double.compare(bm25B, other.bm25B) == 0
            && Objects.equals(lexicalEngine, other.lexicalEngine)
            && Objects.equals(pdfConverterCommand, other.pdfConverterCommand)
            && Objects.equals(pdfConverterArgs, other.pdfConverterArgs)
            && pdfConverterTimeoutSeconds == other.pdfConverterTimeoutSeconds
            && pdfConverterParallelism == other.pdfConverterParallelism
            && pdfReader.equals(other.pdfReader)
            && indraModelDir.equals(other.indraModelDir)
            && indraModelDownload == other.indraModelDownload
            && indraAcceptModelLicense == other.indraAcceptModelLicense
            && indraMode.equals(other.indraMode)
            && indraDpi == other.indraDpi
            && indraThreads == other.indraThreads
            && indraMath == other.indraMath
            && indraKeepFurniture == other.indraKeepFurniture
            && indraImageOcr == other.indraImageOcr;
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentTimeoutMs, documentAtomicity, reindexIntervalMs, crawlDelayMs,
            lexicalEngine, lexicalTombstoneRatio, lexicalMaxLatencySeconds, lexicalMaxSegments,
            lexicalRefreshIntervalMs, chunkTargetTokens, chunkOverlapTokens, bm25K1, bm25B,
            pdfConverterCommand, pdfConverterArgs, pdfConverterTimeoutSeconds,
            pdfConverterParallelism, pdfReader, indraModelDir, indraModelDownload,
            indraAcceptModelLicense, indraMode, indraDpi, indraThreads, indraMath,
            indraKeepFurniture, indraImageOcr);
    }
}
