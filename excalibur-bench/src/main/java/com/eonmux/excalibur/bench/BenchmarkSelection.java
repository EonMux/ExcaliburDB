package com.eonmux.excalibur.bench;

import java.util.List;
import java.util.Locale;

/**
 * Translates the command-line argument of {@link BenchmarkRunner} into the JMH
 * include pattern that decides which benchmarks a run executes.
 *
 * <p>Selection is kept apart from the runner because picking the wrong set of
 * benchmarks is silent: the run still succeeds, still prints results, and still
 * looks like a measurement of the thing that was asked for. Separating it lets the
 * mapping be asserted against the real benchmark classes in milliseconds instead of
 * being discovered after a multi-minute run measured the wrong code.</p>
 */
public final class BenchmarkSelection {

    /** Include pattern matching every benchmark in the module. */
    public static final String ALL_BENCHMARKS = ".*Benchmark.*";

    /** Selector keywords accepted on the command line, in the order they are tried. */
    private static final List<String> SELECTORS = List.of("segment", "cuckoo");

    private BenchmarkSelection() {
        throw new AssertionError("Utility class");
    }

    /**
     * Resolves the JMH include pattern for a set of command-line arguments.
     *
     * @param args The runner's arguments; only the first is a selector
     * @return The JMH include pattern to run
     * @throws IllegalArgumentException if a selector was given but names nothing
     */
    public static String patternFor(String[] args) {
        if (args == null || args.length == 0) {
            return ALL_BENCHMARKS;
        }
        String selector = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT).trim();
        if (selector.contains("segment")) {
            return ".*SegmentBenchmark.*";
        }
        if (selector.contains("cuckoo")) {
            // "insert" narrows the family to one benchmark; the bare family is the
            // fallback so that an unqualified "cuckoo" still means all of them.
            return selector.contains("insert")
                ? ".*CuckooFilterInsertBenchmark.*"
                : ".*CuckooFilter.*Benchmark.*";
        }
        throw new IllegalArgumentException(
            "Unknown benchmark selector '" + args[0] + "'. Known selectors: "
                + String.join(", ", SELECTORS)
                + ". Omit the argument to run every benchmark.");
    }
}
