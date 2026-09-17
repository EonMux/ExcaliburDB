/**
 * The computation the benchmarks perform, separated from the harness that times it.
 *
 * <p>A JMH benchmark class is two things wound together: a measurement loop, which is
 * only meaningful when actually run, and the arithmetic that loop exercises, which is
 * ordinary code with ordinary answers. This package holds the second kind - reference
 * distance kernels, brute-force ground truth, recall and false-positive accumulation,
 * latency percentiles - so it can be unit-tested in milliseconds instead of being
 * vouched for by a benchmark run lasting minutes.</p>
 */
package excalibur.bench.kernel;
