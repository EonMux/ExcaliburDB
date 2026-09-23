package com.eonmux.excalibur.bench.metric;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.util.Statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures and parses benchmark results from JMH output.
 *
 * <p>This class provides utilities to:</p>
 * <ul>
 *   <li>Parse JMH console output and extract results</li>
 *   <li>Parse JMH JSON output files</li>
 *   <li>Capture throughput metrics (ops/sec)</li>
 *   <li>Capture latency percentiles (p50, p99)</li>
 *   <li>Compare actual results against targets</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Throughput values are positive numbers in ops/sec</li>
 *   <li>Latency values are non-negative, typically in ms or ns</li>
 *   <li>Percentiles: p50 &lt; p90 &lt; p95 &lt; p99</li>
 *   <li>Error margins are non-negative</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is designed for single-threaded use after benchmark completion.
 * All methods are stateless and thread-safe for concurrent reads of results.</p>
 *
 * @author ExcaliburDB
 */
public final class BenchmarkResultCapture {

    // JMH output patterns for console parsing
    private static final Pattern JMH_RESULT_LINE = Pattern.compile(
        "^([a-zA-Z0-9_.]+)\\s+" +           // benchmark name
        "(\\S+)\\s+" +                       // mode
        "(\\d+)\\s+" +                       // cnt (count)
        "([\\d.]+)\\s+" +                    // score
        "±\\s*([\\d.]+)\\s+" +               // error
        "(\\S+)$"                            // units
    );

    private static final Pattern JMH_RESULT_SIMPLE = Pattern.compile(
        "^([a-zA-Z0-9_.]+\\.[a-zA-Z0-9_]+)\\s+" +
        "([\\d.Ee+-]+)\\s+" +
        "±\\s*([\\d.Ee+-]+)\\s+" +
        "(\\S+)$"
    );

    // Pattern for result with parameters
    private static final Pattern JMH_RESULT_WITH_PARAMS = Pattern.compile(
        "^([a-zA-Z0-9_.]+)\\s*\\[([^\\]]+)\\]\\s+" +
        "(\\S+)\\s+" +
        "(\\d+)\\s+" +
        "([\\d.]+)\\s+" +
        "±\\s*([\\d.]+)\\s+" +
        "(\\S+)$"
    );

    // Pattern for percentile output

    /**
     * Parses JMH console output and extracts benchmark results.
     *
     * @param outputLines the lines of JMH console output
     * @return a list of parsed benchmark results
     */
    public static List<BenchmarkResult> parseConsoleOutput(List<String> outputLines) {
        List<BenchmarkResult> results = new ArrayList<>();

        for (String line : outputLines) {
            line = line.trim();

            // Skip empty lines and headers
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("Benchmark")) {
                continue;
            }

            BenchmarkResult result = parseResultLine(line);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Parses JMH console output from a file.
     *
     * @param filePath path to the file containing JMH output
     * @return a list of parsed benchmark results
     * @throws IOException if the file cannot be read
     */
    public static List<BenchmarkResult> parseConsoleOutput(Path filePath) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        return parseConsoleOutput(lines);
    }

    /**
     * Parses a single JMH result line.
     */
    private static BenchmarkResult parseResultLine(String line) {
        // Try pattern with parameters first
        Matcher matcher = JMH_RESULT_WITH_PARAMS.matcher(line);
        if (matcher.matches()) {
            return buildResultFromMatch(
                matcher.group(1),   // benchmark name
                matcher.group(2),   // parameters
                matcher.group(3),   // mode
                matcher.group(5),   // score
                matcher.group(6),   // error
                matcher.group(7)    // units
            );
        }

        // Try simple result pattern
        matcher = JMH_RESULT_SIMPLE.matcher(line);
        if (matcher.matches()) {
            return buildResultFromMatch(
                matcher.group(1),   // benchmark name
                null,               // no parameters
                "thrpt",            // default mode
                matcher.group(2),   // score
                matcher.group(3),   // error
                matcher.group(4)    // units
            );
        }

        // Try full JMH format
        matcher = JMH_RESULT_LINE.matcher(line);
        if (matcher.matches()) {
            return buildResultFromMatch(
                matcher.group(1),   // benchmark name
                null,               // no parameters
                matcher.group(2),   // mode
                matcher.group(4),   // score
                matcher.group(5),   // error
                matcher.group(6)    // units
            );
        }

        return null;
    }

    /**
     * Builds a BenchmarkResult from regex match groups.
     */
    private static BenchmarkResult buildResultFromMatch(
            String benchmarkName, String paramsStr, String mode,
            String scoreStr, String errorStr, String units) {

        double score = parseDouble(scoreStr);
        double error = parseDouble(errorStr);

        // Parse parameters if present
        Map<String, String> params = new HashMap<>();
        if (paramsStr != null && !paramsStr.isEmpty()) {
            for (String param : paramsStr.split(",")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // Determine if higher is better from the measured unit. The registry must not
        // override this: its direction belongs to the target's own unit, which may be a
        // different dimension entirely.
        boolean higherIsBetter = isHigherBetter(units, mode);

        // Build result
        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName(benchmarkName)
            .mode(mode)
            .timestamp(Instant.now())
            .parameters(params)
            .primaryScore(score)
            .primaryScoreError(error)
            .primaryScoreUnit(units)
            .higherIsBetter(higherIsBetter);

        attachThroughputMetric(builder, score, error, units);
        attachRegisteredTargets(builder, benchmarkName);

        return builder.build();
    }

    /**
     * Attaches a throughput metric when the measurement really is a throughput.
     *
     * <p>The check goes through {@link MetricUnits} rather than substring matching:
     * JMH writes {@code ops/s}, which contains neither "ops/sec" nor "op/s".</p>
     */
    private static void attachThroughputMetric(
            BenchmarkResult.Builder builder, double score, double error, String units) {
        if (MetricUnits.dimensionOf(units) != MetricUnits.Dimension.THROUGHPUT) {
            return;
        }
        double opsPerSec = MetricUnits.convert(score, units, MetricUnits.OPS_PER_SEC);
        if (!Double.isFinite(opsPerSec) || opsPerSec <= 0) {
            return;
        }
        double errorOpsPerSec = Double.isFinite(error)
            ? MetricUnits.convert(Math.max(error, 0.0), units, MetricUnits.OPS_PER_SEC)
            : 0.0;
        builder.addMetric(BenchmarkMetric.throughput(opsPerSec, errorOpsPerSec));
    }

    /**
     * Attaches every target the registry defines for this benchmark.
     */
    private static void attachRegisteredTargets(BenchmarkResult.Builder builder, String benchmarkName) {
        for (BenchmarkTargetRegistry.TargetConfig target : BenchmarkTargetRegistry.getTargets(benchmarkName)) {
            builder.addTarget(target);
        }
    }

    /**
     * Parses JMH JSON output file.
     *
     * <p>JMH JSON format contains an array of result objects with fields:
     * benchmark, mode, primaryMetric.score, primaryMetric.scoreError, etc.</p>
     *
     * @param jsonPath path to the JMH JSON output file
     * @return a list of parsed benchmark results
     * @throws IOException if the file cannot be read
     */
    public static List<BenchmarkResult> parseJsonOutput(Path jsonPath) throws IOException {
        String content = Files.readString(jsonPath);
        return parseJsonContent(content);
    }

    /**
     * Parses JMH JSON content.
     */
    public static List<BenchmarkResult> parseJsonContent(String jsonContent) {
        List<BenchmarkResult> results = new ArrayList<>();

        // This tool's report lists every result twice: grouped under "categories" and again
        // flat under "results". Sweeping the whole document would return each one twice, and a
        // comparison against a doubled baseline is not a comparison. When the canonical flat
        // list is there, it is the whole answer.
        int flatList = topLevelIndexOf(jsonContent, "results");
        if (flatList >= 0) {
            int open = jsonContent.indexOf('[', flatList);
            int close = open < 0 ? -1 : findMatchingBracket(jsonContent, open);
            if (close > open) {
                collectResults(jsonContent, open + 1, close, results);
                return results;
            }
        }

        collectResults(jsonContent, 0, jsonContent.length(), results);
        return results;
    }

    /**
     * The index of the {@code ]} closing the array opened at {@code openPos}.
     *
     * @param s       The document
     * @param openPos Index of the opening bracket
     * @return The matching bracket's index, or -1
     */
    private static int findMatchingBracket(String s, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Collects every benchmark object in a region, at whatever depth it sits.
     *
     * <p>JMH writes a flat array of result objects; this tool's own report nests them inside
     * {@code categories}. Scanning only the outermost objects therefore matched the whole report
     * as a single object, found the first nested {@code "benchmark"} string inside it, and
     * skipped past everything else -- one result recovered from a file holding dozens.
     *
     * <p>An object that is not itself a result is descended into rather than discarded, which is
     * what makes both shapes work without the parser having to know which it is reading.
     */
    private static void collectResults(String json, int from, int to, List<BenchmarkResult> out) {
        int idx = from;
        while (idx < to) {
            int objStart = json.indexOf('{', idx);
            if (objStart < 0 || objStart >= to) {
                return;
            }
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0 || objEnd >= to) {
                return;
            }

            BenchmarkResult result = parseJsonObject(json.substring(objStart, objEnd + 1));
            if (result != null) {
                out.add(result);
            } else {
                collectResults(json, objStart + 1, objEnd, out);
            }
            idx = objEnd + 1;
        }
    }

    /**
     * Parses a single JSON object representing a benchmark result.
     */
    private static BenchmarkResult parseJsonObject(String json) {
        // Top level only. Searching the whole substring made an enclosing report look like a
        // result, because one of the results nested inside it has a "benchmark" key.
        if (topLevelIndexOf(json, "benchmark") < 0) {
            return null;
        }
        String benchmark = extractJsonString(json, "benchmark");
        if (benchmark == null) return null;

        String mode = extractJsonString(json, "mode");
        if (mode == null) mode = "thrpt";

        // Two shapes reach this parser. JMH writes "primaryMetric": {score, scoreError,
        // scoreUnit}; this tool's own reports write "score": {value, error, unit}. It used to
        // read only the first, so loading a baseline this tool had itself written produced zero
        // results -- and a comparison against zero baselines reports every benchmark as NEW
        // rather than regressed, so the regression gate passed whatever the numbers did.
        String metricJson = objectFieldOf(json, "primaryMetric");
        boolean jmh = metricJson != null;
        if (!jmh) {
            metricJson = objectFieldOf(json, "score");
        }
        if (metricJson == null) {
            return null;
        }

        double score = extractJsonDouble(metricJson, jmh ? "score" : "value");
        double error = extractJsonDouble(metricJson, jmh ? "scoreError" : "error");
        String units = extractJsonString(metricJson, jmh ? "scoreUnit" : "unit");
        if (units == null) units = "ops/sec";

        // JMH names the @Param values "params"; this tool's reports name them "parameters".
        // Reading only the first dropped every report's parameters on reload, so each
        // combination of a benchmark looked like the same measurement to a comparison.
        Map<String, String> params = new HashMap<>();
        String paramsJson = objectFieldOf(json, "params");
        if (paramsJson == null) {
            paramsJson = objectFieldOf(json, "parameters");
        }
        if (paramsJson != null) {
            extractParamsFromJson(paramsJson, params);
        }

        // Determine if higher is better from the measured unit (never from the target).
        boolean higherIsBetter = isHigherBetter(units, mode);

        // Build result
        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName(benchmark)
            .mode(mode)
            .timestamp(Instant.now())
            .parameters(params)
            .primaryScore(score)
            .primaryScoreError(error)
            .primaryScoreUnit(units)
            .higherIsBetter(higherIsBetter);

        attachThroughputMetric(builder, score, error, units);
        attachRegisteredTargets(builder, benchmark);

        return builder.build();
    }

    /**
     * The text of a named object field, or null when the field is absent or malformed.
     *
     * @param json  The enclosing object
     * @param field The field name
     * @return The field's object text, braces included
     */
    private static String objectFieldOf(String json, String field) {
        int at = topLevelIndexOf(json, field);
        if (at < 0) {
            return null;
        }
        int open = json.indexOf("{", at);
        if (open < 0) {
            return null;
        }
        int close = findMatchingBrace(json, open);
        if (close < 0) {
            return null;
        }
        return json.substring(open, close + 1);
    }

    /**
     * The position of a key in an object, ignoring keys of any object or array nested inside it.
     *
     * @param json An object, braces included
     * @param key  The key name, without quotes
     * @return The index of the key's opening quote, or -1
     */
    private static int topLevelIndexOf(String json, String key) {
        String quoted = "\"" + key + "\"";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString && depth == 1 && json.startsWith(quoted, i)) {
                    return i;
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return -1;
    }

    /**
     * Extracts parameters from a JSON object.
     */
    private static void extractParamsFromJson(String json, Map<String, String> params) {
        int idx = 0;
        while (idx < json.length()) {
            int keyStart = json.indexOf("\"", idx);
            if (keyStart < 0) break;

            int keyEnd = json.indexOf("\"", keyStart + 1);
            if (keyEnd < 0) break;

            String key = json.substring(keyStart + 1, keyEnd);

            int colonPos = json.indexOf(":", keyEnd);
            if (colonPos < 0) break;

            // Find value
            int valueStart = -1;
            for (int i = colonPos + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') {
                    // String value
                    valueStart = i;
                    int valueEnd = json.indexOf("\"", valueStart + 1);
                    if (valueEnd > 0) {
                        params.put(key, json.substring(valueStart + 1, valueEnd));
                        idx = valueEnd + 1;
                    }
                    break;
                } else if (Character.isDigit(c) || c == '-') {
                    // Numeric value
                    valueStart = i;
                    int valueEnd = valueStart;
                    while (valueEnd < json.length() &&
                           (Character.isDigit(json.charAt(valueEnd)) ||
                            json.charAt(valueEnd) == '.' ||
                            json.charAt(valueEnd) == '-' ||
                            json.charAt(valueEnd) == 'E' ||
                            json.charAt(valueEnd) == 'e')) {
                        valueEnd++;
                    }
                    params.put(key, json.substring(valueStart, valueEnd));
                    idx = valueEnd;
                    break;
                } else if (!Character.isWhitespace(c) && c != ',') {
                    break;
                }
            }

            if (valueStart < 0) break;
        }
    }

    /**
     * Extracts a string value from JSON.
     */
    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos < 0) return null;

        int colonPos = json.indexOf(":", keyPos + searchKey.length());
        if (colonPos < 0) return null;

        int valueStart = json.indexOf("\"", colonPos);
        if (valueStart < 0) return null;

        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd < 0) return null;

        return json.substring(valueStart + 1, valueEnd);
    }

    /**
     * Extracts a double value from JSON.
     */
    private static double extractJsonDouble(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyPos = json.indexOf(searchKey);
        if (keyPos < 0) return 0.0;

        int colonPos = json.indexOf(":", keyPos + searchKey.length());
        if (colonPos < 0) return 0.0;

        // Find start of number
        int valueStart = -1;
        for (int i = colonPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-' || c == '.') {
                valueStart = i;
                break;
            } else if (c == 'n' && json.substring(i).startsWith("null")) {
                return 0.0;
            } else if (!Character.isWhitespace(c)) {
                break;
            }
        }

        if (valueStart < 0) return 0.0;

        // Find end of number
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (Character.isDigit(c) || c == '.' || c == '-' ||
                c == 'E' || c == 'e' || c == '+' || c == '-') {
                valueEnd++;
            } else {
                break;
            }
        }

        return parseDouble(json.substring(valueStart, valueEnd));
    }

    /**
     * Finds the matching closing brace for an opening brace.
     */
    private static int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Determines if higher values are better, from the unit first and the mode second.
     *
     * <p>The unit is authoritative because it names the physical dimension. The JMH mode
     * is only consulted for units {@link MetricUnits} does not recognise.</p>
     */
    private static boolean isHigherBetter(String units, String mode) {
        MetricUnits.Dimension dimension = MetricUnits.dimensionOf(units);
        if (dimension != MetricUnits.Dimension.UNKNOWN
                && dimension != MetricUnits.Dimension.DIMENSIONLESS) {
            return MetricUnits.higherIsBetter(units);
        }

        String unitsLower = units.toLowerCase(java.util.Locale.ROOT);
        String modeLower = mode.toLowerCase(java.util.Locale.ROOT);

        // Throughput mode: higher is better
        if (modeLower.contains("thrpt") || unitsLower.contains("throughput")) {
            return true;
        }

        // Average time or sample time: lower is better
        if (modeLower.contains("avgt") || modeLower.contains("sample")) {
            return false;
        }

        // Recall: higher is better
        if (unitsLower.contains("recall") || unitsLower.contains("score")) {
            return true;
        }

        // Rate: lower is better (for error rates, FP rates)
        if (unitsLower.contains("rate") || unitsLower.contains("fp")) {
            return false;
        }

        // Default: higher is better
        return true;
    }

    /**
     * Safely parses a double value.
     */
    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Converts the results JMH itself returned into structured benchmark results.
     *
     * <p>This is the authoritative capture path. {@code new Runner(options).run()} hands
     * back every measurement it made, so nothing has to be scraped back out of stdout -
     * the previous approach depended on redirecting {@code System.out}, which the forked
     * benchmark JVM does not write to at all.</p>
     *
     * <p>For sampling modes the primary result's sample distribution is preserved as
     * p0/p50/p90/p99 metrics in milliseconds, which is what the latency targets in
     * {@link BenchmarkTargetRegistry} are keyed on.</p>
     *
     * @param runResults the results returned by JMH
     * @return structured results, one per benchmark
     */
    public static List<BenchmarkResult> fromRunResults(Collection<RunResult> runResults) {
        List<BenchmarkResult> results = new ArrayList<>();
        for (RunResult runResult : runResults) {
            results.add(fromRunResult(runResult));
        }
        return results;
    }

    /**
     * Converts a single JMH {@link RunResult}.
     *
     * @param runResult the JMH result
     * @return the structured benchmark result
     */
    public static BenchmarkResult fromRunResult(RunResult runResult) {
        Result<?> primary = runResult.getPrimaryResult();
        String benchmarkName = runResult.getParams().getBenchmark();
        String mode = runResult.getParams().getMode().shortLabel();
        String units = primary.getScoreUnit();
        double score = primary.getScore();
        double error = primary.getScoreError();
        if (Double.isNaN(error)) {
            error = 0.0;
        }

        Map<String, String> params = new HashMap<>();
        for (String key : runResult.getParams().getParamsKeys()) {
            params.put(key, runResult.getParams().getParam(key));
        }

        BenchmarkResult.Builder builder = BenchmarkResult.builder()
            .benchmarkName(benchmarkName)
            .mode(mode)
            .timestamp(Instant.now())
            .parameters(params)
            .primaryScore(score)
            .primaryScoreError(error)
            .primaryScoreUnit(units)
            .higherIsBetter(isHigherBetter(units, mode))
            .iterations(runResult.getParams().getMeasurement().getCount())
            .forks(runResult.getParams().getForks())
            .durationMs(estimatedDurationMs(runResult));

        attachThroughputMetric(builder, score, error, units);
        attachPercentileMetrics(builder, runResult, units);
        attachRegisteredTargets(builder, benchmarkName);

        return builder.build();
    }

    /**
     * Attaches p0/p50/p90/p99 metrics, in milliseconds, when JMH collected a
     * sample distribution for this benchmark.
     */
    private static void attachPercentileMetrics(
            BenchmarkResult.Builder builder, RunResult runResult, String units) {
        if (runResult.getParams().getMode() != Mode.SampleTime) {
            // Only sampling modes record a per-operation distribution. For other modes
            // the statistics describe per-iteration scores, which are not latencies.
            return;
        }
        if (MetricUnits.dimensionOf(units) != MetricUnits.Dimension.TIME_PER_OP) {
            return;
        }
        Statistics statistics = runResult.getPrimaryResult().getStatistics();
        if (statistics == null || statistics.getN() < 2) {
            return;
        }
        for (int percentile : new int[]{0, 50, 90, 99}) {
            double raw = statistics.getPercentile(percentile);
            if (!Double.isFinite(raw)) {
                return;
            }
            builder.addMetric(new BenchmarkMetric(
                "p" + percentile,
                MetricUnits.convert(raw, units, "ms"),
                "ms",
                0.0,
                statistics.getN()));
        }
    }

    private static long estimatedDurationMs(RunResult runResult) {
        TimeValue warmupTime = runResult.getParams().getWarmup().getTime();
        TimeValue measurementTime = runResult.getParams().getMeasurement().getTime();
        long warmupMs = warmupTime.convertTo(java.util.concurrent.TimeUnit.MILLISECONDS)
            * runResult.getParams().getWarmup().getCount();
        long measurementMs = measurementTime.convertTo(java.util.concurrent.TimeUnit.MILLISECONDS)
            * runResult.getParams().getMeasurement().getCount();
        return (warmupMs + measurementMs) * Math.max(1, runResult.getParams().getForks());
    }

    /**
     * Creates a BenchmarkResultSet from parsed results.
     *
     * @param name name for the result set
     * @param results the benchmark results
     * @return a complete result set
     */
    public static BenchmarkResultSet createResultSet(String name, List<BenchmarkResult> results) {
        return BenchmarkResultSet.builder()
            .name(name)
            .startTime(Instant.now())
            .results(results)
            .build();
    }


    private BenchmarkResultCapture() {
        // Utility class
    }
}
