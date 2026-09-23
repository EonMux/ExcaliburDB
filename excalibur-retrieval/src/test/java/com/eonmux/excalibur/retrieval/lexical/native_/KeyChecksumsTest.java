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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checksums a segment's key table is written with: what they record, what they catch, and
 * what is reported for a key table written without them.
 */
@DisplayName("Key table checksums")
class KeyChecksumsTest {

    private static final String SEGMENT = "seg-1";

    private static void writeNames(Path dir, String... names) throws IOException {
        try (SegmentKeys.Writer writer = new SegmentKeys.Writer(dir, SEGMENT)) {
            for (String name : names) {
                writer.add(name);
            }
        }
    }

    private static Path file(Path dir, String suffix) {
        return dir.resolve(SEGMENT + suffix);
    }

    private static List<IntegrityStatus> statuses(List<IntegrityReport> reports) {
        return reports.stream().map(IntegrityReport::status).toList();
    }

    @Nested
    @DisplayName("written with a segment")
    class Written {

        @Test
        @DisplayName("record every file of the key table, and match it")
        void roundTrip(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2", "chunk:3");

            KeyChecksums.Recorded recorded = KeyChecksums.read(dir, SEGMENT);

            assertThat(recorded.docCount()).isEqualTo(3);
            for (String suffix : KeyChecksums.SUFFIXES) {
                assertThat(recorded.of(suffix).length()).isEqualTo(Files.size(file(dir, suffix)));
                assertThat(KeyChecksums.mismatch(dir, SEGMENT, recorded, suffix)).isNull();
            }
            assertThat(statuses(KeyChecksums.inspect(dir, SEGMENT, 3)))
                .containsExactly(IntegrityStatus.INTACT, IntegrityStatus.INTACT,
                    IntegrityStatus.INTACT, IntegrityStatus.INTACT);
        }

        @Test
        @DisplayName("are removed with the segment")
        void deletedWithTheSegment(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");

            SegmentKeys.delete(dir, SEGMENT);

            assertThat(KeyChecksums.of(dir, SEGMENT)).doesNotExist();
            assertThat(KeyChecksums.read(dir, SEGMENT)).isNull();
        }
    }

    @Nested
    @DisplayName("catch")
    class Catch {

        @Test
        @DisplayName("a changed byte in a name")
        void changedName(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            LexicalSegmentCorruptionTest.flipByte(file(dir, SegmentKeys.KEYS_SUFFIX), 6);

            KeyChecksums.Recorded recorded = KeyChecksums.read(dir, SEGMENT);

            assertThat(KeyChecksums.mismatch(dir, SEGMENT, recorded, SegmentKeys.KEYS_SUFFIX))
                .contains("checksum did not match");
            List<IntegrityReport> reports = KeyChecksums.inspect(dir, SEGMENT, 2);
            assertThat(reports.get(0).status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(reports.get(0).detail()).contains("checksum did not match");
            assertThat(reports.get(1).status()).isEqualTo(IntegrityStatus.INTACT);
        }

        @Test
        @DisplayName("a changed offset")
        void changedOffset(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            LexicalSegmentCorruptionTest.flipByte(file(dir, SegmentKeys.INDEX_SUFFIX), 7);

            assertThat(KeyChecksums.mismatch(dir, SEGMENT, KeyChecksums.read(dir, SEGMENT),
                SegmentKeys.INDEX_SUFFIX)).contains("checksum did not match");
        }

        @Test
        @DisplayName("a names file cut short")
        void truncatedNames(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            Path names = file(dir, SegmentKeys.KEYS_SUFFIX);
            byte[] bytes = Files.readAllBytes(names);
            Files.write(names, Arrays.copyOf(bytes, bytes.length - 3));

            assertThat(KeyChecksums.mismatch(dir, SEGMENT, KeyChecksums.read(dir, SEGMENT),
                SegmentKeys.KEYS_SUFFIX)).contains("bytes where it was");
        }

        @Test
        @DisplayName("a file that is gone")
        void missing(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");
            Files.delete(file(dir, SegmentKeys.MAP_SUFFIX));

            assertThat(KeyChecksums.mismatch(dir, SEGMENT, KeyChecksums.read(dir, SEGMENT),
                SegmentKeys.MAP_SUFFIX)).contains("is not there");
            assertThat(KeyChecksums.inspect(dir, SEGMENT, 1).get(2).status())
                .isEqualTo(IntegrityStatus.MISSING);
        }

        @Test
        @DisplayName("checksums recorded for another number of names")
        void anotherCount(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");

            List<IntegrityReport> reports = KeyChecksums.inspect(dir, SEGMENT, 2);

            assertThat(reports.get(3).status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(reports.get(3).detail()).contains("where the manifest records 2");
        }
    }

    @Nested
    @DisplayName("the checksum file itself")
    class Itself {

        @Test
        @DisplayName("is refused when a byte of it changed")
        void flipped(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");
            LexicalSegmentCorruptionTest.flipByte(KeyChecksums.of(dir, SEGMENT), 20);

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum did not match");
            assertThat(KeyChecksums.inspect(dir, SEGMENT, 1)).singleElement()
                .satisfies(report -> assertThat(report.status())
                    .isEqualTo(IntegrityStatus.CORRUPT));
        }

        @Test
        @DisplayName("is refused when cut short")
        void truncated(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");
            Path sums = KeyChecksums.of(dir, SEGMENT);
            Files.write(sums, Arrays.copyOf(Files.readAllBytes(sums), KeyChecksums.FILE_BYTES - 1));

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .hasMessageContaining("exactly " + KeyChecksums.FILE_BYTES);
        }

        @Test
        @DisplayName("is refused when too short to hold a header")
        void tooShort(@TempDir Path dir) throws Exception {
            Files.write(KeyChecksums.of(dir, SEGMENT), new byte[] {1, 2});

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .hasMessageContaining("too short");
        }

        @Test
        @DisplayName("is refused when it does not begin with the magic number")
        void notOne(@TempDir Path dir) throws Exception {
            Files.write(KeyChecksums.of(dir, SEGMENT), new byte[KeyChecksums.FILE_BYTES]);

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .hasMessageContaining("magic number");
        }

        @Test
        @DisplayName("is refused when its version field names one no build writes")
        void overwrittenVersion(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");
            Path sums = KeyChecksums.of(dir, SEGMENT);
            byte[] bytes = Files.readAllBytes(sums);
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, 0);
            Files.write(sums, bytes);

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .isNotInstanceOf(KeyChecksums.UnsupportedVersionException.class)
                .hasMessageContaining("header was overwritten");
        }

        @Test
        @DisplayName("from a newer build is unreadable, not damaged")
        void newer(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1");
            Path sums = KeyChecksums.of(dir, SEGMENT);
            byte[] bytes = Files.readAllBytes(sums);
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, KeyChecksums.VERSION + 1);
            Files.write(sums, bytes);

            assertThatThrownBy(() -> KeyChecksums.read(dir, SEGMENT))
                .isInstanceOfSatisfying(KeyChecksums.UnsupportedVersionException.class,
                    e -> assertThat(e.version()).isEqualTo(KeyChecksums.VERSION + 1));
            assertThat(KeyChecksums.inspect(dir, SEGMENT, 1)).singleElement()
                .satisfies(report -> assertThat(report.status())
                    .isEqualTo(IntegrityStatus.UNREADABLE));
        }
    }

    @Nested
    @DisplayName("a key table written before checksums")
    class Legacy {

        @Test
        @DisplayName("is held to its structure and reported unprotected")
        void structureHolds(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            Files.delete(KeyChecksums.of(dir, SEGMENT));

            assertThat(statuses(KeyChecksums.inspect(dir, SEGMENT, 2)))
                .containsExactly(IntegrityStatus.LEGACY_UNPROTECTED,
                    IntegrityStatus.LEGACY_UNPROTECTED, IntegrityStatus.LEGACY_UNPROTECTED);
        }

        @Test
        @DisplayName("is corrupt when its offsets do not number the manifest's documents")
        void wrongCount(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            Files.delete(KeyChecksums.of(dir, SEGMENT));

            List<IntegrityReport> reports = KeyChecksums.inspect(dir, SEGMENT, 3);

            assertThat(reports.get(0).status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(reports.get(0).detail()).contains("3 name(s)");
            assertThat(reports.get(2).status()).isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
        }

        @Test
        @DisplayName("is corrupt when its offsets do not end where the names do")
        void offsetsOverrun(@TempDir Path dir) throws Exception {
            writeNames(dir, "chunk:1", "chunk:2");
            Files.delete(KeyChecksums.of(dir, SEGMENT));
            Path names = file(dir, SegmentKeys.KEYS_SUFFIX);
            Files.write(names, Arrays.copyOf(Files.readAllBytes(names), 3));

            assertThat(KeyChecksums.inspect(dir, SEGMENT, 2).get(1).detail())
                .contains("the offsets run from byte 0");
        }

        @Test
        @DisplayName("is corrupt when its files are not there")
        void absent(@TempDir Path dir) {
            assertThat(KeyChecksums.inspect(dir, SEGMENT, 1).get(0).detail())
                .contains("not there");
        }
    }
}
