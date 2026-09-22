package com.eonmux.excalibur.store;

/**
 * Callback for compaction completion events.
 *
 * <p>The listener is responsible only for swapping the old segment for the new one in any
 * segment registry or map. The <em>file</em> swap is owned by {@link CompactionWorker} and has
 * already happened: by the time this is called the compacted segment is installed at the
 * canonical {@code segment_&lt;index&gt;.dat} path and reports that path from
 * {@link Segment#path()}. Listeners must not rename or delete segment files themselves.</p>
 */
@FunctionalInterface
public interface CompactionListener {

    /**
     * Called when compaction is complete and the old segment should be replaced with the new
     * segment.
     *
     * <p><strong>Important:</strong> The implementation must atomically update any segment
     * registry to point to the new segment. The old segment is still open when this is called,
     * and its write lock is held by the calling thread, so in-flight operations are never
     * handed a closed segment. Nothing here may close either segment, and nothing here may
     * block on that lock.</p>
     *
     * <p>The returned outcome is how the worker learns which segment is still reachable, and
     * it must describe what the registry actually holds now - a listener that reports
     * {@link CompactionOutcome#INSTALLED} after refusing the swap retires the segment its own
     * registry still routes to.</p>
     *
     * @param oldSegment The old segment (still open, to be replaced)
     * @param newSegment The new compacted segment, already installed at the canonical segment
     *                   path (active, to be used)
     * @return What the registry now holds, which must not be null
     */
    CompactionOutcome onCompactionComplete(Segment oldSegment, Segment newSegment);
}
