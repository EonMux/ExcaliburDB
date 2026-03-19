package excalibur.bench;

import excalibur.bench.benchmark.VectorLayoutBenchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runner for VectorLayout benchmarks.
 *
 * <p>Compares optimized SoA memory layout against traditional AoS
 * for batch distance computations.</p>
 *
 * <h2>Running the Benchmark</h2>
 * <pre>
 * ./gradlew :excalibur-bench:run --args="VectorLayout"
 * </pre>
 *
 * @author ExcaliburDB
 */
public class VectorLayoutBenchmarkRunner {

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
            .include(VectorLayoutBenchmark.class.getSimpleName())
            .shouldFailOnError(true)
            .shouldDoGC(true)
            .build();

        new Runner(options).run();
    }
}
