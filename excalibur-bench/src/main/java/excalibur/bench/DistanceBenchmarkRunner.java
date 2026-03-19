package excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Vector Distance Functions.
 *
 * <p>Runs benchmarks for Cosine Similarity, L2 Distance, and Inner Product
 * computations using both scalar and Vector API implementations.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all distance benchmarks
 * ./gradlew :excalibur-bench:runDistanceBenchmark
 *
 * # Or run directly
 * java -jar excalibur-bench.jar distance
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Single distance computation: >=10M ops/sec</li>
 *   <li>Batch distance computation: >=100M ops/sec</li>
 *   <li>Nearest neighbor search (100 candidates): >=100K searches/sec</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Vector dimensions tested: 64, 128, 256, 512, 768, 1536</li>
 *   <li>Cosine similarity results in range [-1.0, 1.0]</li>
 *   <li>L2 distance results >= 0.0</li>
 *   <li>Inner product can be any float value</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
public class DistanceBenchmarkRunner {

    /**
     * Target throughput for single distance computations (10 million ops/sec).
     */
    public static final double TARGET_SINGLE_OPS_PER_SEC = 10_000_000.0;

    /**
     * Target throughput for batch distance computations (100 million ops/sec).
     */
    public static final double TARGET_BATCH_OPS_PER_SEC = 100_000_000.0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Distance Benchmark Runner");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running distance benchmarks with configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println("  Vector dimensions: 64, 128, 256, 512, 768, 1536");
        System.out.println();
        System.out.println("Performance targets:");
        System.out.println("  Single computation: >= " + (long) TARGET_SINGLE_OPS_PER_SEC + " ops/sec");
        System.out.println("  Batch computation:  >= " + (long) TARGET_BATCH_OPS_PER_SEC + " ops/sec");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include only DistanceBenchmark
                .include(".*DistanceBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(2))
                // Fork configuration
                .forks(1)
                // JVM arguments for Panama FFM and Vector API
                .jvmArgsAppend(
                    "--enable-preview",
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-modules=jdk.incubator.vector",
                    "-Xms2g",
                    "-Xmx2g"
                )
                // Output format
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Distance Benchmark Complete");
        System.out.println("========================================");
    }
}
