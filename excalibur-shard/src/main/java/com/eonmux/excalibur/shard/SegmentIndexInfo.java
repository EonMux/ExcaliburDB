package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.TailOffsetTracker;

/**
 * Everything one registered segment id owns, held in one place because the swap paths move it
 * in one piece.
 *
 * <p>A compaction or a split replaces the {@link Segment} instance behind an index while the
 * graph, the tail offset tracker and the Cuckoo filter carry over unchanged. Gathering them
 * here is what lets the swap read them once, before it disturbs anything, and re-attach them
 * to the replacement - or hand them back when the swap is refused. Kept as separate maps they
 * had to be looked up one at a time, and a swap that failed halfway left them disagreeing
 * about which instance was live.</p>
 *
 * @param segment The segment instance the workers are registered against, by identity
 * @param graph   The segment's HNSW graph, or null when it has none
 * @param filter  The segment's Cuckoo filter, or null when it has none
 * @param tracker The integration checkpoint into the segment's write log, or null
 */
record SegmentIndexInfo(Segment segment, HnswGraph graph, CuckooFilter filter,
                        TailOffsetTracker tracker) {
}
