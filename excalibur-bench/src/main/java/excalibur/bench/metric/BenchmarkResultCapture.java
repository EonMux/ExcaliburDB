package excalibur.bench.metric;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final Pattern PERCENTILE_PATTERN = Pattern.compile(
        "p(\\d+)\\s*:\\s*([\\d.]+)\\s*(\\S+)?"
    );

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

        // Get target from registry
        BenchmarkTargetRegistry.TargetConfig targetConfig =
            BenchmarkTargetRegistry.getTarget(benchmarkName);

        // Determine if higher is better based on units
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

        // Add target if defined
        if (targetConfig != null) {
            builder.target(targetConfig.targetValue())
                   .higherIsBetter(targetConfig.higherIsBetter());
        }

        // Add throughput metric
        if (units.contains("ops/sec") || units.contains("op/s")) {
            builder.addMetric(BenchmarkMetric.throughput(score, error));
        }

        return builder.build();
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

        // Simple JSON parsing without external dependencies
        // JMH JSON format is an array of benchmark result objects

        // Find all benchmark objects
        int idx = 0;
        while (idx < jsonContent.length()) {
            int objStart = jsonContent.indexOf("{", idx);
            if (objStart < 0) break;

            int objEnd = findMatchingBrace(jsonContent, objStart);
            if (objEnd < 0) break;

            String objContent = jsonContent.substring(objStart, objEnd + 1);
            BenchmarkResult result = parseJsonObject(objContent);
            if (result != null) {
                results.add(result);
            }

            idx = objEnd + 1;
        }

        return results;
    }

    /**
     * Parses a single JSON object representing a benchmark result.
     */
    private static BenchmarkResult parseJsonObject(String json) {
        String benchmark = extractJsonString(json, "benchmark");
        if (benchmark == null) return null;

        String mode = extractJsonString(json, "mode");
        if (mode == null) mode = "thrpt";

        // Extract primaryMetric
        int metricStart = json.indexOf("\"primaryMetric\"");
        if (metricStart < 0) return null;

        int metricObjStart = json.indexOf("{", metricStart);
        int metricObjEnd = findMatchingBrace(json, metricObjStart);
        if (metricObjEnd < 0) return null;

        String metricJson = json.substring(metricObjStart, metricObjEnd + 1);

        double score = extractJsonDouble(metricJson, "score");
        double error = extractJsonDouble(metricJson, "scoreError");
        String units = extractJsonString(metricJson, "scoreUnit");
        if (units == null) units = "ops/sec";

        // Extract parameters
        Map<String, String> params = new HashMap<>();
        int paramsStart = json.indexOf("\"params\"");
        if (paramsStart > 0) {
            int paramsObjStart = json.indexOf("{", paramsStart);
            int paramsObjEnd = findMatchingBrace(json, paramsObjStart);
            if (paramsObjEnd > 0) {
                String paramsJson = json.substring(paramsObjStart, paramsObjEnd + 1);
                // Extract key-value pairs
                extractParamsFromJson(paramsJson, params);
            }
        }

        // Get target from registry
        BenchmarkTargetRegistry.TargetConfig targetConfig =
            BenchmarkTargetRegistry.getTarget(benchmark);

        // Determine if higher is better based on units
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

        if (targetConfig != null) {
            builder.target(targetConfig.targetValue())
                   .higherIsBetter(targetConfig.higherIsBetter());
        }

        // Add throughput metric
        if (units.contains("ops/sec") || units.contains("op/s")) {
            builder.addMetric(BenchmarkMetric.throughput(score, error));
        }

        return builder.build();
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
     * Determines if higher values are better based on units and mode.
     */
    private static boolean isHigherBetter(String units, String mode) {
        String unitsLower = units.toLowerCase();
        String modeLower = mode.toLowerCase();

        // Throughput mode: higher is better
        if (modeLower.contains("thrpt") || unitsLower.contains("ops/sec") ||
            unitsLower.contains("op/s") || unitsLower.contains("throughput")) {
            return true;
        }

        // Average time or sample time: lower is better
        if (modeLower.contains("avgt") || modeLower.contains("sample") ||
            unitsLower.contains("ms") || unitsLower.contains("ns") ||
            unitsLower.contains("s/op") || unitsLower.contains("ms/op")) {
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

    /**
     * Captures latency percentiles from JMH sample time output.
     *
     * <p>JMH sample time mode outputs percentiles in the format:
     * p0.00: 123.456 ns
     * p50.00: 234.567 ns
     * p99.00: 345.678 ns
     * </p>
     *
     * @param lines JMH output lines
     * @return map of percentile name to value in milliseconds
     */
    public static Map<String, Double> extractPercentiles(List<String> lines) {
        Map<String, Double> percentiles = new HashMap<>();

        for (String line : lines) {
            Matcher matcher = PERCENTILE_PATTERN.matcher(line);
            if (matcher.find()) {
                String percentile = "p" + matcher.group(1);
                double value = parseDouble(matcher.group(2));
                String unit = matcher.group(3);

                // Convert to milliseconds if needed
                if (unit != null && unit.toLowerCase().contains("ns")) {
                    value = value / 1_000_000.0;
                } else if (unit != null && unit.toLowerCase().contains("us")) {
                    value = value / 1_000.0;
                } else if (unit != null && unit.toLowerCase().contains("s") &&
                           !unit.toLowerCase().contains("ms")) {
                    value = value * 1_000.0;
                }

                percentiles.put(percentile, value);
            }
        }

        return percentiles;
    }

    private BenchmarkResultCapture() {
        // Utility class
    }
}
