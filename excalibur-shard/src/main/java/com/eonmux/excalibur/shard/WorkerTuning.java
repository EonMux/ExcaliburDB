package com.eonmux.excalibur.shard;

/**
 * How hard the background workers are allowed to run.
 *
 * <p>Three numbers reached the worker constructors as three positional arguments threaded
 * through five overloaded orchestrator constructors, where {@code (100000, 60000, 100)}
 * says nothing about which number is a queue depth and which is a period. Carrying them as one
 * named value is what makes a mis-ordered pair a compile error rather than a shard that ticks
 * its epoch once a minute.</p>
 *
 * <p>Unlike {@link CompactionSettings} these are not range-checked here: each worker validates
 * its own argument and raises against the constant it enforces, and the orchestrator's public
 * constructors are documented to throw from there.</p>
 *
 * @param hnswQueueDepthLimit         entries the HNSW integration queue holds before it
 *                                    backpressures
 * @param reindexCheckIntervalMs      how often stale sources are checked for re-indexing
 * @param epochTickIntervalMs         how often the visibility epoch advances on its own
 */
record WorkerTuning(int hnswQueueDepthLimit,
                    long reindexCheckIntervalMs,
                    long epochTickIntervalMs) {
}
