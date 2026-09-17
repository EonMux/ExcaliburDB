package excalibur.filter;

import io.excalibur.core.integrity.IndexChecksum;
import io.excalibur.core.integrity.IntegrityReport;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The integrity block of a serialized Cuckoo filter, and the rules for checking it.
 *
 * <h2>Layout</h2>
 * <p>Version 2 widened the header from 32 bytes to 64, leaving every field version 1 had
 * where it was and putting the integrity block behind them:</p>
 * <pre>
 *   offset  0  magic            (4 bytes)
 *   offset  4  version          (4 bytes)
 *   offset  8  fingerprint bits (4 bytes)
 *   offset 12  reserved         (4 bytes)
 *   offset 16  bucket count     (8 bytes)
 *   offset 24  seed             (8 bytes)
 *   offset 32  seal state       (4 bytes)
 *   offset 36  reserved         (4 bytes)
 *   offset 40  payload CRC      (8 bytes)  CRC32C of the bucket region
 *   offset 48  reserved         (8 bytes)  covered by the header CRC
 *   offset 56  header CRC       (8 bytes)  CRC32C of [0, 56)
 * </pre>
 * <p>All fields are big-endian, matching the rest of the filter.</p>
 *
 * <h2>Why the header CRC sits last</h2>
 * <p>A checksum cannot cover the field it is stored in, so wherever it sits it leaves a hole.
 * At the end of the header that hole is the end of the header, and every reserved byte before
 * it is digested - which means the next field added to this format is protected the day it is
 * added rather than the day somebody remembers to widen the covered range.</p>
 *
 * <h2>Seal state</h2>
 * <p>A live filter is mutated on every insert and delete, so no checksum can stand over it
 * while it is in use. {@link CuckooFilter#writeTo} seals the copy it writes, so a written
 * image carries a checksum while the filter that produced it does not. Attaching a filter to a
 * sealed image verifies it and then clears the seal, because that image is about to become a
 * live filter again.</p>
 *
 * <h2>What this protects today</h2>
 * <p>Nothing this engine keeps on disk. The one filter a running node holds is the membership
 * filter in front of the lexical index, and it is rebuilt from the terms column every time a
 * segment is opened rather than written out - which is the stronger arrangement, because a
 * filter derived from the dictionary it guards cannot have drifted from it. This block exists
 * so that {@code writeTo} and {@code readFrom} are honest for the callers that do use them,
 * and so that an image is checked on the day something starts persisting one, rather than
 * being given a format that has to grow a checksum later.</p>
 *
 * <h2>Version 1</h2>
 * <p>A version 1 image has a 32-byte header and no checksum. It is read as before and
 * reported as unprotected; it gains a checksum the first time it is written out again.</p>
 */
final class CuckooFilterIntegrity {

    /** Name used when a damaged image is reported. */
    static final String FORMAT = "Cuckoo filter";

    /** Seal state of an image whose bucket region has been digested. */
    static final int STATE_SEALED = 0x5345414C;

    /** Seal state of an image a filter is, or was, live over. */
    static final int STATE_OPEN = 0x4F50454E;

    /** Offset of the seal state field. */
    static final int OFFSET_STATE = 32;

    /** Offset of the bucket-region CRC32C. */
    static final int OFFSET_PAYLOAD_CRC = 40;

    /** Offset of the header CRC32C, the last field of the header. */
    static final int OFFSET_HEADER_CRC = 56;

    /** Number of leading header bytes the header CRC covers: everything but itself. */
    static final int HEADER_COVERED_BYTES = 56;

    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN);

    private CuckooFilterIntegrity() {
    }

    /**
     * Marks a freshly allocated image as live and stamps its header checksum.
     *
     * @param segment The image, positioned at its own offset zero
     */
    static void markOpen(MemorySegment segment) {
        segment.set(INT, OFFSET_STATE, STATE_OPEN);
        segment.set(LONG, OFFSET_PAYLOAD_CRC, 0L);
        segment.set(LONG, OFFSET_HEADER_CRC,
            IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES));
    }

    /**
     * Seals an image: digests its bucket region so a later read can check it.
     *
     * @param segment       The image, positioned at its own offset zero
     * @param payloadOffset Where the bucket region starts within the image
     * @param payloadLength How many bytes of buckets follow
     */
    static void seal(MemorySegment segment, long payloadOffset, long payloadLength) {
        segment.set(INT, OFFSET_STATE, STATE_SEALED);
        segment.set(LONG, OFFSET_PAYLOAD_CRC,
            IndexChecksum.of(segment, payloadOffset, payloadLength));
        segment.set(LONG, OFFSET_HEADER_CRC,
            IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES));
    }

    /**
     * Refuses an image whose stored checksums do not match its bytes.
     *
     * @param segment       The image, positioned at its own offset zero
     * @param version       The format version read from the header
     * @param payloadOffset Where the bucket region starts within the image
     * @param payloadLength How many bytes of buckets follow
     * @throws IllegalArgumentException if the image is sealed and damaged
     */
    static void requireIntact(MemorySegment segment, int version, long payloadOffset,
                              long payloadLength) {
        IntegrityReport report = inspect(segment, version, payloadOffset, payloadLength);
        if (report.loadable()) {
            return;
        }
        throw new IllegalArgumentException("This " + FORMAT + " image failed its checksum: "
            + report.detail() + ". It will not be loaded, because a membership filter that has "
            + "rotted answers 'no' for terms its segment really holds, which prunes the "
            + "segment out of every query that needs it. Rebuild the filter from its segment.");
    }

    /**
     * Reaches a verdict on an image without refusing anything.
     *
     * @param segment       The image, positioned at its own offset zero
     * @param version       The format version read from the header
     * @param payloadOffset Where the bucket region starts within the image
     * @param payloadLength How many bytes of buckets follow
     * @return What the check concluded
     */
    static IntegrityReport inspect(MemorySegment segment, int version, long payloadOffset,
                                   long payloadLength) {
        if (version < CuckooFilter.VERSION) {
            return IntegrityReport.legacy(null, FORMAT, version);
        }
        int state = segment.get(INT, OFFSET_STATE);
        if (state != STATE_SEALED) {
            return IntegrityReport.unsealed(null, FORMAT,
                "the image is not sealed, so its buckets cannot be verified; a filter that is "
                    + "live over it, or was, leaves it in this state");
        }
        long storedHeaderCrc = segment.get(LONG, OFFSET_HEADER_CRC);
        long actualHeaderCrc = IndexChecksum.of(segment, 0, HEADER_COVERED_BYTES);
        if (storedHeaderCrc != actualHeaderCrc) {
            return IntegrityReport.corrupt(null, FORMAT, "the header checksum is 0x"
                + Long.toHexString(storedHeaderCrc) + " but its " + HEADER_COVERED_BYTES
                + " bytes digest to 0x" + Long.toHexString(actualHeaderCrc)
                + ", so the bucket count, fingerprint width and seed cannot be trusted");
        }
        long storedPayloadCrc = segment.get(LONG, OFFSET_PAYLOAD_CRC);
        long actualPayloadCrc = IndexChecksum.of(segment, payloadOffset, payloadLength);
        if (storedPayloadCrc != actualPayloadCrc) {
            return IntegrityReport.corrupt(null, FORMAT, "the stored checksum over "
                + payloadLength + " bytes of buckets is 0x" + Long.toHexString(storedPayloadCrc)
                + " but they digest to 0x" + Long.toHexString(actualPayloadCrc)
                + ", so at least one fingerprint has changed since the filter was written");
        }
        return IntegrityReport.intact(null, FORMAT, payloadLength + " bytes of buckets verified");
    }
}
