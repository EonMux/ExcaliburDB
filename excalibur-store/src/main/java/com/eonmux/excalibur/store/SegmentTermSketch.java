package com.eonmux.excalibur.store;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.sketch.CountMinSketch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * Keeps one {@link Segment}'s term-frequency sketch in step with what the segment holds, and on
 * disk across restarts.
 *
 * <p>The sketch is what the query pipeline orders a query's candidate segments by: a segment in
 * which the query's terms make up a larger share of all terms is searched earlier. It only ever
 * reorders - every candidate is still searched - so what matters is that the counts follow the
 * data closely, not that they are exact.</p>
 *
 * <h2>Where terms are counted</h2>
 * <p>At the one point every applied write passes through: {@link SegmentMutations#putInternal},
 * under the segment's write lock and after the write has been accepted. That covers ingestion,
 * reindexing, upserts and targeted writes, and it covers the copies a split and a compaction
 * make into their new segments, so a segment built by either starts with a sketch that is exact
 * for its contents. The replay of the write log at start-up deliberately does not count: the
 * sketch it would add to is loaded or rebuilt after the replay, from the replayed state.</p>
 *
 * <p>The terms are {@link TermExtractor#extractTerms(String)} of the chunk text, with
 * repetition, which is the same tokenisation the membership filters and the query side use.
 * A value that carries no readable chunk text contributes nothing.</p>
 *
 * <h2>Tokenised before the lock, counted under it</h2>
 * <p>Decoding a chunk's text, tokenising it and hashing every term is most of the cost of
 * counting it, and none of it depends on the segment. So a write reaching the segment through
 * its own {@code put} has its terms {@linkplain #termHashes(byte[]) hashed} before the write lock
 * is taken, and a write routed by the engine has them hashed once for however many segments the
 * routing tries - or not at all, when the caller already tokenised the text and hands the terms
 * over. Under the lock only the counters are touched: one counter per row per term. The count
 * itself stays under the lock, after the write is applied, so that a refused write is never
 * counted and a {@linkplain #persist() persisted} sketch and its watermark still describe one
 * state. Copies made by a split or a compaction hash under whatever lock the copy holds; each
 * term is hashed once there too.</p>
 *
 * <h2>Deletes and overwrites over-count</h2>
 * <p>A Count-Min sketch cannot take a count back, so a deleted entry's terms, and the previous
 * value of an overwritten one, stay counted until the segment is rewritten. The error is always
 * an over-estimate, which can move a segment earlier than it deserves and never drops one from
 * the search. Compaction removes it: it copies only live entries into a new segment whose sketch
 * starts empty. So does any rebuild on open.</p>
 *
 * <h2>Persistence</h2>
 * <p>{@link Segment#flush()} and {@link Segment#close()} write the sketch to
 * {@link TermSketchFile#pathFor(Path)} with the watermark that dates it. A segment without a
 * write log - a compaction or split scratch file, not yet published - writes nothing, because
 * its name is not the one it will be found under; a segment that has given its sketch up
 * ({@link Segment#detachTermFrequencySketch()}) writes nothing because another segment now owns
 * the name. On open the engine calls {@link #loadOrRebuild()} once the log has been replayed.
 * A sidecar whose watermark matches is adopted; anything else - no file, a damaged file, a file
 * dated to another state - is replaced by a sketch rebuilt from the live entries.</p>
 *
 * <h2>Nothing here fails an open</h2>
 * <p>The sketch only orders the search, so no fault in it may keep a segment from serving. A
 * live entry that does not match its checksum is left out of the rebuild and the rest are
 * counted - the same policy {@link SegmentFile} applies to recovery, and the entry cannot be
 * read by a query either. Any other failure to rebuild leaves the segment with an empty sketch,
 * which it keeps counting into but does not persist, so the next open rebuilds it instead of
 * adopting counts known to be incomplete.</p>
 *
 * <p><strong>Thread safety:</strong> {@link #record} runs under the segment's write lock.
 * {@link #persist()} takes the read lock to capture the sketch and its watermark as one state,
 * and may be called while the write lock is held (as {@code close()} does), since the lock is
 * reentrant.</p>
 */
final class SegmentTermSketch {

    /**
     * Counters per row.
     *
     * <p>A segment's sketch answers "what share of this segment's terms is this one", to rank
     * segments against each other, so an over-count of a fraction of a percent of the segment's
     * term total changes nothing that matters. 1024 columns bounds it at about 0.2%; the default
     * sketch's 2000 x 10 would cost five times the memory per segment for precision no ordering
     * decision can use.</p>
     */
    static final int WIDTH = 1024;

    /** Rows; the estimate is within the bound in all but one in sixteen lookups. */
    static final int DEPTH = 4;

    /**
     * Hash seed, fixed so a rebuilt sketch and the one it replaces put each term in the same
     * columns. The seed is stored with the counters, so this is a convenience, not a format.
     */
    static final long SEED = 0x5EED_7F5CL;

    /** The segment whose writes are counted. */
    private final Segment owner;

    /**
     * The state the sidecar on disk was last written at by this segment, or null if it has not
     * written one. Lets a flush followed by a close write the file once.
     */
    private volatile TermSketchFile.Watermark persisted;

    /**
     * Whether the attached sketch is known to be missing live entries because its rebuild
     * failed. Such a sketch is not persisted: dated to the segment's state, it would be adopted
     * on the next open in place of a rebuild that could succeed.
     */
    private volatile boolean incomplete;

    /** Term hashes of a value that carries no chunk text. */
    static final long[] NO_TERMS = new long[0];

    /**
     * Binds the sketch keeping to its segment.
     *
     * @param owner The owning segment
     */
    SegmentTermSketch(Segment owner) {
        this.owner = owner;
    }

    /**
     * Returns an empty sketch of the shape every segment keeps.
     *
     * @return A new sketch
     */
    static CountMinSketch newSketch() {
        return new CountMinSketch(WIDTH, DEPTH, SEED);
    }

    /**
     * Counts the terms of a value the segment has just accepted.
     *
     * <p>The caller holds the write lock and has applied the write.</p>
     *
     * @param termHashes The value's {@linkplain #termHashes(byte[]) term hashes}
     */
    void record(long[] termHashes) {
        CountMinSketch sketch = owner.termFrequencySketch;
        if (sketch != null) {
            count(sketch, termHashes);
        }
    }

    /**
     * Hashes the chunk-text terms of a value, with repetition, ready to be counted.
     *
     * <p>Holds no lock and reads nothing of any segment, so it belongs before the write lock
     * is taken.</p>
     *
     * @param value A stored value; one without readable chunk text has no terms
     * @return One {@linkplain CountMinSketch#itemHash(byte[]) item hash} per term occurrence
     */
    static long[] termHashes(byte[] value) {
        String text = ChunkPayloadCodec.textOf(value);
        if (text == null || text.isEmpty()) {
            return NO_TERMS;
        }
        return termHashes(TermExtractor.extractTerms(text));
    }

    /**
     * Hashes terms a caller has already extracted, ready to be counted.
     *
     * @param terms {@link TermExtractor#extractTerms(String)} of a value's chunk text
     * @return One {@linkplain CountMinSketch#itemHash(byte[]) item hash} per term
     */
    static long[] termHashes(List<String> terms) {
        if (terms.isEmpty()) {
            return NO_TERMS;
        }
        long[] hashes = new long[terms.size()];
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = CountMinSketch.itemHash(terms.get(i).getBytes(StandardCharsets.UTF_8));
        }
        return hashes;
    }

    /**
     * Adds hashed terms to a sketch.
     *
     * @param sketch     The sketch to add to
     * @param termHashes The terms, as {@link #termHashes(byte[])} returned them
     */
    static void count(CountMinSketch sketch, long[] termHashes) {
        for (long hash : termHashes) {
            sketch.insertHashed(hash, 1);
        }
    }

    /**
     * Forgets what was last written, because the sketch it described has been replaced.
     */
    void sketchReplaced() {
        persisted = null;
        incomplete = false;
    }

    /**
     * Writes the sketch to its sidecar if the segment owns its name and has changed since the
     * last write.
     *
     * <p>A failure is logged rather than raised. The sketch is derived data: a missing or stale
     * sidecar costs a rebuild on the next open, and failing the flush or the close over it would
     * turn that into lost writes or a leaked segment.</p>
     */
    void persist() {
        Snapshot snapshot = snapshot();
        if (snapshot == null) {
            return;
        }
        // The write is serialised, the capture is not: close() captures under the write lock,
        // so a monitor held across the capture would deadlock against a flush waiting for the
        // read lock. Serialising the write alone is enough to stop an older capture that
        // finishes last from replacing a newer one on disk.
        synchronized (this) {
            TermSketchFile.Watermark last = persisted;
            if (last != null && (snapshot.watermark().equals(last)
                    || snapshot.watermark().walNextLsn() < last.walNextLsn())) {
                return;
            }
            Path sidecar = TermSketchFile.pathFor(owner.path);
            try {
                TermSketchFile.write(sidecar, snapshot.watermark(), snapshot.sketchBytes());
                persisted = snapshot.watermark();
            } catch (IOException | RuntimeException e) {
                Segment.LOGGER.log(Level.WARNING, "Could not write the term sketch for segment "
                    + owner.path + " to " + sidecar + "; it will be rebuilt when the segment is "
                    + "next opened", e);
            }
        }
    }

    /**
     * A sketch and the state it describes, captured together.
     *
     * @param watermark   The state of the segment
     * @param sketchBytes The sketch at that state
     */
    private record Snapshot(TermSketchFile.Watermark watermark, byte[] sketchBytes) {
    }

    /**
     * Captures the sketch and its watermark under the read lock, so no write falls between them.
     *
     * @return The snapshot, or null when this segment has nothing it should persist
     */
    private Snapshot snapshot() {
        owner.lock.readLock().lock();
        try {
            CountMinSketch sketch = owner.termFrequencySketch;
            WriteLog log = owner.writeLog;
            if (sketch == null || incomplete || log == null || log.isClosed()) {
                return null;
            }
            return new Snapshot(watermark(log), sketch.toBytes());
        } finally {
            owner.lock.readLock().unlock();
        }
    }

    /**
     * Returns the segment's current watermark. The caller holds a lock.
     */
    private TermSketchFile.Watermark watermark(WriteLog log) {
        return new TermSketchFile.Watermark(log.nextLsn(), owner.entryCount,
            owner.liveEntryCount);
    }

    /**
     * Attaches the persisted sketch if it describes the segment as it now stands, and otherwise a
     * sketch rebuilt from the live entries.
     *
     * <p>Called by the engine once a reopened segment's write log has been replayed, before the
     * segment serves anything, so there are no concurrent writes; the write lock is held anyway
     * so the watermark and the attach are one step. That is also what makes it safe to remove
     * the staging files an interrupted {@linkplain TermSketchFile#write sidecar write} left
     * beside the segment: nothing is persisting this segment's sketch yet.</p>
     *
     * <p>Never fails for anything about the sketch or the entries it is counted from; see
     * "Nothing here fails an open".</p>
     *
     * @return true if the sidecar was adopted, false if the sketch was rebuilt
     * @throws IllegalStateException if the segment has no write log attached, which only a
     *                               caller that has not attached one can cause
     */
    boolean loadOrRebuild() {
        owner.lock.writeLock().lock();
        try {
            WriteLog log = owner.writeLog;
            if (log == null) {
                throw new IllegalStateException("Segment " + owner.path
                    + " has no write log, so no watermark can date its term sketch");
            }
            TermSketchFile.Watermark current = watermark(log);
            Path sidecar = TermSketchFile.pathFor(owner.path);
            TermSketchFile.sweepStaging(sidecar);
            CountMinSketch loaded = loadIfCurrent(sidecar, current);
            if (loaded != null) {
                owner.termFrequencySketch = loaded;
                persisted = current;
                incomplete = false;
                return true;
            }
            persisted = null;
            try {
                owner.termFrequencySketch = rebuild();
                incomplete = false;
            } catch (RuntimeException e) {
                Segment.LOGGER.log(Level.WARNING, "Could not rebuild the term sketch of segment "
                    + owner.path + "; it starts empty, orders queries without this segment's "
                    + "earlier terms, and is rebuilt again on the next open", e);
                owner.termFrequencySketch = newSketch();
                incomplete = true;
            }
            return false;
        } finally {
            owner.lock.writeLock().unlock();
        }
    }

    /**
     * Reads the sidecar and returns its sketch only if it is dated to the given state.
     *
     * @param sidecar The sidecar path
     * @param current The segment's watermark now
     * @return The stored sketch, or null if there is none worth adopting
     */
    private CountMinSketch loadIfCurrent(Path sidecar, TermSketchFile.Watermark current) {
        if (!Files.exists(sidecar)) {
            Segment.LOGGER.log(Level.FINE, "No term sketch beside {0}; rebuilding it",
                owner.path);
            return null;
        }
        try {
            TermSketchFile.Stored stored = TermSketchFile.read(sidecar);
            if (!stored.watermark().equals(current)) {
                Segment.LOGGER.log(Level.INFO, "Term sketch {0} was written at {1} but the "
                    + "segment is now at {2}; rebuilding it",
                    new Object[] { sidecar, stored.watermark(), current });
                return null;
            }
            return stored.sketch();
        } catch (IOException e) {
            Segment.LOGGER.log(Level.WARNING, "Term sketch " + sidecar + " cannot be used ("
                + e.getMessage() + "); rebuilding it from the segment", e);
            return null;
        }
    }

    /**
     * Counts the terms of every live entry into a fresh sketch, leaving out any entry that does
     * not match its checksum.
     *
     * @return The rebuilt sketch
     */
    private CountMinSketch rebuild() {
        CountMinSketch sketch = newSketch();
        int skipped = 0;
        try (Segment.EntryIterator entries = owner.liveEntries()) {
            while (true) {
                com.eonmux.excalibur.store.format.SegmentEntry entry;
                try {
                    if (!entries.hasNext()) {
                        break;
                    }
                    entry = entries.next();
                } catch (CorruptSegmentEntryException e) {
                    // The walk has already moved past the damaged slot; see EntryIterator.
                    if (skipped++ == 0) {
                        Segment.LOGGER.log(Level.WARNING, "Term sketch rebuild of segment "
                            + owner.path + " is leaving out an entry that fails verification",
                            e);
                    }
                    continue;
                }
                count(sketch, termHashes(entry.value()));
            }
        }
        if (skipped > 0) {
            Segment.LOGGER.log(Level.WARNING, "Term sketch of segment {0} was rebuilt without "
                + "{1} damaged entr{2}; the rest are counted. The damage is reported when those "
                + "keys are read and by an integrity check of the segment",
                new Object[] { owner.path, skipped, skipped == 1 ? "y" : "ies" });
        }
        return sketch;
    }
}
