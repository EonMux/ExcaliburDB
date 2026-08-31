package excalibur.bench.metric;

import excalibur.bench.benchmark.CuckooFilterBenchmark;
import excalibur.bench.benchmark.CuckooFilterDeleteBenchmark;
import excalibur.bench.benchmark.CuckooFilterFPRateBenchmark;
import excalibur.bench.benchmark.CuckooFilterInsertBenchmark;
import excalibur.bench.benchmark.CuckooFilterMemoryMappedBenchmark;
import excalibur.bench.benchmark.DistanceBenchmark;
import excalibur.bench.benchmark.DistanceVectorApiBenchmark;
import excalibur.bench.benchmark.EndToEndQueryBenchmark;
import excalibur.bench.benchmark.HashBenchmark;
import excalibur.bench.benchmark.HnswRecallBenchmark;
import excalibur.bench.benchmark.MappedFileBenchmark;
import excalibur.bench.benchmark.PointLookupBenchmark;
import excalibur.bench.benchmark.SegmentBenchmark;
import excalibur.bench.benchmark.TailScanBenchmark;
import excalibur.bench.benchmark.VectorLayoutBenchmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the target registry against the benchmarks that actually exist.
 *
 * <p>Every assertion here is derived independently of the registry: the benchmark
 * names come from reflecting over the {@code @Benchmark} methods in this module, and
 * the expected measurement unit comes from each class's {@code @BenchmarkMode} and
 * {@code @OutputTimeUnit}. That makes the tests capable of catching the two ways the
 * registry silently stopped working:</p>
 * <ul>
 *   <li>a pattern that matches no benchmark at all (the p50/p99 targets matched
 *       nothing and were therefore never evaluated);</li>
 *   <li>two patterns matching the same benchmark on the same metric, which made
 *       target selection depend on {@code HashMap} iteration order.</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
class BenchmarkTargetRegistryTest {

    /**
     * Every benchmark class in this module. Kept explicit so a new benchmark class has
     * to be added here deliberately rather than being silently skipped by a classpath
     * scan that finds nothing.
     */
    private static final List<Class<?>> BENCHMARK_CLASSES = List.of(
        CuckooFilterBenchmark.class,
        CuckooFilterDeleteBenchmark.class,
        CuckooFilterFPRateBenchmark.class,
        CuckooFilterInsertBenchmark.class,
        CuckooFilterMemoryMappedBenchmark.class,
        DistanceBenchmark.class,
        DistanceVectorApiBenchmark.class,
        EndToEndQueryBenchmark.class,
        HashBenchmark.class,
        HnswRecallBenchmark.class,
        MappedFileBenchmark.class,
        PointLookupBenchmark.class,
        SegmentBenchmark.class,
        TailScanBenchmark.class,
        VectorLayoutBenchmark.class
    );

    @Test
    @DisplayName("Reflection finds the benchmark methods the registry is written against")
    void benchmarkNamesAreDiscoverable() {
        assertThat(benchmarkNames())
            .hasSizeGreaterThan(80)
            .contains("excalibur.bench.benchmark.PointLookupBenchmark.sequentialLookupFast")
            .contains("excalibur.bench.benchmark.EndToEndQueryBenchmark.fullQueryPipeline");
    }

    @Test
    @DisplayName("Every registered target pattern matches at least one real benchmark")
    void everyTargetPatternMatchesARealBenchmark() {
        List<String> names = benchmarkNames();
        List<String> deadPatterns = new ArrayList<>();

        for (BenchmarkTargetRegistry.TargetConfig target : BenchmarkTargetRegistry.allTargets()) {
            boolean matched = names.stream().anyMatch(target::matches);
            if (!matched) {
                deadPatterns.add(target.pattern() + " [" + target.metric() + "]");
            }
        }

        assertThat(deadPatterns)
            .as("a target that matches no benchmark is never evaluated and silently "
                + "vanishes from every report")
            .isEmpty();
    }

    @Test
    @DisplayName("No benchmark matches two targets on the same metric")
    void targetSelectionIsUnambiguous() {
        for (String name : benchmarkNames()) {
            // getTargets throws on an ambiguous match rather than picking arbitrarily.
            List<BenchmarkTargetRegistry.TargetConfig> targets =
                BenchmarkTargetRegistry.getTargets(name);
            assertThat(targets).isNotNull();
        }
    }

    @Test
    @DisplayName("Target selection is deterministic across repeated lookups")
    void targetSelectionIsDeterministic() {
        for (String name : benchmarkNames()) {
            List<BenchmarkTargetRegistry.TargetConfig> first = BenchmarkTargetRegistry.getTargets(name);
            for (int repeat = 0; repeat < 5; repeat++) {
                assertThat(BenchmarkTargetRegistry.getTargets(name))
                    .containsExactlyElementsOf(first);
            }
        }
    }

    @Test
    @DisplayName("Every primary-score target uses a unit compatible with the benchmark's mode")
    void primaryTargetUnitsMatchTheMeasuredUnit() {
        Map<String, String> mismatches = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : benchmarkNamesToUnits().entrySet()) {
            String name = entry.getKey();
            String measuredUnit = entry.getValue();
            for (BenchmarkTargetRegistry.TargetConfig target : BenchmarkTargetRegistry.getTargets(name)) {
                if (!BenchmarkTargetRegistry.PRIMARY_METRIC.equals(target.metric())) {
                    continue;
                }
                if (target.scaleByParameter() != null) {
                    // The measurement is scaled into the target's dimension first; only
                    // require that what is being scaled really is a throughput.
                    if (MetricUnits.dimensionOf(measuredUnit) != MetricUnits.Dimension.THROUGHPUT) {
                        mismatches.put(name, measuredUnit + " cannot be scaled to " + target.unit());
                    }
                    continue;
                }
                if (!MetricUnits.isCompatible(measuredUnit, target.unit())) {
                    mismatches.put(name, measuredUnit + " vs target unit " + target.unit());
                }
            }
        }

        assertThat(mismatches)
            .as("a target compared against a score in a different dimension produces a "
                + "verdict that has nothing to do with the property being targeted")
            .isEmpty();
    }

    @Test
    @DisplayName("Percentile targets only apply to sampling-mode benchmarks")
    void percentileTargetsOnlyApplyToSamplingBenchmarks() {
        for (Class<?> benchmarkClass : BENCHMARK_CLASSES) {
            Mode mode = modeOf(benchmarkClass);
            for (Method method : benchmarkMethods(benchmarkClass)) {
                String name = benchmarkClass.getName() + "." + method.getName();
                for (BenchmarkTargetRegistry.TargetConfig target : BenchmarkTargetRegistry.getTargets(name)) {
                    if (target.metric().startsWith("p")
                            && !BenchmarkTargetRegistry.PRIMARY_METRIC.equals(target.metric())) {
                        assertThat(mode)
                            .as("%s carries a %s target but does not sample latencies",
                                name, target.metric())
                            .isEqualTo(Mode.SampleTime);
                    }
                }
            }
        }
    }

    // ==================== Helpers ====================

    private static List<String> benchmarkNames() {
        return new ArrayList<>(benchmarkNamesToUnits().keySet());
    }

    /**
     * Maps each benchmark's fully qualified name to the unit JMH will report for it,
     * derived from the class's {@code @BenchmarkMode} and {@code @OutputTimeUnit}.
     */
    private static Map<String, String> benchmarkNamesToUnits() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Class<?> benchmarkClass : BENCHMARK_CLASSES) {
            String unit = expectedUnit(benchmarkClass);
            for (Method method : benchmarkMethods(benchmarkClass)) {
                names.put(benchmarkClass.getName() + "." + method.getName(), unit);
            }
        }
        return names;
    }

    private static List<Method> benchmarkMethods(Class<?> benchmarkClass) {
        List<Method> methods = new ArrayList<>();
        for (Method method : benchmarkClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Benchmark.class)) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static Mode modeOf(Class<?> benchmarkClass) {
        BenchmarkMode annotation = benchmarkClass.getAnnotation(BenchmarkMode.class);
        assertThat(annotation)
            .as("%s must declare @BenchmarkMode", benchmarkClass.getSimpleName())
            .isNotNull();
        return annotation.value()[0];
    }

    private static String expectedUnit(Class<?> benchmarkClass) {
        Mode mode = modeOf(benchmarkClass);
        OutputTimeUnit timeUnit = benchmarkClass.getAnnotation(OutputTimeUnit.class);
        assertThat(timeUnit)
            .as("%s must declare @OutputTimeUnit", benchmarkClass.getSimpleName())
            .isNotNull();
        String suffix = abbreviate(timeUnit.value());
        return switch (mode) {
            case Throughput -> "ops/" + suffix;
            case AverageTime, SampleTime, SingleShotTime -> suffix + "/op";
            default -> throw new IllegalStateException("Unsupported mode " + mode);
        };
    }

    private static String abbreviate(TimeUnit unit) {
        return switch (unit) {
            case NANOSECONDS -> "ns";
            case MICROSECONDS -> "us";
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            default -> throw new IllegalStateException("Unsupported time unit " + unit);
        };
    }
}
