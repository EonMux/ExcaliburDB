package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bytes of {@code routing.dir}, and what a reader does with bytes that are not the ones
 * written.
 *
 * <p>Every slot value in the file is a plausible segment index, so damage to one does not look
 * like damage: it sends part of the keyspace to a segment that does not hold it. The checksum
 * over the whole file is what turns that into a refusal, and these fix both halves - that a
 * file whose bytes changed is not applied, and that a directory an older build wrote is still
 * applied and is rewritten with a checksum the next time it is written.</p>
 */
@DisplayName("Routing directory format")
class RoutingDirectoryFormatTest {

    private static final int[] SLOTS = { 0, 1, 2, 3, 4, 5, 6, 7, 0, 9, 2, 3, 4, 5, 6, 15 };

    private static Path file(Path dataDir) {
        return dataDir.resolve(RoutingDirectory.FILE_NAME);
    }

    /** A directory exactly as the build before the checksum wrote it. */
    private static byte[] versionOne(int[] slots) {
        ByteBuffer buffer = ByteBuffer
            .allocate(RoutingDirectory.HEADER_BYTES + slots.length * Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(RoutingDirectory.MAGIC).putInt(1).putInt(slots.length);
        for (int slot : slots) {
            buffer.putInt(slot);
        }
        return buffer.array();
    }

    /** A well-formed directory under any version number, checksummed as the current one is. */
    private static byte[] atVersion(int version, int[] slots) {
        ByteBuffer buffer = ByteBuffer
            .allocate(RoutingDirectory.HEADER_BYTES + slots.length * Integer.BYTES
                + RoutingDirectory.CHECKSUM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(RoutingDirectory.MAGIC).putInt(version).putInt(slots.length);
        for (int slot : slots) {
            buffer.putInt(slot);
        }
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());
        return buffer.array();
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("every slot comes back exactly as written, at the current version")
        void slotsSurvive(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);

            assertThat(RoutingDirectory.read(dataDir)).containsExactly(SLOTS);
            RoutingDirectory.Contents decoded =
                RoutingDirectory.decode(Files.readAllBytes(file(dataDir)));
            assertThat(decoded.version()).isEqualTo(RoutingDirectory.VERSION);
            assertThat(decoded.canonicalPerSlot()).containsExactly(SLOTS);
        }

        @Test
        @DisplayName("the file ends with a CRC32C of every byte before it")
        void endsWithAChecksumOfEverythingBeforeIt(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);
            byte[] bytes = Files.readAllBytes(file(dataDir));

            assertThat(bytes).hasSize(RoutingDirectory.HEADER_BYTES
                + SLOTS.length * Integer.BYTES + RoutingDirectory.CHECKSUM_BYTES);
            CRC32C crc = new CRC32C();
            crc.update(bytes, 0, bytes.length - RoutingDirectory.CHECKSUM_BYTES);
            int stored = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(bytes.length - RoutingDirectory.CHECKSUM_BYTES);
            assertThat(stored).isEqualTo((int) crc.getValue());
        }
    }

    @Nested
    @DisplayName("a directory whose bytes changed")
    class Damaged {

        @Test
        @DisplayName("a flipped slot is refused, and the refusal says the checksum did not match")
        void flippedSlotIsRefused(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);
            byte[] bytes = Files.readAllBytes(file(dataDir));
            // Slot 8 now names segment 1 instead of 0: still a segment that exists.
            bytes[RoutingDirectory.HEADER_BYTES + 8 * Integer.BYTES] ^= 1;
            Files.write(file(dataDir), bytes);

            assertThat(RoutingDirectory.read(dataDir)).isNull();
            assertThat(RoutingDirectory.load(dataDir).problem())
                .contains("checksum did not match");
            assertThatThrownBy(() -> RoutingDirectory.decode(bytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("a flipped checksum is refused the same way")
        void flippedChecksumIsRefused(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);
            byte[] bytes = Files.readAllBytes(file(dataDir));
            bytes[bytes.length - 1] ^= (byte) 0x80;

            assertThatThrownBy(() -> RoutingDirectory.decode(bytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("a file cut short is refused before any checksum is looked for")
        void truncationIsRefused(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);
            byte[] bytes = Files.readAllBytes(file(dataDir));
            Files.write(file(dataDir), Arrays.copyOf(bytes, bytes.length - 6));

            assertThat(RoutingDirectory.read(dataDir)).isNull();
            assertThat(RoutingDirectory.load(dataDir).problem()).contains("bytes");
        }

        @Test
        @DisplayName("bytes past the checksum are refused rather than ignored")
        void trailingBytesAreRefused(@TempDir Path dataDir) throws Exception {
            RoutingDirectory.write(dataDir, SLOTS);
            byte[] bytes = Files.readAllBytes(file(dataDir));

            assertThatThrownBy(() -> RoutingDirectory.decode(Arrays.copyOf(bytes, bytes.length + 4)))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a file that is not a routing directory at all is refused")
        void foreignFileIsRefused() {
            byte[] bytes = "not a routing directory, just text".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> RoutingDirectory.decode(bytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("magic");
        }

        @Test
        @DisplayName("an absent file is absent, not a problem")
        void absentIsNoProblem(@TempDir Path dataDir) {
            RoutingDirectory.Loaded loaded = RoutingDirectory.load(dataDir);

            assertThat(loaded.canonicalPerSlot()).isNull();
            assertThat(loaded.problem()).isNull();
        }
    }

    @Nested
    @DisplayName("a directory from another build")
    class OtherBuilds {

        @Test
        @DisplayName("one written before the checksum is read exactly as before")
        void versionOneIsRead(@TempDir Path dataDir) throws Exception {
            Files.write(file(dataDir), versionOne(SLOTS));

            assertThat(RoutingDirectory.read(dataDir)).containsExactly(SLOTS);
            assertThat(RoutingDirectory.decode(Files.readAllBytes(file(dataDir))).version())
                .isEqualTo(RoutingDirectory.UNCHECKSUMMED_VERSION);
        }

        @Test
        @DisplayName("one written before the checksum is rewritten with one by the next write")
        void versionOneIsRewrittenAtTheCurrentVersion(@TempDir Path dataDir) throws Exception {
            Files.write(file(dataDir), versionOne(SLOTS));

            RoutingDirectory.write(dataDir, RoutingDirectory.read(dataDir));

            RoutingDirectory.Contents decoded =
                RoutingDirectory.decode(Files.readAllBytes(file(dataDir)));
            assertThat(decoded.version()).isEqualTo(RoutingDirectory.VERSION);
            assertThat(decoded.canonicalPerSlot()).containsExactly(SLOTS);
        }

        @Test
        @DisplayName("one from a newer build is refused, naming the version it found")
        void newerVersionIsRefused(@TempDir Path dataDir) throws Exception {
            int newer = RoutingDirectory.VERSION + 1;
            Files.write(file(dataDir), atVersion(newer, SLOTS));

            assertThat(RoutingDirectory.read(dataDir)).isNull();
            assertThatThrownBy(() -> RoutingDirectory.decode(atVersion(newer, SLOTS)))
                .isInstanceOfSatisfying(RoutingDirectory.UnsupportedVersionException.class,
                    e -> assertThat(e.version()).isEqualTo(newer))
                .hasMessageContaining("version " + newer);
        }
    }
}
