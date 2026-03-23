package excalibur.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;

/**
 * Quick JMH benchmark runner for validation targets.
 *
 * <p>Runs minimal JMH benchmarks to validate performance against targets:</p>
 * <ul>
 *   <li>Storage: ≥1B point lookups/sec</li>
 *   <li>Cuckoo filter: FP rate ≤0.1%</li>
 *   <li>HNSW: ≥99% recall@10</li>
 * </ul>
 */
public class QuickJmhBenchmark {

    public static void main(String[] args) throws RunnerException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     ExcaliburDB Quick JMH Benchmark Validation               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration: Minimal (quick validation)");
        System.out.println("  Warmup: 1 iteration x 1 second");
        System.out.println("  Measurement: 2 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();

        Options options = new OptionsBuilder()
                .include(".*CuckooFilterBenchmark.lookupHit.*")
                .include(".*CuckooFilterBenchmark.lookupMiss.*")
                .include(".*SegmentBenchmark.randomLookup.*")
                .include(".*SegmentBenchmark.batchLookup.*")
                .include(".*PointLookupBenchmark.sequentialLookupFast.*")
                .include(".*HnswRecallBenchmark.measureRecall.*")
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(2)
                .measurementTime(TimeValue.seconds(1))
                .forks(1)
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "-Xms4g",
                    "-Xmx4g"
                )
                .shouldDoGC(true)
                .build();

        new Runner(options).run();
    }
}
