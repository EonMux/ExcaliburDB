package io.excalibur.store;

import io.excalibur.store.format.LogEntry;

import java.io.IOException;

/**
 * Every way of changing one {@link Segment}, and the write-ahead log that has to agree with
 * the result.
 *
 * <h2>The ordering problem</h2>
 * <p>A mutation touches two things - the mapping and the log - and the order has to satisfy
 * two rules at once:</p>
 * <ol>
 *   <li>A write the segment rejects must never be in the log. Tail-scan queries read the log
 *       live and a restart replays it, so a record there is a write that happened.</li>
 *   <li>A write the segment applies must always be in the log. Otherwise an acknowledged
 *       write is lost on restart - or, worse, a write the caller was told had failed is
 *       readable now and gone after a crash.</li>
 * </ol>
 *
 * <p>Which order gives both depends on which of the two can still refuse. An overwrite cannot:
 * once the load factor and the data region have been checked there is nothing left to
 * discover, so the record goes into the log first and the segment follows a step that cannot
 * fail. A delete is the same. An insertion is not: whether a Robin Hood placement is
 * admissible is discovered only by attempting it, so the placement goes first - and because
 * the log can still fail at its fsync, the placement records what it changed so that it can
 * be taken back. Being able to take it back is what makes mutating first safe, and it is
 * possible only because a displacement moves slot metadata and copies no entry data.</p>
 *
 * <h2>The load-factor guard applies to insertions only</h2>
 * <p>An overwrite consumes no new slot, so applying the guard to it would have a segment at
 * the boundary reject updates it can trivially satisfy - and, worse, reject the replay of its
 * own already-acknowledged log, which fails the startup and keeps failing it.</p>
 *
 * <h2>A segment with no log</h2>
 * <p>Every segment the engine creates is given one. Reaching {@link #warnUnlogged()} means
 * the segment was built directly and its writes survive only if the mapping happens to be
 * written back; the warning is issued once per segment, because silence made that
 * indistinguishable from a healthy segment.</p>
 *
 * <p><strong>Thread safety:</strong> the two public-facing entry points take the segment's
 * write lock; the {@code WithoutLock} variants require the caller to hold it. The writability
 * check is made <em>inside</em> the lock, because a split sets its state under that same lock
 * and a check made outside it is a check-then-act that loses the write.</p>
 */
final class SegmentMutations {

    /** The segment being changed. */
    private final Segment owner;

    /**
     * Binds this write path to its segment.
     *
     * @param owner The owning segment
     */
    SegmentMutations(Segment owner) {
        this.owner = owner;
    }

    /**
     * Applies a put to the segment. The caller must hold the write lock.
     *
     * <p>Ordering matters here, and it has to satisfy two things at once:</p>
     * <ol>
     *   <li>A write the segment rejects must never be in the log. Tail-scan queries read the
     *       log live and restart replays it, so a record there is a write that happened.</li>
     *   <li>A write the segment applies must always be in the log. Otherwise an acknowledged
     *       write is lost on restart - or, worse, a write the caller was told had failed is
     *       readable now and gone after a crash.</li>
     * </ol>
     *
     * <p>Which order gives both depends on which of the two can still refuse. An overwrite
     * cannot: once the load factor and the data region have been checked there is nothing left
     * to discover, so the record goes into the log first and the segment follows a step that
     * cannot fail. An insertion can: whether a Robin Hood placement is admissible is
     * discovered only by attempting it, so the placement goes first - and because the log can
     * still fail at its fsync, the placement records what it changed so that it can be taken
     * back. Taking the placement back is what makes mutating first safe, and it is possible
     * only because a displacement moves slot metadata and copies no entry data.</p>
     *
     * <p>The load-factor guard is evaluated only for a genuine insert, because an overwrite
     * consumes no new slot and a segment at the boundary would otherwise reject updates it can
     * trivially satisfy.</p>
     *
     * @param key   The key (already validated)
     * @param value The value (already validated)
     * @throws SegmentCapacityExceededException if the segment cannot admit the write
     * @throws IllegalStateException            if the log cannot record it
     */
    void putInternal(byte[] key, byte[] value) {
        long keyHash = owner.records.computeHash(key);
        int existingSlot = owner.slots.findSlot(keyHash, key);

        if (existingSlot >= 0) {
            overwrite(existingSlot, key, value);
        } else {
            insert(keyHash, key, value);
        }
    }

    /**
     * Replaces the value of an entry that is already present, logging the record first.
     *
     * <p>The data region check is hoisted out of {@link SegmentSlots#updateEntry(int, byte[], byte[])} deliberately: it is the
     * only way an overwrite can still be refused, and it has to be answered before the record
     * reaches the log so that a tail scan never reads a write the segment went on to reject.
     * With it answered here, everything after the append is a write that cannot fail.</p>
     *
     * @param slotIndex The slot holding the entry
     * @param key       The key (already validated)
     * @param value     The replacement value (already validated)
     */
    void overwrite(int slotIndex, byte[] key, byte[] value) {
        owner.records.requireDataSpace(overwriteDataSpaceNeeded(slotIndex, key, value));

        appendPutToLog(key, value);

        // Before the bytes change, so that a slice handed out earlier is already stale by the
        // time it could be read against the new contents.
        owner.modificationEpoch++;
        owner.slots.updateEntry(slotIndex, key, value);
    }

    /**
     * Adds an entry that is not present, logging the record once the placement has succeeded.
     *
     * @param keyHash The key's hash
     * @param key     The key (already validated)
     * @param value   The value (already validated)
     */
    void insert(long keyHash, byte[] key, byte[] value) {
        if (owner.split.isFull()) {
            double loadFactor = (double) (owner.entryCount + 1) / owner.slotCount;
            throw new SegmentCapacityExceededException(
                SegmentCapacityExceededException.Reason.LOAD_FACTOR,
                "Load factor " + loadFactor + " would reach or exceed threshold " + owner.loadFactorThreshold);
        }
        owner.records.requireDataSpace(owner.records.entryHeaderSize() + key.length + value.length + Segment.ENTRY_TRAILER_SIZE);
        reserveLogSpace(LogEntry.OPCODE_PUT, key, value);

        RobinHoodPlacement placement = owner.slots.insertEntry(keyHash, key, value);
        owner.modificationEpoch++;

        try {
            appendPutToLog(key, value);
        } catch (RuntimeException e) {
            try {
                owner.slots.undoInsert(placement);
            } catch (RuntimeException undoFailure) {
                // The caller needs to be told why the write failed, not why the tidying up
                // afterwards did, but a segment left half-undone is the more serious of the
                // two and must not disappear.
                e.addSuppressed(undoFailure);
            }
            throw e;
        }
    }

    /**
     * Returns the data region bytes an overwrite of this slot would consume.
     *
     * <p>A replacement value that is no longer than the one it replaces is written over the
     * existing record and consumes nothing; a longer one is appended and the slot repointed at
     * it, which is the only case the data region has to be able to take.</p>
     *
     * @param slotIndex The slot holding the entry
     * @param key       The key
     * @param value     The replacement value
     * @return Bytes the overwrite needs in the data region, zero if it fits in place
     */
    int overwriteDataSpaceNeeded(int slotIndex, byte[] key, byte[] value) {
        long entryOffset = owner.slotTableSize + owner.slots.readDataOffset(slotIndex);
        int existingKeyLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_KEY_LEN_OFFSET);
        int existingValueLen = owner.segment.get(Segment.INT_LAYOUT, entryOffset + Segment.ENTRY_VALUE_LEN_OFFSET);
        int existingSize = owner.records.entryHeaderSize() + existingKeyLen + existingValueLen
            + Segment.ENTRY_TRAILER_SIZE;
        int newSize = owner.records.entryHeaderSize() + key.length + value.length + Segment.ENTRY_TRAILER_SIZE;

        return newSize <= existingSize ? 0 : newSize;
    }

    /**
     * Says once that this segment is accepting mutations that nothing is logging.
     *
     * <p>Every segment the engine creates - on discovery, on creation, on a split, on a
     * compaction - is given a log, so reaching here means the segment was built directly and
     * its writes survive only if the mapping happens to be written back. Silence made that
     * indistinguishable from a healthy segment.</p>
     */
    void warnUnlogged() {
        if (owner.unloggedWarningIssued.compareAndSet(false, true)) {
            Segment.LOGGER.log(java.util.logging.Level.SEVERE,
                "Segment {0} has no write-ahead log attached: its mutations are not logged and "
                + "will not survive a crash.", owner.path);
        }
    }

    /**
     * Asks the attached log whether it could record an insertion, before the segment makes it.
     *
     * <p>An insertion is the one mutation that runs before its record is logged, so this is
     * what keeps a placement from being made and then taken back again over something as
     * ordinary as a full log. What it cannot rule out is the device refusing the record at the
     * fsync, which is why the placement is undoable as well.</p>
     *
     * <p>A segment with no log has already been warned about by {@link #warnUnlogged()} on the
     * append that follows, so this stays silent rather than warning twice per write.</p>
     *
     * @param opcode The operation the log will be asked to record
     * @param key    The key
     * @param value  The value, empty for a delete
     * @throws IllegalStateException if the log could not take it
     */
    void reserveLogSpace(byte opcode, byte[] key, byte[] value) {
        WriteLog log = owner.writeLog;
        if (log == null) {
            return;
        }
        try {
            log.reserve(opcode, key, value);
        } catch (IOException e) {
            throw new IllegalStateException("Write-ahead log cannot record the write", e);
        }
    }

    /**
     * Appends a PUT record to the attached write-ahead log, if any.
     *
     * @param key   The key
     * @param value The value
     * @throws IllegalStateException if the log append fails
     */
    void appendPutToLog(byte[] key, byte[] value) {
        WriteLog log = owner.writeLog;
        if (log == null) {
            warnUnlogged();
            return;
        }
        try {
            log.appendPut(key, value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append to write-ahead log", e);
        }
    }

    /**
     * Appends a DELETE record to the attached write-ahead log, if any.
     *
     * @param key The key
     * @throws IllegalStateException if the log append fails
     */
    void appendDeleteToLog(byte[] key) {
        WriteLog log = owner.writeLog;
        if (log == null) {
            warnUnlogged();
            return;
        }
        try {
            log.appendDelete(key);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append to write-ahead log", e);
        }
    }

    /**
     * Inserts an entry during recovery without appending to the write log.
     *
     * <p>This is used by {@link Segment#recoverFromLog()} to replay entries from the
     * write log without creating new log entries.</p>
     *
     * @param keyHash The pre-computed key hash
     * @param key The key bytes
     * @param value The value bytes
     */
    void insertEntryForRecovery(long keyHash, byte[] key, byte[] value) {
        // Replaying an update of an already-present key adds no entry, so the
        // load-factor guard must not be applied to it - otherwise a segment sitting
        // at its capacity boundary can never replay its own log.
        int existingSlot = owner.slots.findSlot(keyHash, key);
        if (existingSlot >= 0) {
            owner.slots.updateEntry(existingSlot, key, value);
            return;
        }

        if (owner.split.isFull()) {
            throw new SegmentCapacityExceededException(
                SegmentCapacityExceededException.Reason.LOAD_FACTOR,
                "Recovery would exceed load factor threshold. Segment needs splitting.");
        }

        int entrySize = owner.records.entryHeaderSize() + key.length + value.length + Segment.ENTRY_TRAILER_SIZE;
        owner.records.requireDataSpace(entrySize);

        owner.slots.insertEntry(keyHash, key, value);
    }

    /**
     * Deletes an entry without logging (internal operation).
     *
     * @param key The key to delete
     * @return true if deleted, false if not found
     */
    boolean deleteInternal(byte[] key) {
        long keyHash = owner.records.computeHash(key);
        int slotIndex = owner.slots.findSlot(keyHash, key);

        if (slotIndex < 0 || owner.slots.isSlotTombstoned(slotIndex)) {
            return false;
        }

        owner.slots.markAsTombstoned(slotIndex);
        return true;
    }

    /**
     * Applies a put under the segment's write lock.
     *
     * <p>The writability check is made inside the lock, not before it.
     * {@code Segment.beginSplit()} and {@code Segment.close()} both take this same write lock
     * to set their state, so a check made outside it is a check-then-act: the write blocks
     * acquiring the lock, the split snapshots the segment while it waits, and the write then
     * lands in a segment whose contents have already been copied into the children. The
     * caller is told it succeeded and the entry is gone.</p>
     *
     * @param key   The key
     * @param value The value
     */
    void put(byte[] key, byte[] value) {
        owner.records.validateKey(key);
        owner.records.validateValue(value);

        owner.lock.writeLock().lock();
        try {
            // Inside the lock, not before it. beginSplit() and close() both take this same
            // write lock to set their state, so a check made outside it is a check-then-act:
            // the write blocks acquiring the lock, the split snapshots the segment while it
            // waits, and the write then lands in a segment whose contents have already been
            // copied into the children. The caller is told it succeeded and the entry is gone.
            owner.split.requireWritable();
            putInternal(key, value);
        } finally {
            owner.lock.writeLock().unlock();
        }
    }

    /**
     * Tombstones a key under the segment's write lock.
     *
     * <p>Both refusals - split state and closure - are behind us before anything is written,
     * and tombstoning itself cannot fail, so the record is logged first and the segment
     * follows. Tombstoning first would mean a delete whose record the device refused had
     * already taken effect: the caller is told the delete failed, a reader no longer finds
     * the key, and a restart brings it back.</p>
     *
     * <p>{@code entryCount} is deliberately not decremented. The slot stays occupied until a
     * compaction rebuilds the segment, so it still consumes probe budget;
     * {@code liveEntryCount} is what falls.</p>
     *
     * @param key The key
     * @return true if the key was found and tombstoned
     */
    boolean delete(byte[] key) {
        owner.records.validateKey(key);

        owner.lock.writeLock().lock();
        try {
            // Inside the lock, for the same reason as put: an acknowledged delete that a
            // concurrent split discards leaves the key readable through the children.
            owner.split.requireWritable();
            long keyHash = owner.records.computeHash(key);
            int slotIndex = owner.slots.findSlot(keyHash, key);

            if (slotIndex < 0) {
                return false; // Not found
            }

            // Check if already tombstoned
            if (owner.slots.isSlotTombstoned(slotIndex)) {
                return false;
            }

            // Both refusals are already behind us and tombstoning cannot fail, so the record
            // is logged first and the segment follows. Tombstoning first would mean a delete
            // whose record the device refused had already taken effect: the caller is told the
            // delete failed, a reader no longer finds the key, and a restart brings it back.
            appendDeleteToLog(key);

            // Increment modification epoch to invalidate existing zero-copy slices
            owner.modificationEpoch++;

            owner.slots.markAsTombstoned(slotIndex);

            // Note: entryCount is NOT decremented for tombstones - the slot stays
            // occupied until compaction. liveEntryCount is decremented instead.

            return true;
        } finally {
            owner.lock.writeLock().unlock();
        }
    }

    /**
     * Applies a put with no lock of its own; the caller holds the write lock.
     *
     * @param key   The key
     * @param value The value
     */
    void putWithoutLock(byte[] key, byte[] value) {
        owner.validateNotClosed();
        owner.records.validateKey(key);
        owner.records.validateValue(value);

        // Caller must hold write lock - no lock acquisition here
        putInternal(key, value);
    }

    /**
     * Tombstones a key with no lock of its own; the caller holds the write lock.
     *
     * @param key The key
     * @return true if the key was found and tombstoned
     */
    boolean deleteWithoutLock(byte[] key) {
        owner.validateNotClosed();
        owner.records.validateKey(key);

        // Caller must hold write lock - no lock acquisition here

        long keyHash = owner.records.computeHash(key);
        int slotIndex = owner.slots.findSlot(keyHash, key);

        if (slotIndex < 0) {
            return false; // Not found
        }

        // Check if already tombstoned
        if (owner.slots.isSlotTombstoned(slotIndex)) {
            return false;
        }

        // The record is logged before the segment changes, for the reason delete() gives.
        appendDeleteToLog(key);

        // Increment modification epoch to invalidate existing zero-copy slices
        owner.modificationEpoch++;

        owner.slots.markAsTombstoned(slotIndex);

        return true;
    }

    /**
     * Replays the whole write-ahead log back into the segment.
     *
     * <p>Opening the log has already established that every record up to its head reads
     * cleanly, so a record that will not read during the replay means the log changed while
     * it was being read. Whatever the cause, the segment then holds some prefix of the state
     * its log describes and there is no way to tell which - so this fails rather than
     * returning, and the engine treats that as a reason not to start.</p>
     *
     * @throws CorruptLogException if the log could not be replayed in full
     * @throws java.io.IOException if reading the log fails
     */
    void recoverFromLog() throws IOException {
        owner.validateNotClosed();
        if (owner.writeLog == null) {
            throw new IllegalStateException("No write log attached to segment");
        }

        owner.lock.writeLock().lock();
        try {
            // Replay all entries from the log
            boolean complete = owner.writeLog.recover(entry -> {
                byte[] key = entry.key();
                byte[] value = entry.value();

                if (entry.opcode() == LogEntry.OPCODE_PUT) {
                    // Direct insert without logging (we're recovering from log)
                    insertEntryForRecovery(entry.keyHash(), key, value);
                } else if (entry.opcode() == LogEntry.OPCODE_DELETE) {
                    // Direct delete without logging
                    deleteInternal(key);
                }
            });

            if (!complete) {
                throw new CorruptLogException(owner.writeLog.path(), owner.writeLog.firstCorruptOffset(),
                    "Write-ahead log " + owner.writeLog.path() + " could not be replayed in full for "
                    + "segment " + owner.path + ": " + owner.writeLog.corruptEntriesFound() + " record(s) "
                    + "were unreadable, the first at offset " + owner.writeLog.firstCorruptOffset()
                    + ". The segment's committed state cannot be reconstructed.");
            }
        } finally {
            owner.lock.writeLock().unlock();
        }
    }
}
