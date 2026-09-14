package io.excalibur.graph.file;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Tells a torn tail apart from corruption in the middle of an append-only log.
 *
 * <h2>The mistake this exists to prevent</h2>
 * <p>Every log in this module replays to the first record it cannot read and treats what came
 * before as the truth. For a process killed mid-append that is exactly right: the partial record
 * is the only thing lost, and it was never acknowledged to anybody. The replay cannot tell that
 * case apart from the other one, though, and the other one is not survivable. A write that failed
 * for a reason the file outlives - a full disk, a quota, a device error - leaves a partial record
 * with perfectly good records appended after it, because the next caller appends wherever the file
 * now ends and is told its write succeeded. Replaying to the first bad record and truncating there
 * then destroys every record written after the failure, silently, and reports a healthy log.</p>
 *
 * <p>The two cases are distinguishable by looking past the break: a torn tail has nothing readable
 * after it, and corruption does. This scans for that, over byte offsets rather than record
 * boundaries, because a record cut short leaves everything after it at an offset no framing points
 * at.</p>
 *
 * <h2>Which way to be wrong</h2>
 * <p>A scan over byte offsets can in principle find a checksum that matches by accident, calling a
 * torn tail corruption and refusing to open a log that was fine. Every record these logs hold is
 * an obligation to destroy data, so that is the direction to err in: an operator faced with a log
 * that will not open still has the log, whereas one whose obligations were truncated away has
 * nothing left to tell them what was owed.</p>
 *
 * @author ExcaliburDB
 */
public final class TailIntegrity {

    private TailIntegrity() {
        throw new AssertionError("Utility class");
    }

    /** Recognises a well-formed record at a byte offset. */
    @FunctionalInterface
    public interface RecordScan {

        /**
         * Whether a complete, checksum-clean record begins at {@code offset}.
         *
         * <p>Must not throw for an offset that holds nonsense; nonsense is the expected input.
         *
         * @param contents The whole file
         * @param offset   Where to look
         * @return true when a record starts exactly there
         */
        boolean startsAt(byte[] contents, int offset);
    }

    /**
     * The first offset at or after {@code from} holding a well-formed record, or -1 for none.
     *
     * @param contents The whole file
     * @param from     Where the readable records stopped
     * @param scan     Recognises this log's framing
     * @return The offset, or -1
     */
    public static int firstIntactRecordFrom(byte[] contents, int from, RecordScan scan) {
        for (int offset = Math.max(from, 0); offset < contents.length; offset++) {
            if (scan.startsAt(contents, offset)) {
                return offset;
            }
        }
        return -1;
    }

    /**
     * Asserts that what follows {@code validBytes} is a torn tail and not corruption.
     *
     * <p>Called before anything truncates to {@code validBytes}, so that the truncation only ever
     * removes bytes nothing could have read anyway.
     *
     * @param file       The log, named in the failure
     * @param what       What the log is, for an operator reading the message
     * @param contents   The whole file
     * @param validBytes Where the records that replayed cleanly ended
     * @param scan       Recognises this log's framing
     * @throws IOException if a well-formed record follows the break
     */
    public static void requireTornTail(Path file, String what, byte[] contents, long validBytes,
                                       RecordScan scan) throws IOException {
        if (validBytes >= contents.length) {
            return;
        }
        int intact = firstIntactRecordFrom(contents, (int) validBytes + 1, scan);
        if (intact < 0) {
            return;
        }
        throw new IOException(what + " " + file + " is unreadable from byte " + validBytes
            + ", but holds a further intact record at byte " + intact + ". That is corruption in "
            + "the middle of the log rather than a write cut short at the end, so the records "
            + "after the damage were written and acknowledged. Truncating to the damage would "
            + "destroy them without trace; this build refuses to open the log instead. Recover or "
            + "quarantine the file before restarting.");
    }
}
