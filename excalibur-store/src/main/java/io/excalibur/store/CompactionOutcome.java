package io.excalibur.store;

/**
 * What a {@link CompactionListener} did with a compacted segment, and therefore which of the
 * two segments the compaction worker may retire.
 *
 * <p>By the time the listener is called the file swap has already happened: the replacement is
 * installed at the canonical segment name and the original's file is unlinked. All that is left
 * is moving the routing registry from one segment object to the other, and whether that
 * succeeded is the one thing the worker cannot see for itself. It decides which segment is
 * still reachable, and retiring a reachable segment fails every read and write to its key range
 * for the life of the process, while leaving an unreachable one open leaks its mapping and its
 * write log descriptor until the process exits.</p>
 */
public enum CompactionOutcome {

    /**
     * The registry routes to the replacement. The compacted segment is unreachable and is
     * retired; the replacement serves from here on.
     */
    INSTALLED(true, false),

    /**
     * The registry still routes to the compacted segment, so it stays open and serving and the
     * replacement is discarded.
     *
     * <p>This is a degraded outcome rather than a clean one - the replacement's file is already
     * at the canonical name, so the segment still being served is reading from an unlinked
     * inode and the next start sees the compacted file instead. It is nonetheless the better of
     * the two answers available: the keys stay readable and writable, which they would not be
     * if the segment the registry points at were closed underneath it.</p>
     */
    REFUSED(false, true),

    /**
     * Neither segment is reachable, because the compacted segment left the registry before the
     * swap could be applied. Both are retired rather than left open with nothing routing to
     * them.
     */
    ABANDONED(true, true);

    private final boolean retiresCompactedSegment;
    private final boolean discardsReplacement;

    CompactionOutcome(boolean retiresCompactedSegment, boolean discardsReplacement) {
        this.retiresCompactedSegment = retiresCompactedSegment;
        this.discardsReplacement = discardsReplacement;
    }

    /**
     * Whether the segment that was compacted is now unreachable.
     *
     * @return true if the compacted segment must be closed
     */
    public boolean retiresCompactedSegment() {
        return retiresCompactedSegment;
    }

    /**
     * Whether the replacement built by this compaction is unreachable.
     *
     * @return true if the replacement must be discarded
     */
    public boolean discardsReplacement() {
        return discardsReplacement;
    }
}
