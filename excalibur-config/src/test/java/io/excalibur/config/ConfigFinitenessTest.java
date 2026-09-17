package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link PropertyParser} as the single place that refuses a non-finite number.
 *
 * <p>Every decimal setting in every section is read through {@code parseDouble}, which rejects
 * {@code NaN} and the infinities before any section sees the value. The sections therefore
 * carry no {@code Double.isNaN} guards of their own: a guard that cannot fire reads like a
 * check and performs none, and six copies of one invariant are six copies that can drift -
 * which is exactly how one unit-ratio check came to be missing the guard its twin had.</p>
 *
 * <p>The assertions below name the parser's exact wording. If the parser stops rejecting a
 * non-finite value, or starts reporting it differently, this test fails rather than the
 * absence of the downstream guards turning into a NaN that survives every ordered range
 * check.</p>
 */
@DisplayName("Non-finite property values")
class ConfigFinitenessTest {

    /** Every property key whose value a section parses as a double. */
    private static final List<String> DOUBLE_KEYS = List.of(
        "excalibur.health.readiness-threshold",
        "excalibur.health.healthy-threshold",
        "excalibur.health.min-free-disk-ratio",
        "excalibur.query.threshold",
        "excalibur.llm.relevancy-threshold",
        "excalibur.ingest.lexical-tombstone-ratio",
        "excalibur.ingest.bm25-k1",
        "excalibur.ingest.bm25-b",
        "excalibur.shard.segment-load-factor",
        "excalibur.shard.compaction-tombstone-ratio",
        "excalibur.shard.hnsw-compaction-tombstone-ratio",
        "excalibur.graph.min-score",
        "excalibur.graph.min-separation",
        "excalibur.graph.min-mean-confidence",
        "excalibur.graph.ram-buffer-mb",
        "excalibur.graph.erasure-tombstone-ratio");

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("nonFiniteSettings")
    @DisplayName("are refused by the parser, naming the key and what was expected")
    void nonFiniteValuesAreRefusedByTheParser(String key, String value) {
        Map<String, String> props = new HashMap<>();
        props.put(key, value);

        assertThatThrownBy(() -> new Config(props))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid value for " + key
                + ": expected a finite decimal number, got: " + value);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"nan", "INFINITY", "inf", "1e400d"})
    @DisplayName("near-misses are refused too, so no spelling slips past")
    void unparseableSpellingsAreRefused(String value) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.query.threshold", value);

        assertThatThrownBy(() -> new Config(props))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid value for excalibur.query.threshold: "
                + "expected a finite decimal number, got: " + value);
    }

    /**
     * Every decimal key paired with every literal {@code Double.parseDouble} accepts but the
     * engine cannot use.
     *
     * @return key and value for each case
     */
    private static Stream<Arguments> nonFiniteSettings() {
        return DOUBLE_KEYS.stream().flatMap(key -> Stream.of("NaN", "Infinity", "-Infinity")
            .map(value -> Arguments.of(key, value)));
    }
}
