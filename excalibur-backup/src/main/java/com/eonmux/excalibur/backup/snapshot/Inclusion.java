package com.eonmux.excalibur.backup.snapshot;

/**
 * Why a file is in a backup, or is not.
 *
 * <p>The distinction that matters is not "can this be recomputed" but "will anything
 * recompute it". Several structures under a data directory are derived in the sense that
 * their content originates elsewhere, and only some of those have code that will rebuild
 * them. A file in the second group is authoritative in practice however derived it is in
 * principle, and treating it as disposable produces a restored node that starts, serves,
 * and is wrong.</p>
 *
 * @author ExcaliburDB
 */
public enum Inclusion {

    /**
     * State that exists nowhere else. Always copied.
     *
     * <p>The segment files and their write-ahead logs, the catalog, the routing directory,
     * the document registry, the source catalog, and each tenant's graph staging logs and
     * erasure ledger.</p>
     */
    AUTHORITATIVE(true, true),

    /**
     * Derived in principle; no code path rebuilds it. Always copied.
     *
     * <p>The HNSW graphs and their document mappings. Their vectors come from the segment
     * payloads, but nothing regenerates a graph from a segment: deleting one yields an
     * empty graph attached to a populated segment, not a rebuild, and the document mapping
     * beside it is persisted precisely because reconstructing it was impossible. The two
     * are copied as a pair, together with any staged pair left by an interrupted
     * compaction, because a graph restored beside a mapping from a different moment
     * answers with the wrong document rather than failing.</p>
     */
    REQUIRED_DERIVED(true, true),

    /**
     * Derived, with a rebuild path. Copied unless the operator asks for a smaller image.
     *
     * <p>The lexical chunk index, a tenant's built edge store and the quantiser codebooks.
     * Each can be regenerated - by a reindex, by a graph build or by retraining - at a cost
     * in time rather than in data. Excluding them makes a much smaller backup whose restore
     * is followed by work; including them makes a backup that can serve immediately.</p>
     */
    REBUILDABLE(true, false),

    /**
     * A staged name a crash can strand. Never copied.
     *
     * <p>Every durable structure here is written beside its destination and renamed on, so
     * a leftover is content that was never published. Segment discovery does not match
     * these names and nothing reads them; copying one puts a file in the backup that looks
     * like state and is not.</p>
     */
    TRANSIENT(false, false),

    /**
     * A JVM heap dump. Never copied.
     *
     * <p>{@code -XX:+HeapDumpOnOutOfMemoryError} writes one wherever {@code -XX:HeapDumpPath}
     * points, and a node pointed at its data directory leaves it beside the segments. It is
     * a record of a process, not state: nothing in the engine reads it, a restored node has
     * no use for it, and it is roughly the size of the heap that ran out - so copying it
     * would most often make the image several times the size of the data it protects. The
     * snapshot reports it as omitted, so the dump is seen rather than silently left
     * behind.</p>
     */
    DIAGNOSTIC(false, false),

    /**
     * A file an earlier build kept that this build neither reads nor writes. Never copied.
     *
     * <p>The term-frequency sketch an earlier build wrote beside each segment,
     * {@code segment_N.tfs}, and the name it staged one under. Nothing reads them, the node
     * removes them when it starts, and a restored node would remove them again, so an image
     * that carried one would only be larger. The snapshot reports each as omitted, and a
     * restore of an image an earlier build took verifies it with the rest of the image and
     * then leaves it out of the restored directory.</p>
     */
    OBSOLETE(false, false);

    /** Whether a snapshot that keeps derived state copies this. */
    private final boolean withDerived;

    /** Whether a snapshot that drops derived state copies this. */
    private final boolean withoutDerived;

    /**
     * Creates a classification.
     *
     * @param withDerived    Copied when derived state is kept
     * @param withoutDerived Copied when derived state is dropped
     */
    Inclusion(boolean withDerived, boolean withoutDerived) {
        this.withDerived = withDerived;
        this.withoutDerived = withoutDerived;
    }

    /**
     * Reports whether a snapshot copies files of this class.
     *
     * @param includeDerived Whether the snapshot keeps rebuildable derived state
     * @return true if the file belongs in the image
     */
    public boolean includedWhen(boolean includeDerived) {
        return includeDerived ? withDerived : withoutDerived;
    }
}
