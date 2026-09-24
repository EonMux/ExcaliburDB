package com.eonmux.excalibur.config;

import java.util.List;
import java.util.Set;

/**
 * Every property key {@link Config} reads, so a mistyped one can be noticed.
 *
 * <p>A property this node does not read is not an error - it is silence. The key is parsed,
 * stored and never consulted, the node starts with the shipped default, and nothing anywhere
 * says the setting did not take. {@code excalibur.ingest.hnsw-queue-depth-limit} looks exactly
 * like a real key and is not one, and a deployment can run for a year on a queue limit it
 * believes it changed.</p>
 *
 * <h2>Only the sections this module owns</h2>
 * <p>{@code excalibur.api.*} is read by a module that {@code excalibur-config} has no
 * dependency on, through {@link Config#getEffectiveProperties()}. Listing its keys here would be
 * a second copy that drifts, so it is left alone: an unrecognised key is only reported for a section this
 * module actually parses.</p>
 *
 * <h2>Reported, not refused</h2>
 * <p>A warning rather than a startup failure, because the same file is reused across versions.
 * A key that a newer build reads and an older one does not is a downgrade, not a typo, and a
 * node that refused to start on one would turn a rollback into an outage.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe.</p>
 */
public final class ConfigKeys {

    /** The prefixes whose keys this module parses, and can therefore judge. */
    private static final List<String> OWNED_PREFIXES = List.of(
        "excalibur.visibility.", "excalibur.ingest.", "excalibur.health.",
        "excalibur.query.", "excalibur.llm.", "excalibur.shard.", "excalibur.graph.");

    /**
     * Every key read under a prefix this module owns.
     *
     * <p>Mostly the ones {@link Config} itself parses, plus the handful another module reads
     * from {@link Config#getEffectiveProperties()} under one of those prefixes. Those have to be
     * here too: this module cannot see the class that reads them, but it does claim their prefix,
     * and warning that a setting which is being honoured is being ignored is worse than saying
     * nothing - an operator who acts on it deletes the line.</p>
     */
    private static final Set<String> KNOWN = Set.of(
        "excalibur.graph.cold-namespaces",
        "excalibur.graph.context-token-budget",
        "excalibur.graph.entity-vectors-enabled",
        "excalibur.graph.erasure-max-latency-seconds",
        "excalibur.graph.erasure-tombstone-ratio",
        "excalibur.graph.extraction-enabled",
        "excalibur.graph.extractor-version",
        "excalibur.graph.hot-namespaces",
        "excalibur.graph.max-ambiguous-candidates",
        "excalibur.graph.max-frontier",
        "excalibur.graph.max-open-namespaces",
        "excalibur.graph.max-walk-facts",
        "excalibur.graph.min-mean-confidence",
        "excalibur.graph.confidence-temperature",
        "excalibur.graph.min-score",
        "excalibur.graph.min-separation",
        "excalibur.graph.path",
        "excalibur.graph.ram-buffer-mb",
        "excalibur.graph.rerank-over-fetch",
        "excalibur.graph.single-valued-relations",
        "excalibur.graph.top-k",
        "excalibur.health.healthy-threshold",
        "excalibur.health.max-latency-ms",
        "excalibur.health.min-free-disk-ratio",
        "excalibur.health.readiness-threshold",
        // Read by excalibur-api's ApiSecurityConfig, not by a Config section: the two crawl
        // allow-lists and the bound on registered sources.
        "excalibur.ingest.allowed-hosts",
        "excalibur.ingest.allowed-roots",
        "excalibur.ingest.max-sources",
        "excalibur.ingest.bm25-b",
        "excalibur.ingest.bm25-k1",
        "excalibur.ingest.chunk-overlap-tokens",
        "excalibur.ingest.chunk-target-tokens",
        "excalibur.ingest.crawl-delay-ms",
        "excalibur.ingest.document-atomicity",
        "excalibur.ingest.document-atomicity-timeout-ms",
        "excalibur.ingest.lexical-engine",
        "excalibur.ingest.lexical-max-latency-seconds",
        "excalibur.ingest.lexical-max-segments",
        "excalibur.ingest.lexical-refresh-interval-ms",
        "excalibur.ingest.lexical-tombstone-ratio",
        "excalibur.ingest.reindex-interval-ms",
        "excalibur.llm.api-key",
        "excalibur.llm.base-url",
        "excalibur.llm.chat-api-key",
        "excalibur.llm.chat-base-url",
        "excalibur.llm.chat-enabled",
        "excalibur.llm.chat-model",
        "excalibur.llm.cooldown-period-ms",
        "excalibur.llm.embedding-api-key",
        "excalibur.llm.embedding-base-url",
        "excalibur.llm.embedding-model",
        "excalibur.llm.embeddings-dimensions",
        "excalibur.llm.embeddings-enabled",
        "excalibur.llm.failure-threshold",
        "excalibur.llm.max-batch-tokens",
        "excalibur.llm.max-prompts-per-query",
        "excalibur.llm.max-retries",
        "excalibur.llm.relevancy-threshold",
        "excalibur.llm.retry-base-delay-ms",
        "excalibur.llm.timeout-ms",
        "excalibur.query.cache.enabled",
        "excalibur.query.cache.max-entries",
        "excalibur.query.distance-metric",
        "excalibur.query.hybrid-retrieval",
        "excalibur.query.include-partial-segments",
        "excalibur.query.expand-hybrid-queries",
        "excalibur.query.expansion-timeout-ms",
        "excalibur.query.llm-timeout-ms",
        "excalibur.query.per-signal-over-fetch",
        "excalibur.query.rrf-k",
        "excalibur.query.threshold",
        "excalibur.query.top-k-default",
        "excalibur.query.vector-dimensions",
        "excalibur.shard.compaction-delay-ms",
        "excalibur.shard.compaction-interval-ms",
        "excalibur.shard.compaction-max-concurrent",
        "excalibur.shard.compaction-tombstone-ratio",
        "excalibur.shard.compaction-write-log-mb",
        "excalibur.shard.default-ef-construction",
        "excalibur.shard.default-ef-search",
        "excalibur.shard.default-max-connections",
        "excalibur.shard.default-max-connections-layer0",
        "excalibur.shard.hnsw-compaction-interval-ms",
        "excalibur.shard.hnsw-compaction-tombstone-ratio",
        "excalibur.shard.hnsw-integration-queue-depth-limit",
        "excalibur.shard.lsh-hash-functions",
        "excalibur.shard.lsh-hash-tables",
        "excalibur.shard.lsh-probes",
        "excalibur.shard.max-routing-width",
        "excalibur.shard.pq-centroids",
        "excalibur.shard.pq-oversample",
        "excalibur.shard.pq-search-enabled",
        "excalibur.shard.pq-subspaces",
        "excalibur.shard.segment-sync-interval-ms",
        "excalibur.shard.pq-training-samples",
        "excalibur.shard.segment-load-factor",
        "excalibur.shard.volumes",
        "excalibur.visibility.epoch-tick-ms",
        "excalibur.visibility.query-timeout-ms");

    private ConfigKeys() {
        throw new AssertionError("No instances");
    }

    /**
     * Whether this module parses the section the key belongs to.
     *
     * @param key A property key
     * @return true if an unrecognised name under it is worth reporting
     */
    public static boolean isOwned(String key) {
        return OWNED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    /**
     * The longest key a suggestion is attempted for.
     *
     * <p>Comfortably past the longest key there is; a value beyond it is not a typo of
     * anything.</p>
     */
    private static final int MAX_SUGGESTIBLE_LENGTH = 128;

    /** {@link #KNOWN} in a fixed order, so a tie between two candidates resolves the same way. */
    private static final List<String> SORTED_KNOWN = KNOWN.stream().sorted().toList();

    /**
     * Whether the key is one {@link Config} reads.
     *
     * @param key A property key
     * @return true if some section consults it
     */
    public static boolean isKnown(String key) {
        return KNOWN.contains(key);
    }

    /**
     * Every key this module reads, in a stable order.
     *
     * <p>Exposed so a test can hold the inventory against the annotated template an operator
     * copies. A setting that exists and is not in that file can only be found by reading the
     * source, and a key in that file this module does not know is reported at every start as a
     * setting being ignored.</p>
     *
     * @return The inventory, sorted
     */
    public static List<String> known() {
        return SORTED_KNOWN;
    }

    /**
     * The known key a mistyped one was most likely meant to be.
     *
     * <p>Naming the nearest match is most of the value of noticing at all: "no such key" sends
     * an operator to the documentation, and "did you mean ...-integration-queue-depth-limit"
     * ends the search.</p>
     *
     * @param key The unrecognised key
     * @return The closest known key, or null when nothing is close enough to suggest
     */
    public static String nearest(String key) {
        // Nothing this long is a slip against a key of at most fifty characters, and the
        // distance is proportional to the input, so a pasted document in a properties file
        // does not buy a scan of every known key.
        if (key.length() > MAX_SUGGESTIBLE_LENGTH) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        // Over SORTED_KNOWN, not the set: Set.of randomises its iteration order per JVM, so two
        // candidates at the same distance would make the same file suggest a different key on
        // each restart - which reads as the node changing its mind.
        for (String candidate : SORTED_KNOWN) {
            int distance = editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // A third of the key's length, so a suggestion is a plausible slip - a dropped word
        // or a transposition - rather than whichever key happened to be least unlike a
        // completely different word.
        return bestDistance <= Math.max(3, key.length() / 3) ? best : null;
    }

    /**
     * Levenshtein distance between two keys.
     *
     * <p>Public so that a module owning its own prefix - the {@code excalibur.api.} settings,
     * which this module deliberately does not claim - can suggest a nearest match the same way
     * rather than carrying a second implementation of the same measure.</p>
     *
     * @param left  One key
     * @param right The other
     * @return How many single-character edits separate them
     */
    public static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution =
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
