package com.eonmux.excalibur.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.eonmux.excalibur.bench.benchmark.CuckooFilterBenchmark;
import com.eonmux.excalibur.bench.benchmark.CuckooFilterDeleteBenchmark;
import com.eonmux.excalibur.bench.benchmark.CuckooFilterFPRateBenchmark;
import com.eonmux.excalibur.bench.benchmark.CuckooFilterInsertBenchmark;
import com.eonmux.excalibur.bench.benchmark.CuckooFilterMemoryMappedBenchmark;
import com.eonmux.excalibur.bench.benchmark.DistanceBenchmark;
import com.eonmux.excalibur.bench.benchmark.HnswRecallBenchmark;
import com.eonmux.excalibur.bench.benchmark.SegmentBenchmark;

/**
 * Tests the command-line selector that decides which benchmarks a run executes.
 *
 * <p>The assertions resolve each pattern against the real benchmark classes rather
 * than against the pattern text, so a benchmark that is renamed or a selector that
 * stops matching what it names fails here in milliseconds instead of being
 * discovered by a benchmark run that quietly measures the wrong thing.</p>
 */
@DisplayName("Benchmark selection from command-line arguments")
class BenchmarkSelectionTest {

    /** Every benchmark class the selector can be asked to pick out. */
    private static final List<Class<?>> ALL_BENCHMARKS = List.of(
        SegmentBenchmark.class,
        CuckooFilterBenchmark.class,
        CuckooFilterInsertBenchmark.class,
        CuckooFilterDeleteBenchmark.class,
        CuckooFilterFPRateBenchmark.class,
        CuckooFilterMemoryMappedBenchmark.class,
        DistanceBenchmark.class,
        HnswRecallBenchmark.class);

    @Test
    @DisplayName("no argument selects every benchmark in the module")
    void noArgumentSelectsEverything() {
        assertThat(selected(BenchmarkSelection.patternFor(new String[0])))
            .containsExactlyInAnyOrderElementsOf(names(ALL_BENCHMARKS));
    }

    @Test
    @DisplayName("the segment selector picks the segment benchmark and nothing else")
    void segmentSelectorIsExact() {
        assertThat(selected(BenchmarkSelection.patternFor(new String[]{"segment"})))
            .containsExactly(SegmentBenchmark.class.getSimpleName());
    }

    @Test
    @DisplayName("the cuckoo selector picks the whole cuckoo family")
    void cuckooSelectorPicksTheFamily() {
        assertThat(selected(BenchmarkSelection.patternFor(new String[]{"cuckoo"})))
            .containsExactlyInAnyOrder(
                CuckooFilterBenchmark.class.getSimpleName(),
                CuckooFilterInsertBenchmark.class.getSimpleName(),
                CuckooFilterDeleteBenchmark.class.getSimpleName(),
                CuckooFilterFPRateBenchmark.class.getSimpleName(),
                CuckooFilterMemoryMappedBenchmark.class.getSimpleName());
    }

    @Test
    @DisplayName("naming insert narrows the cuckoo family to the insert benchmark alone")
    void cuckooInsertNarrowsToOneBenchmark() {
        assertThat(selected(BenchmarkSelection.patternFor(new String[]{"CuckooFilterInsert"})))
            .containsExactly(CuckooFilterInsertBenchmark.class.getSimpleName());
    }

    @Test
    @DisplayName("selectors are matched without regard to case")
    void selectorsAreCaseInsensitive() {
        assertThat(BenchmarkSelection.patternFor(new String[]{"SEGMENT"}))
            .isEqualTo(BenchmarkSelection.patternFor(new String[]{"segment"}));
    }

    @Test
    @DisplayName("an unrecognised selector is refused rather than silently running everything")
    void unrecognisedSelectorIsRefused() {
        assertThatThrownBy(() -> BenchmarkSelection.patternFor(new String[]{"hnsw"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hnsw")
            .hasMessageContaining("segment");
    }

    @Test
    @DisplayName("a mistyped selector is refused, not rounded to the nearest match")
    void mistypedSelectorIsRefused() {
        assertThatThrownBy(() -> BenchmarkSelection.patternFor(new String[]{"segmnet"}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank selector is refused rather than treated as absent")
    void blankSelectorIsRefused() {
        assertThatThrownBy(() -> BenchmarkSelection.patternFor(new String[]{"   "}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Resolves a JMH include pattern against the real benchmark classes. */
    private static List<String> selected(String pattern) {
        Pattern compiled = Pattern.compile(pattern);
        return ALL_BENCHMARKS.stream()
            .filter(type -> compiled.matcher(type.getName() + ".someBenchmarkMethod").matches())
            .map(Class::getSimpleName)
            .collect(Collectors.toList());
    }

    private static List<String> names(List<Class<?>> types) {
        return types.stream().map(Class::getSimpleName).collect(Collectors.toList());
    }
}
