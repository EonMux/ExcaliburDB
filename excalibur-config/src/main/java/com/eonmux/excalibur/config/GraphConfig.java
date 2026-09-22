package com.eonmux.excalibur.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Graph-RAG configuration.
 *
 * <p>Everything {@code POST /v1/graph/query} needs that is not on disk beside the graph
 * itself: where to find it, how much evidence an answer requires, and how much context the
 * model is given.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe.</p>
 */
public final class GraphConfig {

    /**
     * Smallest relevance the entity a question named must reach before an answer is attempted.
     *
     * <p>Read on the graph route's unit scale: the share of the question's attainable BM25 score
     * the entity carries - over the words the graph's descriptions hold at all - or its vector
     * similarity to the question once the exact rerank has run. One twentieth declines a named
     * entity whose description carries almost none of what the question asks, the rest of it
     * pointing at other entities, while one matching a single distinctive word of a short
     * question clears it several times over.
     */
    public static final double DEFAULT_MIN_SCORE = 0.05;

    /**
     * Smallest gap between the two best readings of one name, as a fraction of the best, before
     * the result is ambiguous.
     *
     * <p>One percent: two entities sharing the name the question gave, which it matched within a
     * hundredth of each other, were not told apart by anything the question said, and are handed
     * back to be chosen between rather than chosen by a rounding error.
     */
    public static final double DEFAULT_MIN_SEPARATION = 0.01;

    /** Smallest mean confidence across the surviving evidence. */
    public static final double DEFAULT_MIN_MEAN_CONFIDENCE = 0.3;

    /** How many equally-plausible candidates are reported back rather than chosen between. */
    public static final int DEFAULT_MAX_AMBIGUOUS_CANDIDATES = 5;

    /**
     * Divisor applied to the log-odds of a reported confidence. One leaves it untouched.
     *
     * <p>One by default because the honest default is no correction. A temperature is measured,
     * not guessed: {@code AbstentionCalibrator.fitTemperature} fits it against queries whose
     * answers were known, and a number invented here would make every node's confidences wrong in
     * a way no operator asked for. Above one softens overconfident claims towards 0.5, below one
     * sharpens underconfident ones.
     */
    public static final double DEFAULT_CONFIDENCE_TEMPERATURE = 1.0;

    /**
     * Widest temperature a node will accept.
     *
     * <p>The fit is bounded at 20 and reports a value that reached the bound as not well posed, so
     * a configured 20 is either a coincidence or a badly posed fit copied in without reading what
     * it said. Beyond it the value is certainly a typo - a temperature of 100 reports 0.51 for an
     * answer the policy was sure of, which looks like a broken node rather than a cautious one.
     */
    public static final double MAX_CONFIDENCE_TEMPERATURE = 20.0;

    /** Tokens of serialised subgraph an answer may carry. */
    public static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 2000;

    /** Documents each retrieval signal contributes before fusion. */
    public static final int DEFAULT_TOP_K = 10;

    /** Candidates the full-precision reranker reads per result it returns. */
    public static final int DEFAULT_RERANK_OVER_FETCH = 4;

    /** Lucene RAM buffer, in megabytes, for the read-only indexes. */
    public static final double DEFAULT_RAM_BUFFER_MB = 16.0;

    /**
     * Whether ingestion extracts facts by default.
     *
     * <p>Off. Extraction is one chat-model call per document, which is the most expensive
     * thing ingestion can do, and a deployment that wants vector search should not start
     * paying for a graph it never asked for.
     */
    public static final boolean DEFAULT_EXTRACTION_ENABLED = false;

    /** Extractor version recorded on facts when none is configured. */
    public static final int DEFAULT_EXTRACTOR_VERSION = 1;

    /**
     * Whether a build embeds each entity's text.
     *
     * <p>On, because it is what lets retrieval rerank against real vectors rather than stop at
     * term overlap - and it only happens at all when an embedding endpoint is configured. It
     * costs one embedding call per batch of entities at build time, which is why it can be
     * turned off for a deployment that wants the graph and not that cost.
     */
    public static final boolean DEFAULT_ENTITY_VECTORS_ENABLED = true;

    /**
     * How many namespaces' graphs stay open at once.
     *
     * <p>A served store is thirteen memory mappings. The mapping budget is the hard bound;
     * this is the count bound beside it, so a node with many small tenants does not hold every
     * one of them open merely because none is individually expensive.
     */
    public static final int DEFAULT_MAX_OPEN_NAMESPACES = 16;

    /**
     * Tombstoned fraction of the store at which erasure compaction becomes worthwhile.
     *
     * <p>A compaction rewrites the whole store, so running one per request would make erasure
     * a way to keep a node permanently busy. Waiting until a fifth of the edges are tombstoned
     * amortises it.
     */
    public static final double DEFAULT_ERASURE_TOMBSTONE_RATIO = 0.2;

    /**
     * Longest an erasure obligation may wait, whatever has accumulated.
     *
     * <p>The ratio alone would let a single request sit indefinitely on a large store that
     * never reaches the threshold. An obligation to erase is not a performance tuning knob;
     * this is the bound that makes it a promise.
     */
    public static final long DEFAULT_ERASURE_MAX_LATENCY_SECONDS = 3600L;

    private final String path;
    private final double minScore;
    private final double minSeparation;
    private final double minMeanConfidence;
    private final int maxAmbiguousCandidates;
    private final double confidenceTemperature;
    private final int contextTokenBudget;
    private final int topK;
    private final int rerankOverFetch;
    private final double ramBufferMb;
    private final boolean extractionEnabled;
    private final int extractorVersion;
    private final List<String> singleValuedRelations;
    private final boolean entityVectorsEnabled;
    private final int maxOpenNamespaces;
    private final List<String> hotNamespaces;
    private final List<String> coldNamespaces;
    private final double erasureTombstoneRatio;
    private final long erasureMaxLatencySeconds;

    /**
     * Creates a Graph config from properties.
     *
     * @param props The properties map
     * @throws IllegalArgumentException if a threshold is outside its domain
     */
    public GraphConfig(Map<String, String> props) {
        this.path = PropertyParser.parseString(props, "excalibur.graph.path", null);
        this.extractionEnabled = PropertyParser.parseBoolean(
            props, "excalibur.graph.extraction-enabled", DEFAULT_EXTRACTION_ENABLED);
        this.extractorVersion = PropertyParser.parseInt(
            props, "excalibur.graph.extractor-version", DEFAULT_EXTRACTOR_VERSION);
        this.entityVectorsEnabled = PropertyParser.parseBoolean(
            props, "excalibur.graph.entity-vectors-enabled", DEFAULT_ENTITY_VECTORS_ENABLED);
        this.maxOpenNamespaces = ConfigBounds.atLeast(
            props, "excalibur.graph.max-open-namespaces", DEFAULT_MAX_OPEN_NAMESPACES, 1);
        this.erasureTombstoneRatio = requirePositiveFraction(PropertyParser.parseDouble(
            props, "excalibur.graph.erasure-tombstone-ratio",
            DEFAULT_ERASURE_TOMBSTONE_RATIO), "erasure-tombstone-ratio");
        this.erasureMaxLatencySeconds = PropertyParser.parseLong(
            props, "excalibur.graph.erasure-max-latency-seconds",
            DEFAULT_ERASURE_MAX_LATENCY_SECONDS);
        if (erasureMaxLatencySeconds <= 0) {
            throw new IllegalArgumentException(
                "excalibur.graph.erasure-max-latency-seconds must be positive, got: "
                    + erasureMaxLatencySeconds);
        }
        if (extractorVersion < Short.MIN_VALUE || extractorVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "excalibur.graph.extractor-version must fit in a short, got "
                    + extractorVersion);
        }
        // Relations that hold one value at a time. Empty by default, because closing an
        // interval destroys information: a relation wrongly left multi-valued returns a stale
        // extra answer, while one wrongly superseded silently deletes a true fact.
        this.singleValuedRelations =
            ConfigBounds.parseList(props, "excalibur.graph.single-valued-relations");
        // Which namespaces resist eviction and which give way first. Both empty by default,
        // which is the shipped policy: the shared index pinned, everything else evicted by
        // recency. The names are validated where they become namespace identifiers, not
        // here - this section does not depend on the tenancy module.
        this.hotNamespaces = ConfigBounds.parseList(props, "excalibur.graph.hot-namespaces");
        this.coldNamespaces = ConfigBounds.parseList(props,
            "excalibur.graph.cold-namespaces");
        this.minScore = requireInUnitRange(PropertyParser.parseDouble(
            props, "excalibur.graph.min-score", DEFAULT_MIN_SCORE), "min-score");
        this.minSeparation = requireInUnitRange(PropertyParser.parseDouble(
            props, "excalibur.graph.min-separation", DEFAULT_MIN_SEPARATION),
            "min-separation");
        this.minMeanConfidence = requireInUnitRange(PropertyParser.parseDouble(
            props, "excalibur.graph.min-mean-confidence", DEFAULT_MIN_MEAN_CONFIDENCE),
            "min-mean-confidence");
        // At least two, not at least one: the abstention policy calls a result ambiguous by
        // comparing competing readings, so one candidate is not a value it can work with and
        // it refuses it. Accepting it here deferred the refusal to the graph open, where the
        // same mistake reads as a broken store rather than a setting to correct.
        this.maxAmbiguousCandidates = ConfigBounds.atLeast(
            props, "excalibur.graph.max-ambiguous-candidates",
            DEFAULT_MAX_AMBIGUOUS_CANDIDATES, 2);
        this.confidenceTemperature = requireTemperature(PropertyParser.parseDouble(
            props, "excalibur.graph.confidence-temperature", DEFAULT_CONFIDENCE_TEMPERATURE));
        this.contextTokenBudget = ConfigBounds.atLeast(
            props, "excalibur.graph.context-token-budget", DEFAULT_CONTEXT_TOKEN_BUDGET, 1);
        this.topK = ConfigBounds.atLeast(
            props, "excalibur.graph.top-k", DEFAULT_TOP_K, 1);
        this.rerankOverFetch = ConfigBounds.atLeast(
            props, "excalibur.graph.rerank-over-fetch", DEFAULT_RERANK_OVER_FETCH, 1);
        double buffer = PropertyParser.parseDouble(
            props, "excalibur.graph.ram-buffer-mb", DEFAULT_RAM_BUFFER_MB);
        if (buffer <= 0) {
            throw new IllegalArgumentException(
                "excalibur.graph.ram-buffer-mb must be positive, got: " + buffer);
        }
        this.ramBufferMb = buffer;
    }

    /**
     * Requires a graph threshold to be a probability: anywhere in [0.0, 1.0], zero included.
     *
     * <p>Kept here rather than shared. Zero is meaningful for every key that uses it - a
     * minimum score of zero is "accept any candidate", a separation of zero is "never call a
     * result ambiguous" - which is the opposite of what zero means to the ratios the other
     * sections bound, where it would ask for work on every write.</p>
     *
     * @param value The parsed value
     * @param name The key's suffix below {@code excalibur.graph.}, named in the failure message
     * @return The value
     * @throws IllegalArgumentException if the value is outside [0.0, 1.0]
     */
    private static double requireTemperature(double value) {
        if (Double.isNaN(value) || value <= 0.0 || value > MAX_CONFIDENCE_TEMPERATURE) {
            throw new IllegalArgumentException(
                "excalibur.graph.confidence-temperature must be in (0.0, "
                    + MAX_CONFIDENCE_TEMPERATURE + "], got: " + value);
        }
        return value;
    }

    /**
     * Refuses a fraction that is not above zero and at most one.
     *
     * <p>For a threshold that is reached by accumulating something: at zero it would be reached
     * before anything had accumulated.</p>
     */
    private static double requirePositiveFraction(double value, String name) {
        if (!(value > 0.0) || value > 1.0) {
            throw new IllegalArgumentException(
                "excalibur.graph." + name + " must be in (0.0, 1.0], got: " + value);
        }
        return value;
    }

    private static double requireInUnitRange(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                "excalibur.graph." + name + " must be in [0.0, 1.0], got: " + value);
        }
        return value;
    }

    /** @return the configured graph directory, or null to use the data directory default */
    public String getPath() {
        return path;
    }

    /** @return smallest relevance the best candidate must reach, on the unit scale */
    public double getMinScore() {
        return minScore;
    }

    /** @return smallest gap between the best two candidates, as a fraction of the best */
    public double getMinSeparation() {
        return minSeparation;
    }

    /** @return smallest mean confidence across the surviving evidence */
    public double getMinMeanConfidence() {
        return minMeanConfidence;
    }

    /** @return how many candidates are reported back when the answer is ambiguous */
    public int getMaxAmbiguousCandidates() {
        return maxAmbiguousCandidates;
    }

    /**
     * @return the divisor applied to the log-odds of a reported confidence; 1.0 leaves it alone
     */
    public double getConfidenceTemperature() {
        return confidenceTemperature;
    }

    /** @return tokens of serialised subgraph an answer may carry */
    public int getContextTokenBudget() {
        return contextTokenBudget;
    }

    /**
     * Candidates the full-precision reranker reads per result it returns.
     *
     * <p>Reranking can only reorder what it is given, so this is what lets a document
     * that term overlap ranked poorly be rescued by its vector. Each extra candidate is
     * one more full-precision vector read.</p>
     *
     * @return the over-fetch multiplier, at least 1
     */
    public int getRerankOverFetch() {
        return rerankOverFetch;
    }

    /** @return documents each retrieval signal contributes before fusion */
    public int getTopK() {
        return topK;
    }

    /** @return relations that hold one value at a time, whose new values close the old */
    public List<String> getSingleValuedRelations() {
        return singleValuedRelations;
    }

    /**
     * Namespaces pinned against eviction, alongside the shared index.
     *
     * @return Their names, in the order configured; empty by default
     */
    public List<String> getHotNamespaces() {
        return hotNamespaces;
    }

    /**
     * Namespaces evicted before any other, whatever their recency.
     *
     * @return Their names, in the order configured; empty by default
     */
    public List<String> getColdNamespaces() {
        return coldNamespaces;
    }

    /**
     * Whether ingestion should extract facts from the documents it publishes.
     *
     * @return true when the graph is built from ingested documents
     */
    public boolean isEntityVectorsEnabled() {
        return entityVectorsEnabled;
    }

    /**
     * How many namespaces' graphs stay open at once.
     *
     * @return The bound
     */
    public int getMaxOpenNamespaces() {
        return maxOpenNamespaces;
    }

    /**
     * Tombstoned fraction of the store at which an erasure compaction becomes worthwhile.
     *
     * @return The ratio, in [0, 1]
     */
    public double getErasureTombstoneRatio() {
        return erasureTombstoneRatio;
    }

    /**
     * Longest an erasure obligation may wait before a compaction runs regardless.
     *
     * @return The bound in seconds
     */
    public long getErasureMaxLatencySeconds() {
        return erasureMaxLatencySeconds;
    }

    /**
     * Whether ingestion extracts facts from the documents it publishes.
     *
     * @return true when each ingested document is passed to the chat model for extraction
     */
    public boolean isExtractionEnabled() {
        return extractionEnabled;
    }

    /**
     * The version recorded on every fact this node extracts.
     *
     * @return The extractor version
     */
    public int getExtractorVersion() {
        return extractorVersion;
    }

    public double getRamBufferMb() {
        return ramBufferMb;
    }

    @Override
    public String toString() {
        return "Graph{root=" + (path != null ? path : "<data dir>")
            + ", minScore=" + minScore
            + ", minSeparation=" + minSeparation
            + ", minMeanConfidence=" + minMeanConfidence
            + ", confidenceTemperature=" + confidenceTemperature
            + ", maxAmbiguousCandidates=" + maxAmbiguousCandidates
            + ", contextTokenBudget=" + contextTokenBudget
            + ", topK=" + topK
            + ", rerankOverFetch=" + rerankOverFetch + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GraphConfig other = (GraphConfig) o;
        return Double.compare(minScore, other.minScore) == 0
            && Double.compare(minSeparation, other.minSeparation) == 0
            && Double.compare(minMeanConfidence, other.minMeanConfidence) == 0
            && Double.compare(confidenceTemperature, other.confidenceTemperature) == 0
            && maxAmbiguousCandidates == other.maxAmbiguousCandidates
            && contextTokenBudget == other.contextTokenBudget
            && topK == other.topK
            && rerankOverFetch == other.rerankOverFetch
            && Double.compare(ramBufferMb, other.ramBufferMb) == 0
            && extractionEnabled == other.extractionEnabled
            && extractorVersion == other.extractorVersion
            && entityVectorsEnabled == other.entityVectorsEnabled
            && maxOpenNamespaces == other.maxOpenNamespaces
            && Double.compare(erasureTombstoneRatio, other.erasureTombstoneRatio) == 0
            && erasureMaxLatencySeconds == other.erasureMaxLatencySeconds
            && Objects.equals(singleValuedRelations, other.singleValuedRelations)
            && Objects.equals(hotNamespaces, other.hotNamespaces)
            && Objects.equals(coldNamespaces, other.coldNamespaces)
            && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, minScore, minSeparation, minMeanConfidence,
            confidenceTemperature,
            maxAmbiguousCandidates, contextTokenBudget, topK, rerankOverFetch, ramBufferMb,
            extractionEnabled, extractorVersion, entityVectorsEnabled, maxOpenNamespaces,
            erasureTombstoneRatio, erasureMaxLatencySeconds, singleValuedRelations,
            hotNamespaces, coldNamespaces);
    }
}
