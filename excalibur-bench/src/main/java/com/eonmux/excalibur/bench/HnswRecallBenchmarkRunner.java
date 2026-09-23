package com.eonmux.excalibur.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import com.eonmux.excalibur.bench.benchmark.HnswRecallBenchmark;
import com.eonmux.excalibur.bench.kernel.EmbeddingCorpus;

/**
 * JMH Benchmark Runner for HNSW recall@10 validation.
 *
 * <p>This runner executes the HNSW recall benchmark to validate that the
 * default configuration achieves ≥99% recall@10 as required by Phase 4.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run all HNSW recall benchmarks
 * ./gradlew :excalibur-bench:runHnswRecallBenchmark
 *
 * # Or run directly
 * java -jar excalibur-bench.jar hnsw-recall
 * </pre>
 *
 * <h2>Performance Target</h2>
 * <p>Target: ≥99% recall@10 with default configuration (M=16, efConstruction=200, efSearch=200)</p>
 *
 * <h2>Configuration Parameters</h2>
 * <ul>
 *   <li>M (maxConnections): 16</li>
 *   <li>M0 (maxConnectionsLayer0): 32</li>
 *   <li>efConstruction: 200</li>
 *   <li>efSearch: 50, 100, 150, 200, 250, 300, 400 (tested)</li>
 *   <li>mL: 1/ln(M)</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Recall values in range [0.0, 1.0]</li>
 *   <li>Dataset sizes: 1K, 10K vectors</li>
 *   <li>Vector dimensions: 64, 128</li>
 *   <li>K (nearest neighbors): 10</li>
 * </ul>
 *
 * <h2>Validation Criteria</h2>
 * <p>The benchmark passes if recall@10 ≥ 0.99 (99%) for efSearch=200.</p>
 *
 * @author ExcaliburDB
 */
public class HnswRecallBenchmarkRunner {

    /**
     * Target recall@10 threshold (99%)
     */
    public static final double TARGET_RECALL = HnswRecallBenchmark.TARGET_RECALL_10;

    public static void main(String[] args) throws RunnerException {
        System.out.println("========================================");
        System.out.println("ExcaliburDB HNSW Recall Benchmark Runner");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Running HNSW recall benchmarks with configuration:");
        System.out.println("  Warmup: 2 iterations x 1 second");
        System.out.println("  Measurement: 3 iterations x 1 second");
        System.out.println("  Forks: 1");
        System.out.println();
        System.out.println("HNSW Default Configuration:");
        System.out.println("  M (maxConnections): " + com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_MAX_CONNECTIONS);
        System.out.println("  M0 (maxConnectionsLayer0): " + com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0);
        System.out.println("  efConstruction: " + com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_EF_CONSTRUCTION);
        System.out.println("  efSearch (default): " + com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_EF_SEARCH);
        System.out.println("  mL: " + String.format("%.4f", com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_ML));
        System.out.println();
        System.out.println("Performance target:");
        System.out.println("  Recall@10: >= " + String.format("%.0f", TARGET_RECALL * 100) + "%");
        System.out.println();
        System.out.println("Corpus: EmbeddingCorpus (clustered, " + EmbeddingCorpus.LATENT_DIMENSION
            + " directions of variance), efSearch values 50-400");
        System.out.println();
        System.out.println("========================================");
        System.out.println();

        Options options = new OptionsBuilder()
                // Include HnswRecallBenchmark
                .include(".*HnswRecallBenchmark.*")
                // Warmup configuration
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                // Measurement configuration
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                // Fork configuration
                .forks(1)
                // The flags every fork needs, then this benchmark's own
                .jvmArgsAppend(ForkedJvm.flags(
                    "-Xms4g",
                    "-Xmx4g"
                ))
                // Output format
                .shouldDoGC(true)
                .build();

        try {
            new Runner(options).run();
            System.out.println();
            System.out.println("========================================");
            System.out.println("HNSW Recall Benchmark Complete");
            System.out.println("========================================");
            System.out.println();
            System.out.println("VALIDATION CRITERIA:");
            System.out.println("  Each trial prints \"Initial recall@10\" after building its graph, and a trial at");
            System.out.println("  efSearch >= " + com.eonmux.excalibur.vector.hnsw.HnswConfig.DEFAULT_EF_SEARCH
                + " below " + (TARGET_RECALL * 100) + "% aborts in setup. JMH scores are ms per search.");
            System.out.println();
            System.out.println("QUERIES/SEC ANALYSIS:");
            System.out.println("  Compare searchThroughput benchmark results across efSearch values");
            System.out.println("  Higher efSearch = higher recall but lower QPS");
            System.out.println("  Find the sweet spot that meets >=99% recall with acceptable QPS");
            System.out.println();
        } catch (RunnerException e) {
            System.err.println("Benchmark execution failed: " + e.getMessage());
            throw e;
        }
    }
}
