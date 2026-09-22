package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That what this node offers a scraper parses as the Prometheus text exposition format.
 *
 * <p>The format is unforgiving in exactly the ways a hand-built body gets wrong: a locale that
 * writes {@code 0,5} for a half, a help line carrying a raw newline, a label value carrying a
 * quote. Each produces a body a scraper rejects wholesale rather than partially, so the node
 * would publish nothing while appearing to publish everything.</p>
 */
@DisplayName("Prometheus text exposition")
class PrometheusTextTest {

    @Test
    @DisplayName("writes a gauge as HELP, TYPE and one sample")
    void writesAGauge() {
        String text = new PrometheusText()
            .gauge("excalibur_coverage_ratio", "Share of live segments a query reads", 0.25)
            .render();

        assertThat(text).isEqualTo("""
            # HELP excalibur_coverage_ratio Share of live segments a query reads
            # TYPE excalibur_coverage_ratio gauge
            excalibur_coverage_ratio 0.25
            """);
    }

    @Test
    @DisplayName("writes a counter with the counter type")
    void writesACounter() {
        String text = new PrometheusText()
            .counter("excalibur_queries_executed_total", "Queries executed", 7)
            .render();

        assertThat(text).contains("# TYPE excalibur_queries_executed_total counter");
        assertThat(text).contains("excalibur_queries_executed_total 7");
    }

    @Test
    @DisplayName("emits one HELP and one TYPE for a family of labelled samples")
    void groupsALabelledFamily() {
        String text = new PrometheusText()
            .counter("excalibur_http_responses_total", "Responses by status class",
                Map.of("class", "2xx"), 12)
            .counter("excalibur_http_responses_total", "Responses by status class",
                Map.of("class", "5xx"), 3)
            .render();

        // A repeated HELP or TYPE for the same family is a duplicate-metric error to a
        // scraper, which drops the whole scrape rather than the offending line.
        assertThat(text.split("# HELP excalibur_http_responses_total", -1)).hasSize(2);
        assertThat(text.split("# TYPE excalibur_http_responses_total", -1)).hasSize(2);
        assertThat(text).contains("excalibur_http_responses_total{class=\"2xx\"} 12");
        assertThat(text).contains("excalibur_http_responses_total{class=\"5xx\"} 3");
    }

    @Test
    @DisplayName("writes a decimal point whatever the default locale is")
    void isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String text = new PrometheusText()
                .gauge("excalibur_fp_rate", "False positive rate", 0.0005)
                .render();
            assertThat(text).contains("excalibur_fp_rate 0.0005");
            assertThat(text).doesNotContain(",");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("writes a whole double without an exponent or a trailing .0")
    void writesRoundNumbersPlainly() {
        String text = new PrometheusText()
            .gauge("excalibur_bytes", "Bytes", 5_368_709_120.0)
            .render();
        assertThat(text).contains("excalibur_bytes 5368709120");
    }

    @Test
    @DisplayName("writes the values a scraper has words for rather than Java's")
    void writesNonFiniteValues() {
        String text = new PrometheusText()
            .gauge("excalibur_a", "A", Double.NaN)
            .gauge("excalibur_b", "B", Double.POSITIVE_INFINITY)
            .gauge("excalibur_c", "C", Double.NEGATIVE_INFINITY)
            .render();
        assertThat(text).contains("excalibur_a NaN");
        assertThat(text).contains("excalibur_b +Inf");
        assertThat(text).contains("excalibur_c -Inf");
    }

    @Test
    @DisplayName("escapes a help line so it cannot become two lines")
    void escapesHelp() {
        String text = new PrometheusText()
            .gauge("excalibur_a", "first\nsecond\\third", 1)
            .render();
        assertThat(text).contains("# HELP excalibur_a first\\nsecond\\\\third");
        assertThat(text.lines().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("escapes a label value so it cannot close its own quote")
    void escapesLabelValues() {
        String text = new PrometheusText()
            .gauge("excalibur_build_info", "Build", Map.of("version", "1.0 \"rc\"\n"), 1)
            .render();
        assertThat(text).contains("version=\"1.0 \\\"rc\\\"\\n\"");
        assertThat(text.lines().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("orders labels so two scrapes of the same state read the same")
    void ordersLabels() {
        String text = new PrometheusText()
            .gauge("excalibur_x", "X", Map.of("b", "2", "a", "1"), 1)
            .render();
        assertThat(text).contains("excalibur_x{a=\"1\",b=\"2\"} 1");
    }

    @Test
    @DisplayName("refuses a metric name a scraper cannot parse")
    void refusesABadName() {
        assertThatThrownBy(() -> new PrometheusText().gauge("excalibur-coverage", "C", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrometheusText().gauge("", "C", 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a label name a scraper cannot parse")
    void refusesABadLabelName() {
        assertThatThrownBy(() ->
            new PrometheusText().gauge("excalibur_x", "X", Map.of("a-b", "1"), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses to change a family's type half way through")
    void refusesAConflictingType() {
        PrometheusText text = new PrometheusText()
            .counter("excalibur_x", "X", 1);
        assertThatThrownBy(() -> text.gauge("excalibur_x", "X", 2))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("writes a summary's quantiles and count under one header")
    void writesASummary() {
        String text = new PrometheusText()
            .summary("excalibur_query_latency_milliseconds", "Query latency",
                Map.of("0.99", 40.0, "0.5", 3.0, "0.999", 120.0), 812)
            .render();

        assertThat(text).isEqualTo("""
            # HELP excalibur_query_latency_milliseconds Query latency
            # TYPE excalibur_query_latency_milliseconds summary
            excalibur_query_latency_milliseconds{quantile="0.5"} 3
            excalibur_query_latency_milliseconds{quantile="0.99"} 40
            excalibur_query_latency_milliseconds{quantile="0.999"} 120
            excalibur_query_latency_milliseconds_count 812
            """);
    }

    @Test
    @DisplayName("orders a summary by quantile rather than by the text of its label")
    void ordersQuantilesNumerically() {
        // "0.999" sorts before "0.99" as text, which would put the tail in the middle.
        String text = new PrometheusText()
            .summary("excalibur_x", "X", Map.of("0.999", 1.0, "0.99", 2.0, "0.9", 3.0), 1)
            .render();
        assertThat(text.indexOf("0.9\"")).isLessThan(text.indexOf("0.99\""));
        assertThat(text.indexOf("0.99\"")).isLessThan(text.indexOf("0.999\""));
    }

    @Test
    @DisplayName("ends with a newline, as the format requires")
    void endsWithANewline() {
        assertThat(new PrometheusText().gauge("excalibur_x", "X", 1).render()).endsWith("\n");
        assertThat(new PrometheusText().render()).isEmpty();
    }
}
