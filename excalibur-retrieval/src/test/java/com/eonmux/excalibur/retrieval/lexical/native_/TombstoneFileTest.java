package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checksummed tombstone file: what it reads back, what it refuses, and how it reads the
 * headerless version before it.
 */
@DisplayName("Tombstone file")
class TombstoneFileTest {

    private static boolean[] marks(int docCount, int... set) {
        boolean[] marks = new boolean[docCount];
        for (int doc : set) {
            marks[doc] = true;
        }
        return marks;
    }

    /** The headerless version: the marks alone, one bit per document. */
    private static byte[] headerless(boolean[] marks) {
        byte[] bits = new byte[(marks.length + 7) / 8];
        for (int i = 0; i < marks.length; i++) {
            if (marks[i]) {
                bits[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return bits;
    }

    private static Path write(Path dir, byte[] bytes) throws IOException {
        Path file = TombstoneFile.of(dir, "seg-1");
        Files.write(file, bytes);
        return file;
    }

    @Nested
    @DisplayName("at the current version")
    class Current {

        @Test
        @DisplayName("reads back the marks it was written with")
        void roundTrip(@TempDir Path dir) throws Exception {
            boolean[] written = marks(21, 0, 7, 8, 20);
            Path file = write(dir, TombstoneFile.encode(written));

            TombstoneFile.Marks read = TombstoneFile.read(file, 21);

            assertThat(read.marks()).containsExactly(written);
            assertThat(read.version()).isEqualTo(TombstoneFile.VERSION);
            assertThat(read.count()).isEqualTo(4);
            assertThat(TombstoneFile.inspect(file, 21).status()).isEqualTo(IntegrityStatus.INTACT);
        }

        @Test
        @DisplayName("reads a segment with no documents")
        void empty(@TempDir Path dir) throws Exception {
            Path file = write(dir, TombstoneFile.encode(new boolean[0]));

            assertThat(TombstoneFile.read(file, 0).marks()).isEmpty();
        }

        @Test
        @DisplayName("reads an absent file as no marks, and has nothing to report on it")
        void absent(@TempDir Path dir) throws Exception {
            Path file = TombstoneFile.of(dir, "seg-1");

            TombstoneFile.Marks read = TombstoneFile.read(file, 5);

            assertThat(read.count()).isZero();
            assertThat(read.version()).isEqualTo(TombstoneFile.VERSION);
            assertThat(TombstoneFile.inspect(file, 5)).isNull();
        }

        @Test
        @DisplayName("refuses a cleared mark, because the checksum no longer matches")
        void flippedMark(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(16, 3));
            bytes[16] ^= 0x08;
            Path file = write(dir, bytes);

            assertThatThrownBy(() -> TombstoneFile.read(file, 16))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum did not match");
            IntegrityReport report = TombstoneFile.inspect(file, 16);
            assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.detail()).contains("checksum did not match");
        }

        @Test
        @DisplayName("refuses a damaged checksum")
        void flippedChecksum(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(9, 1));
            bytes[bytes.length - 1] ^= 0x01;
            Path file = write(dir, bytes);

            assertThatThrownBy(() -> TombstoneFile.read(file, 9))
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("refuses a file cut short")
        void truncated(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(40, 39));
            Path file = write(dir, Arrays.copyOf(bytes, bytes.length - 2));

            assertThatThrownBy(() -> TombstoneFile.read(file, 40))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cut short");
            assertThat(TombstoneFile.inspect(file, 40).status())
                .isEqualTo(IntegrityStatus.CORRUPT);
        }

        @Test
        @DisplayName("refuses a file cut short inside its magic number")
        void truncatedInsideTheMagic(@TempDir Path dir) throws Exception {
            // One document takes one byte of marks, so a headerless file for it is one byte long
            // - exactly what a current file cut after its first byte is.
            byte[] bytes = TombstoneFile.encode(marks(1, 0));
            Path file = write(dir, Arrays.copyOf(bytes, 1));

            assertThatThrownBy(() -> TombstoneFile.read(file, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cut short");
        }

        @Test
        @DisplayName("refuses a file written past its end")
        void overlong(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(8, 2));
            Path file = write(dir, Arrays.copyOf(bytes, bytes.length + 1));

            assertThatThrownBy(() -> TombstoneFile.read(file, 8))
                .hasMessageContaining("written past its end");
        }

        @Test
        @DisplayName("refuses marks written for a segment of another size")
        void anotherSegment(@TempDir Path dir) throws Exception {
            Path file = write(dir, TombstoneFile.encode(marks(8, 2)));

            assertThatThrownBy(() -> TombstoneFile.read(file, 9))
                .hasMessageContaining("not written for this segment");
        }

        @Test
        @DisplayName("refuses a header naming a version no build writes")
        void unknownOlderVersion(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(8, 2));
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, 1);
            Path file = write(dir, bytes);

            assertThatThrownBy(() -> TombstoneFile.read(file, 8))
                .isNotInstanceOf(TombstoneFile.UnsupportedVersionException.class)
                .hasMessageContaining("header was overwritten");
        }
    }

    @Nested
    @DisplayName("from another build")
    class OtherBuilds {

        @Test
        @DisplayName("reads the headerless version, as an older build wrote it")
        void headerlessIsRead(@TempDir Path dir) throws Exception {
            boolean[] written = marks(13, 0, 12);
            Path file = write(dir, headerless(written));

            TombstoneFile.Marks read = TombstoneFile.read(file, 13);

            assertThat(read.marks()).containsExactly(written);
            assertThat(read.version()).isEqualTo(TombstoneFile.HEADERLESS_VERSION);
            IntegrityReport report = TombstoneFile.inspect(file, 13);
            assertThat(report.status()).isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
            assertThat(report.detail()).contains("without a header or checksum");
        }

        @Test
        @DisplayName("refuses a headerless file of the wrong length")
        void headerlessOfTheWrongLength(@TempDir Path dir) throws Exception {
            Path file = write(dir, new byte[] {1});

            assertThatThrownBy(() -> TombstoneFile.read(file, 20))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("neither a current tombstone file");
            assertThat(TombstoneFile.inspect(file, 20).status())
                .isEqualTo(IntegrityStatus.CORRUPT);
        }

        @Test
        @DisplayName("refuses a newer build's version as unreadable, not damaged")
        void newerIsRefused(@TempDir Path dir) throws Exception {
            byte[] bytes = TombstoneFile.encode(marks(8, 2));
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, TombstoneFile.VERSION + 1);
            Path file = write(dir, bytes);

            assertThatThrownBy(() -> TombstoneFile.read(file, 8))
                .isInstanceOfSatisfying(TombstoneFile.UnsupportedVersionException.class,
                    e -> assertThat(e.version()).isEqualTo(TombstoneFile.VERSION + 1));
            IntegrityReport report = TombstoneFile.inspect(file, 8);
            assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
            assertThat(report.detail()).contains("version " + (TombstoneFile.VERSION + 1));
        }
    }
}
