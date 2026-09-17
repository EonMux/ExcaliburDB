package excalibur.filter;

import io.excalibur.core.integrity.IntegrityStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Cuckoo filter's verification entry point and its handling of older images.
 */
@DisplayName("CuckooFilter verification")
class CuckooFilterVerificationTest {

    /** Header size of a version 1 image. */
    private static final int LEGACY_HEADER_SIZE = 32;

    /** Header size of a version 2 image. */
    private static final int HEADER_SIZE = 64;

    @Test
    @DisplayName("an image written by writeTo verifies as intact")
    void writtenImageVerifiesIntact() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = writeImage(arena);

            assertThat(CuckooFilter.verifyIntegrity(image, 0).status())
                .isEqualTo(IntegrityStatus.INTACT);
        }
    }

    @Test
    @DisplayName("a live filter's own memory is reported unsealed, not intact")
    void liveFilterMemoryIsReportedUnsealed() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = writeImage(arena);
            CuckooFilter restored = CuckooFilter.readFrom(image, 0);
            assertThat(restored.contains(term(0))).isTrue();

            assertThat(CuckooFilter.verifyIntegrity(image, 0).status())
                .isEqualTo(IntegrityStatus.UNSEALED);
        }
    }

    @Test
    @DisplayName("a damaged image is reported corrupt, never read as a filter of misses")
    void damagedImageIsReportedCorrupt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment image = writeImage(arena);
            byte original = image.get(ValueLayout.JAVA_BYTE, 200);
            image.set(ValueLayout.JAVA_BYTE, 200, (byte) (original ^ 0x40));

            var report = CuckooFilter.verifyIntegrity(image, 0);

            assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.loadable()).isFalse();
        }
    }

    @Test
    @DisplayName("an image written before checksums existed still reads, named as unprotected")
    void olderImageStillReads() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment current = writeImage(arena);
            MemorySegment legacy = toLegacyImage(arena, current);

            assertThat(CuckooFilter.verifyIntegrity(legacy, 0).status())
                .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);

            CuckooFilter restored = CuckooFilter.readFrom(legacy, 0);
            for (int i = 0; i < 50; i++) {
                assertThat(restored.contains(term(i))).isTrue();
            }
        }
    }

    @Test
    @DisplayName("writing out a filter attached to an older image leaves its buckets intact")
    void writingOutAnOlderImageKeepsItsBuckets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment legacy = toLegacyImage(arena, writeImage(arena));
            CuckooFilter restored = CuckooFilter.readFrom(legacy, 0);

            MemorySegment copy = arena.allocate(restored.byteSize());
            restored.writeTo(copy, 0);

            CuckooFilter reread = CuckooFilter.readFrom(copy, 0);
            for (int i = 0; i < 50; i++) {
                assertThat(reread.contains(term(i))).isTrue();
            }
        }
    }

    /**
     * Serializes a populated filter into freshly allocated memory.
     */
    private static MemorySegment writeImage(Arena arena) {
        CuckooFilter filter = CuckooFilter.create(500);
        for (int i = 0; i < 50; i++) {
            filter.insert(term(i));
        }
        MemorySegment image = arena.allocate(filter.byteSize());
        filter.writeTo(image, 0);
        return image;
    }

    /**
     * Rewrites a version 2 image as the version 1 image the same filter would have produced:
     * the same buckets behind a 32-byte header with no integrity block.
     */
    private static MemorySegment toLegacyImage(Arena arena, MemorySegment current) {
        long buckets = current.get(
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16);
        long payload = current.byteSize() - HEADER_SIZE;
        MemorySegment legacy = arena.allocate(LEGACY_HEADER_SIZE + payload);
        MemorySegment.copy(current, 0, legacy, 0, LEGACY_HEADER_SIZE);
        MemorySegment.copy(current, HEADER_SIZE, legacy, LEGACY_HEADER_SIZE, payload);
        legacy.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 4, 1);
        legacy.set(ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN), 16, buckets);
        return legacy;
    }

    private static byte[] term(int index) {
        return ("term-" + index).getBytes(StandardCharsets.UTF_8);
    }
}
