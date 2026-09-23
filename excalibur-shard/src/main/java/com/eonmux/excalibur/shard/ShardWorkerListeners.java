package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.shard.worker.HnswCompactionWorker;
import com.eonmux.excalibur.shard.worker.HnswIntegrationWorker;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.shard.worker.WarmingPhaseWorker;
import com.eonmux.excalibur.store.CompactionListener;

/**
 * Everything the background workers call back into.
 *
 * <p>A worker is built with its listener and never rebuilt, so all five callbacks have to be
 * available before any worker exists - while the collaborators that answer them need the
 * workers to do their job. Bundling the callbacks lets {@link ShardWorkers} be constructed
 * from one argument of deferred lambdas, and lets the bookkeeping collaborators be built
 * afterwards against the workers they drive. The lambdas are only ever invoked from a running
 * worker, which is long after construction has finished.</p>
 *
 * @param integration       receives the count of vectors integrated into a segment's graph
 * @param warming           receives the outcome of a segment's PQ codebook training
 * @param reindex           receives the id of a source whose data has gone stale
 * @param segmentCompaction receives a compacted segment and its replacement, and answers
 *                          which of the two the store may retire
 * @param graphCompaction   receives a rebuilt HNSW graph, or the failure that left one closed
 */
record ShardWorkerListeners(HnswIntegrationWorker.IntegrationListener integration,
                            WarmingPhaseWorker.WarmingListener warming,
                            SegmentReindexScheduler.ReindexListener reindex,
                            CompactionListener segmentCompaction,
                            HnswCompactionWorker.CompactionListener graphCompaction) {
}
