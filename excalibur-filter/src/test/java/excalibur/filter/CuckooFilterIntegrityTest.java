package excalibur.filter;

import io.excalibur.core.integrity.IntegrityStatus;
import io.excalibur.core.mapped.MappedFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrity tests for a serialized Cuckoo filter image.
 *
 * <p>A membership filter that has rotted answers "no" for terms a segment really holds, which
 * prunes the segment out of the query and makes the corpus look as though it has lost
 * documents. No image this engine writes can rot that way - the lexical index rebuilds its
 * filter from the terms column on every open - so these cover {@code writeTo}/{@code readFrom}
 * as a format, for the callers that serialize a filter themselves and for whatever starts
 * persisting one.</p>
 */
@DisplayName("CuckooFilter image integrity")
class CuckooFilterIntegrityTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a flipped byte in a persisted image is refused on read")
    void flippedByteIsRefused() throws IOException {
        Path file = persistFilter("flip.cuckoo");

        flipByte(file, 200);

        long size = Files.size(file);
        try (MappedFile mapped = MappedFile.open(file, size)) {
            assertThatThrownBy(() -> CuckooFilter.readFrom(mapped.segment(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
        }
    }

    @Test
    @DisplayName("an intact image still round-trips every term")
    void intactImageRoundTrips() throws IOException {
        Path file = persistFilter("intact.cuckoo");

        long size = Files.size(file);
        try (MappedFile mapped = MappedFile.open(file, size)) {
            CuckooFilter restored = CuckooFilter.readFrom(mapped.segment(), 0);
            for (int i = 0; i < 100; i++) {
                assertThat(restored.contains(term(i))).isTrue();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "seed, 24",
        "fingerprint width, 8",
        "bucket count, 16",
    })
    @DisplayName("a mutated header field on a sealed image is refused")
    void mutatedHeaderFieldIsRefused(String field, int offset) throws IOException {
        Path file = persistFilter("header-" + offset + ".cuckoo");

        flipByte(file, offset);

        long size = Files.size(file);
        try (MappedFile mapped = MappedFile.open(file, size)) {
            assertThatThrownBy(() -> CuckooFilter.readFrom(mapped.segment(), 0))
                .as("the %s decides which buckets every lookup reads; a filter built from a "
                    + "changed one answers 'no' for terms its segment really holds", field)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a mutated seed is caught by the header checksum, not by a size check")
    void mutatedSeedIsCaughtByTheHeaderChecksum() throws IOException {
        Path file = persistFilter("seed.cuckoo");

        // The seed alone: it changes no size and no bound, so nothing but the header
        // checksum stands between a flipped bit in it and a filter that hashes every term
        // to the wrong pair of buckets.
        flipByte(file, 24);

        long size = Files.size(file);
        try (MappedFile mapped = MappedFile.open(file, size)) {
            assertThatThrownBy(() -> CuckooFilter.readFrom(mapped.segment(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header checksum");
        }
    }

    @Test
    @DisplayName("the bytes between the header checksum and the buckets are covered")
    void thePadBehindTheHeaderChecksumIsCovered() throws IOException {
        Path file = persistFilter("pad.cuckoo");

        // No field lives here today, and the header checksum is stored behind it. A byte
        // flipped in a region no checksum digests is reported intact, and the next field
        // added there inherits that hole rather than the protection its author assumes.
        flipByte(file, 48);

        long size = Files.size(file);
        try (MappedFile mapped = MappedFile.open(file, size)) {
            assertThat(CuckooFilter.verifyIntegrity(mapped.segment(), 0).status())
                .as("every byte of the header must be covered by one of the two checksums")
                .isEqualTo(IntegrityStatus.CORRUPT);
        }
    }

    private Path persistFilter(String name) throws IOException {
        CuckooFilter filter = CuckooFilter.create(1000);
        for (int i = 0; i < 100; i++) {
            filter.insert(term(i));
        }
        Path file = tempDir.resolve(name);
        try (MappedFile mapped = MappedFile.create(file, filter.byteSize())) {
            filter.writeTo(mapped.segment(), 0);
        }
        return file;
    }

    private static byte[] term(int index) {
        return ("term-" + index).getBytes(StandardCharsets.UTF_8);
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.seek(offset);
            int original = handle.readUnsignedByte();
            handle.seek(offset);
            handle.writeByte(original ^ 0x40);
        }
    }
}
