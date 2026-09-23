package com.eonmux.excalibur.bench.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the record of one benchmark-parameter combination that produced no measurement
 * because it threw.
 */
class ErroredBenchmarkTest {

    private static final String NAME =
        "com.eonmux.excalibur.bench.benchmark.MappedFileBenchmark.sequentialRead_bytes";

    @Test
    @DisplayName("the label names the method, every parameter in a stable order, and the mode")
    void labelIdentifiesTheCombination() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fileSize", "65536");
        params.put("bufferSize", "131072");

        ErroredBenchmark errored = new ErroredBenchmark(NAME, "thrpt", params, "boom", "boom");

        assertThat(errored.methodName()).isEqualTo("sequentialRead_bytes");
        assertThat(errored.label())
            .isEqualTo("sequentialRead_bytes [bufferSize=131072, fileSize=65536] (thrpt)");
        assertThat(errored.category()).isEqualTo("MappedFile");
    }

    @Test
    @DisplayName("a combination without parameters is labelled without brackets")
    void labelWithoutParameters() {
        ErroredBenchmark errored = new ErroredBenchmark(NAME, "sample", Map.of(), "boom", "");

        assertThat(errored.label()).isEqualTo("sequentialRead_bytes (sample)");
    }

    @Test
    @DisplayName("an error must say what went wrong")
    void messageIsRequired() {
        assertThatThrownBy(() -> new ErroredBenchmark(NAME, "thrpt", Map.of(), " ", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErroredBenchmark(null, "thrpt", Map.of(), "m", ""))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the parameters cannot be changed after construction")
    void parametersAreImmutable() {
        Map<String, String> params = new LinkedHashMap<>(Map.of("k", "v"));
        ErroredBenchmark errored = new ErroredBenchmark(NAME, "thrpt", params, "m", "");
        params.put("k", "changed");

        assertThat(errored.parameters()).containsEntry("k", "v");
        assertThatThrownBy(() -> errored.parameters().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a report's errored entries survive the round trip, stack trace and all")
    void errorsRoundTripThroughTheReport() {
        String trace = "java.lang.IllegalStateException: bad \"buffer\" \\ size\n"
            + "\tat Demo.setup(Demo.java:1)\u0001";
        ErroredBenchmark first = new ErroredBenchmark(
            NAME, "thrpt", Map.of("bufferSize", "131072"),
            "java.lang.IllegalStateException: bad \"buffer\" \\ size", trace);
        ErroredBenchmark second = new ErroredBenchmark(
            NAME.replace("sequentialRead_bytes", "randomRead_int"), "thrpt", Map.of(),
            "forked VM failed with exit code 134", "");

        String json = BenchmarkResultSet.builder()
            .name("run")
            .startTime(Instant.parse("2024-01-01T00:00:00Z"))
            .endTime(Instant.parse("2024-01-01T00:00:01Z"))
            .addErrored(first)
            .addErrored(second)
            .build()
            .toJson();

        assertThat(ErroredBenchmark.parseReport(json)).containsExactly(first, second);
    }

    @Test
    @DisplayName("a report written before errors were recorded parses to no errors")
    void reportWithoutErroredSectionHasNoErrors() {
        assertThat(ErroredBenchmark.parseReport("{\"name\": \"old\", \"results\": []}")).isEmpty();
    }

    @Test
    @DisplayName("a JMH result file, which is a bare array, parses to no errors")
    void jmhResultArrayHasNoErrors() {
        assertThat(ErroredBenchmark.parseReport("[{\"benchmark\": \"x\"}]")).isEmpty();
    }

    @Test
    @DisplayName("a malformed errored section is rejected rather than read as no errors")
    void malformedErroredSectionIsRejected() {
        assertThatThrownBy(() -> ErroredBenchmark.parseReport(
            "{\"errored\": [{\"benchmark\": \"x\", \"mode\": \"thrpt\"}]}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message");
        assertThatThrownBy(() -> ErroredBenchmark.parseReport("{\"errored\": [ {\"benchmark\": "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reports may carry non-finite scores elsewhere without breaking the errored section")
    void nonFiniteScoresElsewhereAreTolerated() {
        String json = "{\"results\": [{\"score\": {\"value\": NaN, \"error\": -Infinity}}],"
            + " \"errored\": [{\"benchmark\": \"" + NAME + "\", \"mode\": \"thrpt\","
            + " \"parameters\": {}, \"message\": \"m\", \"detail\": \"\"}]}";

        assertThat(ErroredBenchmark.parseReport(json))
            .containsExactly(new ErroredBenchmark(NAME, "thrpt", Map.of(), "m", ""));
    }

    @Test
    @DisplayName("equality covers the whole combination")
    void equalityCoversTheCombination() {
        ErroredBenchmark a = new ErroredBenchmark(NAME, "thrpt", Map.of("k", "1"), "m", "d");
        assertThat(a).isEqualTo(new ErroredBenchmark(NAME, "thrpt", Map.of("k", "1"), "m", "d"));
        assertThat(List.of(a))
            .doesNotContain(new ErroredBenchmark(NAME, "thrpt", Map.of("k", "2"), "m", "d"));
    }
}
