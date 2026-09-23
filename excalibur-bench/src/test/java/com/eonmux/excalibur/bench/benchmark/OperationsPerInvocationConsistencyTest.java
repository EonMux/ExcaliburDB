package com.eonmux.excalibur.bench.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that every benchmark whose body repeats one operation a fixed number of times reports
 * per operation.
 *
 * <p>A method that makes a hundred distance computations per call and does not declare
 * {@code @OperationsPerInvocation(100)} reports a hundredth of the rate its name describes, and
 * nothing in the output says so: the score is simply a batch rate labelled ops/s. Targets and
 * the figures in {@code BENCHMARK_TARGETS.md} are per operation, so the count a loop repeats and
 * the count the annotation declares have to be the same number.</p>
 *
 * <p>Read from the sources rather than from the compiled classes, because the loop bound is
 * only visible there. The test runs with the module directory as its working directory.</p>
 *
 * @author ExcaliburDB
 */
class OperationsPerInvocationConsistencyTest {

    private static final Path BENCHMARK_SOURCES =
        Path.of("src/main/java/com/eonmux/excalibur/bench/benchmark");

    private static final Pattern BENCHMARK_METHOD = Pattern.compile(
        "@Benchmark\\s*\\n((?:\\s*@[^\\n]*\\n)*)\\s*public [^(]*?(\\w+)\\(");

    private static final Pattern OPERATIONS = Pattern.compile(
        "@OperationsPerInvocation\\((\\d+)\\)");

    private static final Pattern FIXED_LOOP = Pattern.compile(
        "^\\s{8}for \\(int i = 0; i < (\\d+); i\\+\\+\\)", Pattern.MULTILINE);

    @Test
    @DisplayName("a benchmark that loops a fixed count declares that count as operations per call")
    void fixedCountLoopsDeclareTheirCount() throws IOException {
        List<String> mismatches = new ArrayList<>();
        int loopingBenchmarks = 0;

        try (Stream<Path> files = Files.list(BENCHMARK_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith("Benchmark.java")).toList()) {
                String source = Files.readString(file);
                Matcher method = BENCHMARK_METHOD.matcher(source);
                List<Integer> starts = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<String> annotations = new ArrayList<>();
                while (method.find()) {
                    starts.add(method.end());
                    names.add(method.group(2));
                    annotations.add(method.group(1));
                }
                for (int i = 0; i < starts.size(); i++) {
                    int end = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
                    String body = methodBody(source, starts.get(i), end);
                    Matcher loop = FIXED_LOOP.matcher(body);
                    if (!loop.find()) {
                        continue;
                    }
                    loopingBenchmarks++;
                    Matcher declared = OPERATIONS.matcher(annotations.get(i));
                    String expected = loop.group(1);
                    String actual = declared.find() ? declared.group(1) : "none";
                    if (!expected.equals(actual)) {
                        mismatches.add(file.getFileName() + " " + names.get(i)
                            + ": loops " + expected + ", declares " + actual);
                    }
                }
            }
        }

        assertThat(loopingBenchmarks)
            .as("the scan must find the fixed-count benchmarks, or it checks nothing")
            .isGreaterThan(20);
        assertThat(mismatches)
            .as("a loop count without a matching @OperationsPerInvocation reports a batch rate "
                + "as a per-operation rate")
            .isEmpty();
    }

    /**
     * The text of one method's body: from its signature to its closing brace, bounded by the
     * next benchmark method.
     */
    private static String methodBody(String source, int from, int limit) {
        int open = source.indexOf('{', from);
        if (open < 0 || open >= limit) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < limit; i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        return source.substring(open, limit);
    }
}
