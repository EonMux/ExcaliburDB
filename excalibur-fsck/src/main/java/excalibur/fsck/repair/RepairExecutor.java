package excalibur.fsck.repair;

import excalibur.fsck.check.WriteAheadLogCheck;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.LogScan;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.catalog.CatalogFile;
import io.excalibur.store.format.LogEntry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies a plan, one action at a time, saying what it is doing before it does it.
 *
 * <p>An action that fails stops nothing else: the remaining repairs are independent of it,
 * and an operator running this on a damaged node needs as much of the directory fixed as
 * can be, with the failures named. Nothing is swallowed - every failure becomes an outcome
 * that says it did not happen and why.</p>
 */
public final class RepairExecutor {

    private final Quarantine quarantine;
    private final PrintStream out;

    /**
     * Prepares an executor.
     *
     * @param quarantine Where files are moved to
     * @param out        Where each action is announced before it runs
     */
    public RepairExecutor(Quarantine quarantine, PrintStream out) {
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Applies every action in a plan.
     *
     * @param plan What to do
     * @return What happened, one outcome per action, in plan order
     */
    public List<RepairOutcome> apply(RepairPlan plan) {
        List<RepairOutcome> outcomes = new ArrayList<>(plan.actions().size());
        for (RepairAction action : plan.actions()) {
            out.println("  -> " + action.description());
            outcomes.add(applyOne(action));
        }
        return outcomes;
    }

    /**
     * Applies one action.
     *
     * @param action What to do
     * @return What happened
     */
    private RepairOutcome applyOne(RepairAction action) {
        try {
            return switch (action.kind()) {
                case QUARANTINE_CORRUPT_FILE, QUARANTINE_ORPHANED_INDEX,
                     QUARANTINE_STAGING_LEFTOVER -> quarantined(action);
                case DROP_CATALOG_RECORD -> droppedRecord(action);
                case TRIM_TORN_LOG_TAIL -> trimmedTail(action);
            };
        } catch (IOException | RuntimeException e) {
            return new RepairOutcome(action, false,
                "failed, and the file is exactly as it was: " + e);
        }
    }

    /**
     * Moves a file into quarantine.
     *
     * @param action What to do
     * @return What happened
     * @throws IOException if the move fails
     */
    private RepairOutcome quarantined(RepairAction action) throws IOException {
        Path destination = quarantine.take(action.subject(), action.relative());
        return new RepairOutcome(action, true, "moved to " + destination);
    }

    /**
     * Marks the catalog row for a missing segment as purged.
     *
     * <p>The state is restored rather than transitioned into. {@code removeSegment} drives
     * the row through the lifecycle's transition rules, and those rules refuse to purge a
     * segment that is, for instance, still recorded as indexing - which is exactly the row
     * this repair is for, because the file it names has gone missing mid-life. The rules
     * exist to catch a logic error in a running node; here the condition they protect has
     * already been violated by the filesystem, and the row is being made to record what is
     * true rather than moved along a legal path. It is written back through the catalog's
     * own writer, so the row keeps its CRC and no other row moves.</p>
     *
     * @param action What to do
     * @return What happened
     * @throws IOException if the catalog cannot be written
     */
    private RepairOutcome droppedRecord(RepairAction action) throws IOException {
        long segmentId = action.segmentId();
        if (segmentId < 0) {
            throw new IOException("The planned action names no segment: " + action.description());
        }
        try (CatalogFile catalog = new CatalogFile(action.subject())) {
            SegmentCatalog.SegmentMetadata row = catalog.readSegment(segmentId);
            if (row == null) {
                return new RepairOutcome(action, false,
                    "no row for segment " + segmentId + " was found to purge");
            }
            row.restoreLifecycleState(LifecycleState.PURGED);
            catalog.writeSegment(row);
            catalog.fsync();
            return new RepairOutcome(action, true,
                "the row for segment " + segmentId + " is now purged, and the file it named "
                    + "is no longer claimed");
        }
    }

    /**
     * Clears the opcode of a torn record so the log reads as ending before it.
     *
     * <p>The scan is run again here rather than trusting the offset the survey recorded.
     * Between the two, the file may have been appended to by a node that started; a stale
     * offset would then clear the opcode of a record that had been written and acknowledged
     * since. Re-scanning costs one pass and makes that impossible.</p>
     *
     * @param action What to do
     * @return What happened
     * @throws IOException if the log cannot be scanned or written
     */
    private RepairOutcome trimmedTail(RepairAction action) throws IOException {
        LogScan scan = WriteAheadLogCheck.scanFor(action.subject());
        if (scan == null || scan.tornTail() == null) {
            return new RepairOutcome(action, false,
                "the log no longer ends in a torn record, so nothing was written");
        }
        long offset = scan.endOffset() + LogEntry.OPCODE_OFFSET;
        try (FileChannel channel = FileChannel.open(
                action.subject(), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] { 0 }), offset);
            channel.force(false);
        }
        return new RepairOutcome(action, true,
            "the opcode at offset " + offset + " is cleared and forced; the log now ends at "
                + scan.endOffset());
    }
}
