/**
 * Statistics and metrics collection for shard-level operations.
 *
 * <p>This package provides classes for tracking and exposing statistics
 * about shard operations, including unique term counting via HyperLogLog.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link excalibur.shard.stats.UniqueTermCounter} - Probabilistic unique term counting using HyperLogLog</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>The precision of HyperLogLog can be configured via:</p>
 * <pre>
 * excalibur.shard.hyperloglog-precision=14
 * </pre>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
package excalibur.shard.stats;
