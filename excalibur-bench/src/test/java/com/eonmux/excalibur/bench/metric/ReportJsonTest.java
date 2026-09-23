package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the JSON reader and string encoder behind the errored section of a report.
 */
class ReportJsonTest {

    @Test
    @DisplayName("every value kind is read, in document order")
    void readsEveryValueKind() {
        Object value = ReportJson.parse(
            " {\"s\": \"x\", \"n\": -1.5e2, \"t\": true, \"f\": false, \"z\": null,"
                + " \"a\": [1, [], {}], \"o\": {\"k\": \"v\"}} ");

        Map<String, Object> expected = new HashMap<>(Map.of(
            "s", "x", "n", -150.0, "t", true, "f", false,
            "a", List.of(1.0, List.of(), Map.of()), "o", Map.of("k", "v")));
        expected.put("z", null);
        assertThat(value).isEqualTo(expected);
        assertThat(List.copyOf(((Map<?, ?>) value).keySet()))
            .isEqualTo(List.of("s", "n", "t", "f", "z", "a", "o"));
    }

    @Test
    @DisplayName("the non-finite doubles a report can hold are read as numbers")
    void readsNonFiniteNumbers() {
        assertThat(ReportJson.parse("[NaN, Infinity, -Infinity]"))
            .isEqualTo(List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("every escape round-trips through quote and parse")
    void escapesRoundTrip() {
        String nasty = "quote\" backslash\\ slash/ \b\f\n\r\t \u0001 \u001f é 日本";

        String literal = ReportJson.quote(nasty);

        assertThat(literal).doesNotContain("\n").doesNotContain("\u0001");
        assertThat(ReportJson.parse(literal)).isEqualTo(nasty);
        assertThat(ReportJson.parse("\"\\/\\b\\f\\u0041\"")).isEqualTo("/\b\fA");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "{", "[1,", "{\"a\" 1}", "{1: 2}", "\"open", "\"bad \\q\"", "\"\\u12\"",
        "\"\\uzzzz\"", "tru", "nul", "-", "1.2.3", "@", "{} extra", "[1 2]", "\"\\"
    })
    @DisplayName("malformed input is rejected, never read as something else")
    void rejectsMalformedInput(String text) {
        assertThatThrownBy(() -> ReportJson.parse(text))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("malformed JSON at offset");
    }

    @Test
    @DisplayName("an errored section of the wrong shape is rejected")
    void rejectsWrongShapes() {
        for (String json : Arrays.asList(
                "{\"errored\": {}}",
                "{\"errored\": [1]}",
                "{\"errored\": [{\"benchmark\": \"b\", \"mode\": \"m\", \"message\": \"x\","
                    + " \"parameters\": []}]}")) {
            assertThatThrownBy(() -> ErroredBenchmark.parseReport(json))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
