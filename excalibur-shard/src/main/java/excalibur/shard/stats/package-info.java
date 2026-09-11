/**
 * Statistics and metrics collection for shard-level operations.
 *
 * <p>Probabilistic cardinality estimation over a segment's terms.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link excalibur.shard.stats.UniqueTermCounter} - Probabilistic unique term counting using HyperLogLog</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>None. Precision is a constructor argument, and no configuration key reaches it: the server
 * does not build one of these. See the class javadoc for what is and is not wired.</p>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
package excalibur.shard.stats;
