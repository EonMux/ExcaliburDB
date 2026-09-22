package com.eonmux.excalibur.backup.manifest;

/**
 * How much the files in a snapshot can be trusted to agree with one another.
 *
 * <p>Nothing in this engine orders one file against another. The catalog, the manifests and
 * the registries are replaced by an atomic rename; the segments, their write-ahead logs and
 * the HNSW graphs are written in place through their mappings. A copy taken while a node is
 * writing can therefore hold a catalog naming a segment the copy does not yet have, or a
 * mapping caught half-flushed. Which of these a given image is, is a fact about how it was
 * taken, so it is recorded rather than assumed.</p>
 *
 * @author ExcaliburDB
 */
public enum Consistency {

    /**
     * Taken with no node running, and nothing in the directory changed while it was taken.
     *
     * <p>The only state this tool will produce without being told to override it. A clean
     * shutdown fsyncs outstanding writes, closes each write-ahead log and persists the
     * routing directory and the catalog in that order, so the files left behind are files
     * that already agree; and the copy re-examined every one of them afterwards and found
     * none of them moved.</p>
     */
    OFFLINE,

    /**
     * Taken while a node was running, at the operator's insistence.
     *
     * <p>Every write-ahead log append is fsynced before it is acknowledged, so the
     * acknowledged writes are on the device and are in the image. What an image like this
     * can lose is the agreement between files - the catalog against the segments it names,
     * a graph against the mapping beside it - not the corpus. It is worth taking when the
     * alternative is no backup at all, and it is not worth mistaking for the other kind.</p>
     */
    LIVE_UNSAFE
}
