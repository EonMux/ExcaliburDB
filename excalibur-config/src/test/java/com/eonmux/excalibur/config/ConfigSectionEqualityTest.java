package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a configuration section is a value: two sections parsed from the same
 * properties are equal, and changing any single parsed field makes them unequal.
 *
 * <p>The per-field half is driven by reflection rather than by hand. Each section lists one
 * variant per instance field, and the test asserts that the variant perturbs exactly that
 * field and that the perturbation is visible through {@code equals} and {@code hashCode}.
 * A field omitted from either therefore fails, and so does a field added to a section
 * without a variant, because the variants have to account for every declared field.</p>
 */
@DisplayName("Configuration section value equality")
class ConfigSectionEqualityTest {

    /**
     * One property overlay, and the single field it is expected to change.
     *
     * @param field The instance field the overlay perturbs
     * @param overlay Properties laid over the section's baseline
     */
    private record Variant(String field, Map<String, String> overlay) {
    }

    /**
     * A section, how to build it, and the variants that cover its fields.
     *
     * @param type The section class
     * @param factory Builds the section from a properties map
     * @param baseline Properties every instance of this section is built with
     * @param variants One variant per instance field
     */
    private record SectionUnderTest(Class<?> type,
                                    Function<Map<String, String>, Object> factory,
                                    Map<String, String> baseline,
                                    List<Variant> variants) {
        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sections")
    @DisplayName("two sections parsed from the same properties are equal and hash alike")
    void samePropertiesProduceEqualSections(SectionUnderTest section) {
        Map<String, String> props = everyVariantApplied(section);

        Object first = section.factory().apply(new HashMap<>(props));
        Object second = section.factory().apply(new HashMap<>(props));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(null).isNotEqualTo("not a configuration section");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sections")
    @DisplayName("every parsed field is visible through equals and hashCode")
    void everyFieldParticipatesInEquality(SectionUnderTest section) throws Exception {
        Object baseline = section.factory().apply(new HashMap<>(section.baseline()));
        Set<String> covered = new TreeSet<>();

        for (Variant variant : section.variants()) {
            Map<String, String> props = new HashMap<>(section.baseline());
            props.putAll(variant.overlay());
            Object changed = section.factory().apply(props);

            assertThat(fieldsThatDiffer(baseline, changed))
                .as("%s in %s must perturb exactly the field it claims", variant.overlay(), section)
                .containsExactly(variant.field());
            assertThat(changed)
                .as("%s.%s must take part in equals", section, variant.field())
                .isNotEqualTo(baseline);
            assertThat(changed.hashCode())
                .as("%s.%s must take part in hashCode", section, variant.field())
                .isNotEqualTo(baseline.hashCode());
            covered.add(variant.field());
        }

        assertThat(covered)
            .as("every instance field of %s needs a variant", section)
            .containsExactlyInAnyOrderElementsOf(instanceFieldNames(section.type()));
    }

    @Test
    @DisplayName("two Config instances built from the same properties are equal")
    void configIsAValue() {
        Map<String, String> props = configProperties();

        Config first = new Config(new HashMap<>(props));
        Config second = new Config(new HashMap<>(props));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "excalibur.visibility.epoch-tick-ms",
        "excalibur.ingest.lexical-max-segments",
        "excalibur.health.max-latency-ms",
        "excalibur.query.rrf-k",
        "excalibur.llm.chat-model",
        "excalibur.shard.compaction-write-log-mb",
        "excalibur.graph.top-k"
    })
    @DisplayName("a Config differing in one section's property is unequal")
    void configFollowsItsSections(String key) {
        Map<String, String> props = configProperties();
        Config baseline = new Config(new HashMap<>(props));

        Map<String, String> changedProps = new HashMap<>(props);
        changedProps.put(key, key.endsWith("chat-model") ? "some-other-chat-model" : "7");

        assertThat(new Config(changedProps)).isNotEqualTo(baseline);
    }

    /**
     * The sections under test, each with a variant for every one of its instance fields.
     *
     * @return One entry per configuration section
     */
    private static Stream<SectionUnderTest> sections() {
        return Stream.of(visibility(), ingest(), health(), query(), llm(), shard(), graph());
    }

    private static SectionUnderTest visibility() {
        return new SectionUnderTest(VisibilityConfig.class, VisibilityConfig::new, Map.of(), List.of(
            v("epochTickMs", "excalibur.visibility.epoch-tick-ms", "200"),
            v("queryTimeoutMs", "excalibur.visibility.query-timeout-ms", "20000")));
    }

    private static SectionUnderTest ingest() {
        return new SectionUnderTest(IngestConfig.class, IngestConfig::new, Map.of(), List.of(
            v("documentTimeoutMs", "excalibur.ingest.document-atomicity-timeout-ms", "3000"),
            v("documentAtomicity", "excalibur.ingest.document-atomicity", "false"),
            v("reindexIntervalMs", "excalibur.ingest.reindex-interval-ms", "7200000"),
            v("crawlDelayMs", "excalibur.ingest.crawl-delay-ms", "1000"),
            v("lexicalRefreshIntervalMs", "excalibur.ingest.lexical-refresh-interval-ms", "5000"),
            v("lexicalEngine", "excalibur.ingest.lexical-engine", "lucene"),
            v("lexicalTombstoneRatio", "excalibur.ingest.lexical-tombstone-ratio", "0.45"),
            v("lexicalMaxLatencySeconds", "excalibur.ingest.lexical-max-latency-seconds", "7200"),
            v("lexicalMaxSegments", "excalibur.ingest.lexical-max-segments", "32"),
            v("chunkTargetTokens", "excalibur.ingest.chunk-target-tokens", "1024"),
            v("chunkOverlapTokens", "excalibur.ingest.chunk-overlap-tokens", "128"),
            v("bm25K1", "excalibur.ingest.bm25-k1", "1.5"),
            v("bm25B", "excalibur.ingest.bm25-b", "0.55")));
    }

    private static SectionUnderTest health() {
        return new SectionUnderTest(HealthConfig.class, HealthConfig::new, Map.of(), List.of(
            v("readinessThreshold", "excalibur.health.readiness-threshold", "0.6"),
            v("healthyThreshold", "excalibur.health.healthy-threshold", "0.95"),
            v("maxLatencyMs", "excalibur.health.max-latency-ms", "6000"),
            v("minFreeDiskRatio", "excalibur.health.min-free-disk-ratio", "0.1")));
    }

    private static SectionUnderTest query() {
        return new SectionUnderTest(QueryConfig.class, QueryConfig::new, Map.of(), List.of(
            v("defaultTopK", "excalibur.query.top-k-default", "50"),
            v("threshold", "excalibur.query.threshold", "0.5"),
            v("vectorDimensions", "excalibur.query.vector-dimensions", "768"),
            v("llmTimeoutMs", "excalibur.query.llm-timeout-ms", "30000"),
            v("expansionTimeoutMs", "excalibur.query.expansion-timeout-ms", "7000"),
            v("expandHybridQueries", "excalibur.query.expand-hybrid-queries", "true"),
            v("includePartialSegments", "excalibur.query.include-partial-segments", "false"),
            v("hybridRetrieval", "excalibur.query.hybrid-retrieval", "false"),
            v("distanceMetric", "excalibur.query.distance-metric", "l2"),
            v("rrfK", "excalibur.query.rrf-k", "30"),
            v("perSignalOverFetch", "excalibur.query.per-signal-over-fetch", "5"),
            v("cacheEnabled", "excalibur.query.cache.enabled", "true"),
            v("cacheMaxEntries", "excalibur.query.cache.max-entries", "2048")));
    }

    private static SectionUnderTest llm() {
        return new SectionUnderTest(LlmConfig.class, LlmConfig::new, Map.of(), List.of(
            v("baseUrl", "excalibur.llm.base-url", "https://models.example.test/v1"),
            v("apiKey", "excalibur.llm.api-key", "sk-not-a-real-key"),
            v("chatModel", "excalibur.llm.chat-model", "gpt-4o"),
            v("embeddingModel", "excalibur.llm.embedding-model", "text-embedding-3-large"),
            v("embeddingsDimensions", "excalibur.llm.embeddings-dimensions", "3072"),
            v("embeddingsEnabled", "excalibur.llm.embeddings-enabled", "false"),
            v("timeoutMs", "excalibur.llm.timeout-ms", "30000"),
            v("maxRetries", "excalibur.llm.max-retries", "5"),
            v("failureThreshold", "excalibur.llm.failure-threshold", "7"),
            v("cooldownPeriodMs", "excalibur.llm.cooldown-period-ms", "30000"),
            v("maxPromptsPerQuery", "excalibur.llm.max-prompts-per-query", "20"),
            v("relevancyThreshold", "excalibur.llm.relevancy-threshold", "0.6"),
            v("maxBatchTokens", "excalibur.llm.max-batch-tokens", "8000"),
            v("retryBaseDelayMs", "excalibur.llm.retry-base-delay-ms", "1000")));
    }

    private static SectionUnderTest shard() {
        // Layer 0 is pinned in the baseline because it is derived from max-connections when
        // left at 0. Pinning it is what lets max-connections be perturbed on its own.
        Map<String, String> baseline =
            Map.of("excalibur.shard.default-max-connections-layer0", "32");
        return new SectionUnderTest(ShardConfig.class, ShardConfig::new, baseline, List.of(
            v("loadFactor", "excalibur.shard.segment-load-factor", "0.8"),
            v("efConstruction", "excalibur.shard.default-ef-construction", "256"),
            v("efSearch", "excalibur.shard.default-ef-search", "128"),
            v("compactDelayMs", "excalibur.shard.compaction-delay-ms", "50"),
            v("hnswQueueLimit", "excalibur.shard.hnsw-integration-queue-depth-limit", "50000"),
            v("maxConnections", "excalibur.shard.default-max-connections", "32"),
            v("maxConnectionsLayer0", "excalibur.shard.default-max-connections-layer0", "64"),
            v("compactionIntervalMs", "excalibur.shard.compaction-interval-ms", "2500"),
            v("segmentSyncIntervalMs", "excalibur.shard.segment-sync-interval-ms", "500"),
            v("compactionTombstoneRatio", "excalibur.shard.compaction-tombstone-ratio", "0.35"),
            v("compactionWriteLogMb", "excalibur.shard.compaction-write-log-mb", "64"),
            v("compactionMaxConcurrent", "excalibur.shard.compaction-max-concurrent", "2"),
            v("hnswCompactionIntervalMs", "excalibur.shard.hnsw-compaction-interval-ms", "30000"),
            v("hnswCompactionTombstoneRatio",
                "excalibur.shard.hnsw-compaction-tombstone-ratio", "0.45"),
            v("lshHashTables", "excalibur.shard.lsh-hash-tables", "20"),
            v("lshHashFunctions", "excalibur.shard.lsh-hash-functions", "24"),
            v("lshProbes", "excalibur.shard.lsh-probes", "6"),
            v("volumes", "excalibur.shard.volumes", "/mnt/one,/mnt/two"),
            v("pqSearchEnabled", "excalibur.shard.pq-search-enabled", "true"),
            v("pqOversample", "excalibur.shard.pq-oversample", "8"),
            v("pqSubspaces", "excalibur.shard.pq-subspaces", "16"),
            v("pqCentroids", "excalibur.shard.pq-centroids", "128"),
            v("pqTrainingSamples", "excalibur.shard.pq-training-samples", "200000"),
            v("maxRoutingWidth", "excalibur.shard.max-routing-width", "4096")));
    }

    private static SectionUnderTest graph() {
        return new SectionUnderTest(GraphConfig.class, GraphConfig::new, Map.of(), List.of(
            v("path", "excalibur.graph.path", "/var/lib/excalibur/graph"),
            v("minScore", "excalibur.graph.min-score", "0.5"),
            v("minSeparation", "excalibur.graph.min-separation", "0.25"),
            v("minMeanConfidence", "excalibur.graph.min-mean-confidence", "0.6"),
            v("maxAmbiguousCandidates", "excalibur.graph.max-ambiguous-candidates", "7"),
            v("confidenceTemperature", "excalibur.graph.confidence-temperature", "2.5"),
            v("contextTokenBudget", "excalibur.graph.context-token-budget", "4000"),
            v("topK", "excalibur.graph.top-k", "25"),
            v("rerankOverFetch", "excalibur.graph.rerank-over-fetch", "8"),
            v("ramBufferMb", "excalibur.graph.ram-buffer-mb", "32.0"),
            v("extractionEnabled", "excalibur.graph.extraction-enabled", "true"),
            v("extractorVersion", "excalibur.graph.extractor-version", "2"),
            v("singleValuedRelations", "excalibur.graph.single-valued-relations", "employer,spouse"),
            v("entityVectorsEnabled", "excalibur.graph.entity-vectors-enabled", "false"),
            v("maxOpenNamespaces", "excalibur.graph.max-open-namespaces", "32"),
            v("maxNamespaces", "excalibur.graph.max-namespaces", "12"),
            v("requireExplicitNamespaces", "excalibur.graph.require-explicit-namespaces", "true"),
            v("hotNamespaces", "excalibur.graph.hot-namespaces", "alpha,beta"),
            v("coldNamespaces", "excalibur.graph.cold-namespaces", "gamma"),
            v("erasureTombstoneRatio", "excalibur.graph.erasure-tombstone-ratio", "0.5"),
            v("erasureMaxLatencySeconds", "excalibur.graph.erasure-max-latency-seconds", "7200")));
    }

    /**
     * Builds a variant that changes one property and is expected to change one field.
     *
     * @param field The instance field the property feeds
     * @param key The property key
     * @param value A value the section's default is not
     * @return The variant
     */
    private static Variant v(String field, String key, String value) {
        return new Variant(field, Map.of(key, value));
    }

    /**
     * Every variant of a section laid over its baseline at once.
     *
     * @param section The section
     * @return Properties setting every one of the section's keys away from its default
     */
    private static Map<String, String> everyVariantApplied(SectionUnderTest section) {
        Map<String, String> props = new HashMap<>(section.baseline());
        section.variants().forEach(variant -> props.putAll(variant.overlay()));
        return props;
    }

    /**
     * Properties that move every section away from at least one of its defaults.
     *
     * @return A properties map a whole Config parses cleanly
     */
    private static Map<String, String> configProperties() {
        Map<String, String> props = new HashMap<>();
        sections().forEach(section -> props.putAll(everyVariantApplied(section)));
        return props;
    }

    /**
     * Names the fields whose values differ between two instances of the same section.
     *
     * @param left One instance
     * @param right The other
     * @return Differing field names, in order
     * @throws IllegalAccessException never, the fields are made accessible first
     */
    private static Set<String> fieldsThatDiffer(Object left, Object right)
            throws IllegalAccessException {
        Set<String> differing = new TreeSet<>();
        for (Field field : instanceFields(left.getClass())) {
            field.setAccessible(true);
            if (!Objects.deepEquals(field.get(left), field.get(right))) {
                differing.add(field.getName());
            }
        }
        return differing;
    }

    /**
     * Names every instance field a section declares.
     *
     * @param type The section class
     * @return The field names
     */
    private static Set<String> instanceFieldNames(Class<?> type) {
        return new TreeSet<>(instanceFields(type).stream().map(Field::getName).toList());
    }

    /**
     * The instance fields a section declares, with anything the compiler or a coverage
     * agent added left out.
     *
     * @param type The section class
     * @return The fields
     */
    private static List<Field> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.isSynthetic())
            .toList();
    }
}
