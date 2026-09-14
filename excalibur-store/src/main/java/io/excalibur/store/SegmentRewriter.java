package io.excalibur.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rewrites one segment into a compacted replacement and installs it.
 *
 * <p>This is the mechanical half of compaction, separated from {@link CompactionWorker}, which
 * owns the schedule and the thresholds that decide <em>which</em> segments are worth rewriting
 * and <em>when</em>. The two halves have nothing in common at runtime: the worker's policy
 * fields are reconfigurable while it runs, and everything here is a single-shot sequence of
 * file operations that either installs a replacement or leaves the original exactly as it was.
 * </p>
 *
 * <p>A rewrite copies every live entry into a scratch file, renames that file onto the
 * segment's canonical {@code segment_<index>.dat} name, and asks the listener to move the
 * routing registry across. The canonical name therefore always holds the live data, which is
 * what {@link SegmentDiscovery} looks for on the next start.</p>
 *
 * <h2>Ordering</h2>
 * <ol>
 *   <li>The old segment's write lock is held for the whole operation. Copying without it drops
 *       every write accepted between the end of the copy and the registry swap.</li>
 *   <li>The file swap is a single {@code Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)}
 *       performed here, by the one owner. There is no separate delete of the original: a
 *       failed swap leaves the original intact and removes only the scratch file.</li>
 *   <li>The registry swap happens before either segment is closed, so in-flight reads and
 *       writes are never handed a closed segment - and the close itself happens before the
 *       write lock is released, so the next compactor to take that lock cannot mistake a
 *       replaced segment for one worth rewriting.</li>
 * </ol>
 */
final class SegmentRewriter {

    private static final Logger LOGGER = Logger.getLogger(SegmentRewriter.class.getName());

    /**
     * Suffix of the scratch file a compacted segment is built at.
     *
     * <p>It is appended to the full segment path, e.g. {@code segment_0.dat.compact},
     * and is renamed onto {@code segment_0.dat} by the atomic swap. The suffix is never
     * matched by segment discovery, so a scratch file left behind by a crash is inert.</p>
     */
    private static final String COMPACT_SUFFIX = ".compact";

    private final Arena arena;
    private final CompactionListener listener;

    /**
     * Creates a rewriter over the arena replacements are mapped out of.
     *
     * @param arena    The arena new segment memory is allocated from
     * @param listener Who moves the routing registry onto a replacement
     */
    SegmentRewriter(Arena arena, CompactionListener listener) {
        this.arena = arena;
        this.listener = listener;
    }

    /**
     * Rewrites a segment, dropping its tombstoned entries.
     *
     * @param segment The segment to rewrite
     * @return What the attempt came to, including the work to credit it with
     * @throws IOException if the rewrite or the swap fails
     */
    Result rewrite(Segment segment) throws IOException {
        if (segment.isClosed()) {
            LOGGER.log(Level.FINE, "Skipping closed segment: {0}", segment.path());
            return Result.skipped();
        }

        long startTime = System.currentTimeMillis();
        Path oldPath = segment.path();
        Path compactPath = Path.of(oldPath.toString() + COMPACT_SUFFIX);

        LOGGER.log(Level.INFO, "Starting compaction for segment: {0}", oldPath);

        int oldTombstoneCount = segment.tombstoneCount();
        long bytesToReclaim = segment.estimatedReclaimableBytes();

        Files.deleteIfExists(compactPath);

        Result result = rewriteUnderWriteLock(segment, compactPath, bytesToReclaim);
        if (result.installed()) {
            long duration = System.currentTimeMillis() - startTime;
            LOGGER.log(Level.INFO, "Compaction complete for {0}: {1} entries ({2} tombstones "
                + "removed) in {3}ms, reclaimed {4} bytes",
                new Object[] { oldPath, result.entriesCopied(), oldTombstoneCount, duration,
                    bytesToReclaim });
        }
        return result;
    }

    /**
     * Builds the replacement and installs it, with the segment's write lock held throughout.
     *
     * <p>Everything that can disturb the segment happens inside the one locked region: the
     * copy, the rename, the registry swap and the retirement of whichever segment the swap
     * leaves unreachable. A caller that releases the lock between any two of those hands
     * another compactor, or a split, a segment in a state neither of them can interpret.</p>
     *
     * @param segment        The segment to rewrite
     * @param compactPath    Scratch path to build the replacement at
     * @param bytesToReclaim Dead weight measured before the rewrite, to credit it with
     * @return What the attempt came to
     * @throws IOException if the rewrite or the swap fails
     */
    private Result rewriteUnderWriteLock(Segment segment, Path compactPath, long bytesToReclaim)
            throws IOException {
        Path oldPath = segment.path();
        Path writeLogPath = getWriteLogPath(segment);

        // The same geometry as the segment being replaced. The constructor adds the data
        // region itself, so this passes the slot table alone.
        long capacity = Segment.capacityForSlots(segment.slotCount());

        int entriesCopied;
        CompactionOutcome outcome;
        Segment newSegment = null;
        Lock writeLock = segment.writeLock();
        writeLock.lock();
        try {
            if (!isCompactable(segment)) {
                return Result.skipped();
            }

            // The threshold the segment being replaced was created with, so a compaction
            // does not quietly reset the engine's configured load factor to the default.
            newSegment = new Segment(compactPath, capacity, arena, segment.loadFactorThreshold());
            entriesCopied = copyLiveEntries(segment, newSegment);
            newSegment.flush();

            swapIntoPlace(newSegment, compactPath, oldPath, writeLogPath);

            // Registry swap happens while the old segment is still open.
            outcome = requireOutcome(listener.onCompactionComplete(segment, newSegment), oldPath);

            // Whichever segment the registry has stopped routing to is retired here. Its
            // mapping and descriptor survive the rename that unlinked its file.
            retire(segment, newSegment, compactPath, outcome);
        } catch (Exception e) {
            discardCompacted(newSegment, compactPath);
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Compaction failed for segment: " + oldPath, e);
        } finally {
            writeLock.unlock();
        }

        if (outcome != CompactionOutcome.INSTALLED) {
            // Nothing is serving the rewritten file, so none of this work counts as reclaimed:
            // the space the tombstones hold is still held by whatever the registry does route
            // to, and reporting otherwise hides the failure from the operator.
            LOGGER.log(Level.WARNING, "Compaction of {0} was not installed: the listener "
                + "reported {1}", new Object[] { oldPath, outcome });
            return Result.skipped();
        }
        return new Result(true, entriesCopied, bytesToReclaim);
    }

    /**
     * Retires whichever segment the listener has left unreachable, under the compacted
     * segment's own write lock.
     *
     * <p>Closing the compacted segment here rather than after the lock is released is what
     * makes {@link #isCompactable(Segment)} a guard rather than a hint. The lock is reentrant,
     * so the close's own acquisition of it costs nothing; what matters is that {@code closed}
     * is set before the lock is released. A compactor queued on that lock - and one usually is,
     * because every lifecycle sweep hands its request to a fresh virtual thread - is granted it
     * the instant it is free, and a segment closed after the release is one that compactor sees
     * as open and rewrites a second time. That second rewrite moves its stale output over the
     * canonical segment name and discards the write log of the replacement already serving from
     * it, which loses every write accepted since the first swap with nothing to show for it
     * until the next start.</p>
     *
     * <p>A failure to retire is logged rather than propagated. The swap is already decided by
     * the time this runs, so there is nothing left to unwind: raising from here would take the
     * caller's error path, which discards a segment that may be the one being served.</p>
     *
     * @param segment     The compacted segment, with its write lock held by the calling thread
     * @param newSegment  The replacement built by this rewrite
     * @param compactPath Scratch path the replacement was built at
     * @param outcome     What the listener reported the registry now holds
     */
    private static void retire(Segment segment, Segment newSegment, Path compactPath,
                               CompactionOutcome outcome) {
        try {
            if (outcome.retiresCompactedSegment()) {
                segment.close();
            }
            if (outcome.discardsReplacement()) {
                discardCompacted(newSegment, compactPath);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to retire a segment after compacting "
                + segment.path() + " (" + outcome + "); its mapping and descriptor stay open "
                + "for the life of the process", e);
        }
    }

    /**
     * Checks that a listener answered the one question the rewrite cannot answer itself.
     *
     * <p>There is no safe default for a missing outcome: guessing that the swap was installed
     * retires a segment the registry may still route to, and guessing that it was refused
     * discards one that may already be serving. A listener that returns nothing has broken its
     * contract, and the compaction is failed as such.</p>
     *
     * @param outcome What the listener returned
     * @param oldPath Path of the segment being compacted, for the message
     * @return The outcome, never null
     * @throws IllegalStateException if the listener returned no outcome
     */
    private static CompactionOutcome requireOutcome(CompactionOutcome outcome, Path oldPath) {
        if (outcome == null) {
            throw new IllegalStateException(
                "Compaction listener returned no outcome for " + oldPath
                    + "; it must report whether the registry swap was installed");
        }
        return outcome;
    }

    /**
     * Whether a segment may still be compacted, checked under its write lock.
     *
     * <p>The checks before the lock are advisory: a split can begin, or another compaction can
     * finish, between them and the copy. {@link Segment#beginSplit()} takes the very lock this
     * runs under, so a split that has not started by now cannot start until this compaction has
     * swapped and released - and one that has started already is caught here, before any file is
     * written.</p>
     *
     * <p>Compacting through a split is the way this ends badly. The registry swap is what makes
     * the compacted file the live one, and a split publishes children over the same directory
     * entry; whichever loses, the compacted file has already replaced the canonical name and the
     * original's write log has already been discarded. Refusing early costs one skipped
     * compaction, and the segment - or its children - become candidates again immediately.</p>
     *
     * @param segment The segment being considered, with its write lock held
     * @return true if the compaction may proceed
     */
    private static boolean isCompactable(Segment segment) {
        if (segment.isClosed()) {
            LOGGER.log(Level.FINE, "Skipping segment closed before compaction started: {0}",
                segment.path());
            return false;
        }
        if (segment.isSplitting() || segment.isSplitComplete()) {
            LOGGER.log(Level.INFO,
                "Skipping compaction of {0}: the segment is being split, and its children are "
                    + "compacted on their own account",
                segment.path());
            return false;
        }
        return true;
    }

    /**
     * Copies every live entry from the old segment into the compacted segment.
     *
     * @param segment    The segment being compacted (its write lock is held)
     * @param newSegment The compacted segment, not yet visible to any other thread
     * @return Number of entries copied
     */
    private static int copyLiveEntries(Segment segment, Segment newSegment) {
        int entriesCopied = 0;
        try (Segment.EntryIterator iterator = segment.liveEntries()) {
            while (iterator.hasNext()) {
                var entry = iterator.next();
                // putWithoutLock is safe: no other thread can reach newSegment yet
                newSegment.putWithoutLock(entry.key(), entry.value());
                entriesCopied++;
            }
        }
        return entriesCopied;
    }

    /**
     * Installs the compacted segment at the canonical segment name.
     *
     * <p>The rename is atomic, so the canonical name always resolves to a complete
     * segment file. The mapping and descriptor of both segments follow their inodes,
     * so the old segment keeps serving reads until it is closed.</p>
     *
     * <p>The compacted segment starts from an empty write log at the canonical WAL
     * name: everything the old log contained is already in the compacted file.</p>
     *
     * @param newSegment   The compacted segment
     * @param compactPath  Scratch path the compacted segment was built at
     * @param canonicalPath The canonical segment path to install it at
     * @param writeLogPath The canonical write log path, or null if the segment has none
     * @throws IOException if the swap or the new write log cannot be created
     */
    private static void swapIntoPlace(Segment newSegment, Path compactPath, Path canonicalPath,
                                      Path writeLogPath) throws IOException {
        Files.move(compactPath, canonicalPath,
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        newSegment.renamedTo(canonicalPath);

        if (writeLogPath != null) {
            Files.deleteIfExists(writeLogPath);
            newSegment.attachWriteLog(new WriteLog(writeLogPath));
            LOGGER.log(Level.FINE, "Attached write log to compacted segment: {0}", writeLogPath);
        }
    }

    /**
     * Removes a compacted segment the registry never took.
     *
     * <p>Only the scratch file is deleted, never a canonical segment name. A rewrite that
     * failed before the swap leaves its original in place, and one the listener refused after
     * the swap leaves the replacement's file where the rename put it - deleting that would
     * take the canonical name away from a key range that has no other copy of it.</p>
     *
     * @param newSegment  The compacted segment, or null if it was never created
     * @param compactPath The scratch path to remove
     */
    private static void discardCompacted(Segment newSegment, Path compactPath) {
        if (newSegment != null && !newSegment.isClosed()) {
            newSegment.close();
        }
        try {
            Files.deleteIfExists(compactPath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to remove abandoned compaction scratch file: "
                + compactPath, e);
        }
    }

    /**
     * Gets the write log path for a segment.
     *
     * <p>The write log convention established by SegmentDiscovery is:
     * {@code segment_<index>.wal} (e.g., segment_0.wal), not
     * {@code segment_<index>.dat.wal}.</p>
     *
     * <p>This method first attempts to use the segment's attached write log path
     * if available. If not, it derives the path from the segment path using
     * the correct convention.</p>
     *
     * @param segment The segment
     * @return The write log path, or null if not applicable
     */
    private static Path getWriteLogPath(Segment segment) {
        // Use the segment's attached write log path if available
        WriteLog writeLog = segment.writeLog();
        if (writeLog != null) {
            return writeLog.path();
        }
        // Fallback: derive from segment path using correct convention
        Path segmentPath = segment.path();
        if (segmentPath == null) {
            return null;
        }
        // segment_0.dat -> segment_0.wal (not segment_0.dat.wal)
        String filename = segmentPath.getFileName().toString();
        if (filename.endsWith(".dat")) {
            String baseName = filename.substring(0, filename.length() - 4);
            return segmentPath.getParent().resolve(baseName + ".wal");
        }
        return segmentPath.getParent().resolve(filename + ".wal");
    }

    /**
     * What one attempt to rewrite a segment came to.
     *
     * @param installed      whether the replacement is the segment the registry now routes to
     * @param entriesCopied  live entries written into the replacement
     * @param bytesReclaimed dead weight the rewrite dropped, zero unless it was installed
     */
    record Result(boolean installed, int entriesCopied, long bytesReclaimed) {

        /**
         * The result of a rewrite that never happened.
         *
         * @return A result crediting no work at all
         */
        static Result skipped() {
            return new Result(false, 0, 0L);
        }
    }
}
