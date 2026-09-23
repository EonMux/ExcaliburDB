package com.eonmux.excalibur.bench.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * One benchmark-parameter combination that was started but produced no usable
 * measurement, because its setup, an iteration, or its forked JVM failed.
 *
 * <p>JMH reports such a combination by omission: {@code Runner.run()} returns results
 * only for the combinations that finished, so without this record a failure is a row
 * that silently is not there, and the run around it can still read as a pass. Every
 * report and exit code in this module therefore treats an errored combination as a
 * failure in its own right.</p>
 *
 * @param benchmarkName the fully qualified benchmark method
 * @param mode the JMH mode's short label, e.g. {@code thrpt} or {@code sample}
 * @param parameters the {@code @Param} values of the combination, sorted by name
 * @param message one line saying what went wrong, normally the exception and its message
 * @param detail everything JMH printed about the failure, stack traces included; may be empty
 */
public record ErroredBenchmark(
        String benchmarkName,
        String mode,
        Map<String, String> parameters,
        String message,
        String detail) {

    /**
     * Validates and defensively copies the combination.
     *
     * @throws IllegalArgumentException if the message is blank: an error that cannot
     *         say what went wrong gives the reader nothing to act on
     */
    public ErroredBenchmark {
        Objects.requireNonNull(benchmarkName, "benchmarkName");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(detail, "detail");
        if (message.isBlank()) {
            throw new IllegalArgumentException("an errored benchmark needs a message");
        }
        parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
    }

    /**
     * The benchmark method's simple name.
     *
     * @return the text after the last dot of {@link #benchmarkName()}
     */
    public String methodName() {
        int lastDot = benchmarkName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < benchmarkName.length() - 1
            ? benchmarkName.substring(lastDot + 1)
            : benchmarkName;
    }

    /**
     * The report category, derived exactly as it is for measured results.
     *
     * @return the benchmark class name without its {@code Benchmark} suffix
     */
    public String category() {
        return BenchmarkResultSet.categoryOf(benchmarkName);
    }

    /**
     * The method name and parameters, which is what distinguishes one combination
     * from its neighbours in a report.
     *
     * @return e.g. {@code sequentialRead_bytes [bufferSize=131072, fileSize=65536]}
     */
    public String combination() {
        if (parameters.isEmpty()) {
            return methodName();
        }
        StringJoiner joiner = new StringJoiner(", ", methodName() + " [", "]");
        parameters.forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString();
    }

    /**
     * The combination together with its mode.
     *
     * @return e.g. {@code sequentialRead_bytes [bufferSize=131072] (thrpt)}
     */
    public String label() {
        return combination() + " (" + mode + ")";
    }

    /**
     * Renders this entry as a JSON object, indented for the result set's
     * {@code errored} array.
     *
     * @return the JSON object text
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"benchmark\": ").append(ReportJson.quote(benchmarkName)).append(",\n");
        sb.append("      \"method\": ").append(ReportJson.quote(methodName())).append(",\n");
        sb.append("      \"mode\": ").append(ReportJson.quote(mode)).append(",\n");
        sb.append("      \"parameters\": {");
        StringJoiner params = new StringJoiner(", ");
        parameters.forEach((key, value) ->
            params.add(ReportJson.quote(key) + ": " + ReportJson.quote(value)));
        sb.append(params).append("},\n");
        sb.append("      \"message\": ").append(ReportJson.quote(message)).append(",\n");
        sb.append("      \"detail\": ").append(ReportJson.quote(detail)).append("\n");
        sb.append("    }");
        return sb.toString();
    }

    /**
     * Reads the errored combinations back out of a report written by
     * {@link BenchmarkResultSet#toJson()}.
     *
     * <p>A document without a top-level {@code errored} array - a JMH result file, or a
     * report written before errors were recorded - holds none. A present but malformed
     * one is rejected rather than read as empty, since that would silently turn a failed
     * run into a clean one when it is compared later.</p>
     *
     * @param json the report text
     * @return the errored combinations, in report order
     * @throws IllegalArgumentException if the document or an entry is malformed
     */
    public static List<ErroredBenchmark> parseReport(String json) {
        Object document = ReportJson.parse(json);
        if (!(document instanceof Map<?, ?> report) || !report.containsKey("errored")) {
            return List.of();
        }
        if (!(report.get("errored") instanceof List<?> entries)) {
            throw new IllegalArgumentException("the report's \"errored\" field is not an array");
        }
        List<ErroredBenchmark> errored = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("an \"errored\" entry is not an object");
            }
            errored.add(fromFields(fields));
        }
        return List.copyOf(errored);
    }

    private static ErroredBenchmark fromFields(Map<?, ?> fields) {
        Map<String, String> parameters = new TreeMap<>();
        Object rawParameters = fields.get("parameters");
        if (rawParameters instanceof Map<?, ?> map) {
            map.forEach((key, value) -> parameters.put(String.valueOf(key), String.valueOf(value)));
        } else if (rawParameters != null) {
            throw new IllegalArgumentException("an \"errored\" entry's parameters are not an object");
        }
        Object detail = fields.get("detail");
        return new ErroredBenchmark(
            requiredString(fields, "benchmark"),
            requiredString(fields, "mode"),
            parameters,
            requiredString(fields, "message"),
            detail instanceof String text ? text : "");
    }

    private static String requiredString(Map<?, ?> fields, String name) {
        if (fields.get(name) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException(
            "an \"errored\" entry has no string field \"" + name + "\"");
    }
}
