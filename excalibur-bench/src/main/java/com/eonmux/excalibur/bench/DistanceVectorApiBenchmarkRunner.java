package com.eonmux.excalibur.bench;

import com.eonmux.excalibur.vector.distance.VectorApiSupport;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * JMH Benchmark Runner for Distance Vector API comparison benchmarks.
 *
 * <p>Runs benchmarks comparing scalar vs Vector API implementations for
 * Cosine Similarity, L2 Distance, and Inner Product computations.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all distance Vector API comparison benchmarks
 * ./gradlew :excalibur-bench:runDistanceVectorApiBenchmark
 *
 * # Or run directly
 * java -jar excalibur-bench.jar distance-vectorapi
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Scalar single computation: >=5M ops/sec</li>
 *   <li>Vector API single computation: >=20M ops/sec</li>
 *   <li>Speedup factor: 2x-8x depending on SIMD width</li>
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
 * @since Phase 10
 */
public class DistanceVectorApiBenchmarkRunner {

    /**
     * Target throughput for scalar single distance computations (5 million ops/sec).
     */
    public static final double TARGET_SCALAR_OPS_PER_SEC = 5_000_000.0;

    /**
     * Target throughput for Vector API single distance computations (20 million ops/sec).
     */
    public static final double TARGET_VECTOR_API_OPS_PER_SEC = 20_000_000.0;

    /**
     * Minimum expected speedup factor from Vector API (2x).
     */
    public static final double MIN_SPEEDUP_FACTOR = 2.0;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("ExcaliburDB Distance Vector API Benchmark");
        System.out.println("========================================");
        System.out.println();

        // Check Vector API availability
        VectorApiSupport vectorSupport = VectorApiSupport.getInstance();
        boolean vectorApiAvailable = vectorSupport.isAvailable();

        System.out.println("Configuration:");
        System.out.println("  Warmup: 3 iterations x 2 seconds");
        System.out.println("  Measurement: 5 iterations x 2 seconds");
        System.out.println("  Forks: 1");
        System.out.println("  Vector dimensions: 64, 128, 256, 512, 768, 1536");
        System.out.println();

        System.out.println("Vector API Status:");
        System.out.println("  Available: " + vectorApiAvailable);
        if (vectorApiAvailable) {
            System.out.println("  SIMD width: " + vectorSupport.getPreferredSpeciesLength() + " floats");
        } else {
            System.out.println("  Note: Vector API unavailable - scalar fallback will be used");
            System.out.println("        Run with --add-modules=jdk.incubator.vector to enable");
        }
        System.out.println();

        System.out.println("Performance targets:");
        System.out.println("  Scalar computation:     >= " + (long) TARGET_SCALAR_OPS_PER_SEC + " ops/sec");
        System.out.println("  Vector API computation: >= " + (long) TARGET_VECTOR_API_OPS_PER_SEC + " ops/sec");
        System.out.println("  Speedup factor:         >= " + MIN_SPEEDUP_FACTOR + "x");
        System.out.println();

        System.out.println("Benchmark categories:");
        System.out.println("  - Cosine similarity (scalar vs Vector API)");
        System.out.println("  - L2 distance (scalar vs Vector API)");
        System.out.println("  - Inner product (scalar vs Vector API)");
        System.out.println("  - Batch operations (100 computations)");
        System.out.println("  - Nearest neighbor search simulation");
        System.out.println("  - Combined all-distance throughput");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include only DistanceVectorApiBenchmark
                .include(".*DistanceVectorApiBenchmark.*")
                // Warmup configuration
                .warmupIterations(3)
                .warmupTime(TimeValue.seconds(2))
                // Measurement configuration
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(2))
                // Fork configuration
                .forks(1)
                // The flags every fork needs, then this benchmark's own
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms2g",
                    "-Xmx2g"
                ))
                // Output format
                .shouldDoGC(true)
                .build();

        new Runner(options).run();

        System.out.println();
        System.out.println("========================================");
        System.out.println("Distance Vector API Benchmark Complete");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Analysis tips:");
        System.out.println("  - Compare *_scalar vs *_vectorApi for speedup factor");
        System.out.println("  - Higher dimensions (768, 1536) benefit more from vectorization");
        System.out.println("  - Batch operations show cumulative speedup");
        System.out.println("  - If Vector API unavailable, both paths use scalar implementation");
    }
}
