package io.excalibur.core.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the shared integrity primitive now that it has one home.
 *
 * <p>Every persisted format in the engine digests its bytes through this class, so the three
 * overloads have to agree with each other and with {@link CRC32C} itself: a mapped index that
 * seals with one form and a checking tool that verifies with another would disagree on a file
 * nothing had touched.</p>
 */
@DisplayName("IndexChecksum")
class IndexChecksumTest {

    @TempDir
    Path tempDir;

    /** Bigger than the primitive's internal chunk, so the chunking loop is actually exercised. */
    private static final int PAYLOAD_BYTES = 200_000;

    @Test
    @DisplayName("the array, segment and channel forms agree with CRC32C and with each other")
    void allThreeFormsAgree() throws IOException {
        byte[] payload = randomBytes(PAYLOAD_BYTES);
        Path file = tempDir.resolve("payload.bin");
        Files.write(file, payload);

        CRC32C reference = new CRC32C();
        reference.update(payload, 0, payload.length);

        long fromArray = IndexChecksum.of(payload, 0, payload.length);
        long fromChannel;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            fromChannel = IndexChecksum.of(channel, 0, payload.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(payload.length);
            MemorySegment.copy(MemorySegment.ofArray(payload), 0, segment, 0, payload.length);
            assertThat(IndexChecksum.of(segment, 0, payload.length)).isEqualTo(fromArray);
        }
        assertThat(fromArray).isEqualTo(reference.getValue());
        assertThat(fromChannel).isEqualTo(fromArray);
    }

    @Test
    @DisplayName("a single flipped byte changes the digest")
    void aFlippedByteChangesTheDigest() {
        byte[] payload = randomBytes(1024);
        long before = IndexChecksum.of(payload, 0, payload.length);
        payload[512] ^= 0x01;

        assertThat(IndexChecksum.of(payload, 0, payload.length)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a region that runs past the bytes is refused rather than digested short")
    void regionPastTheEndIsRefused() {
        byte[] payload = randomBytes(64);

        assertThatThrownBy(() -> IndexChecksum.of(payload, 32, 64))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs past the end");
        assertThatThrownBy(() -> IndexChecksum.of(payload, -1, 8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("a truncated file is an error, not a digest over the bytes that survived")
    void truncatedFileIsRefused() throws IOException {
        Path file = tempDir.resolve("short.bin");
        Files.write(file, randomBytes(100));

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThatThrownBy(() -> IndexChecksum.of(channel, 0, 400))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("truncated");
        }
    }

    @Test
    @DisplayName("an empty region digests without reading anything")
    void emptyRegionIsWellDefined() throws IOException {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThat(IndexChecksum.of(channel, 0, 0)).isEqualTo(0L);
        }
        assertThat(IndexChecksum.of(new byte[0], 0, 0)).isEqualTo(0L);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(20250914).nextBytes(bytes);
        return bytes;
    }
}
