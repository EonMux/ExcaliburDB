package io.excalibur.graph.erasure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A record this build cannot interpret is refused, not guessed at.
 *
 * <p>The log tolerates a torn tail, because a process killed mid-append is ordinary and the
 * obligations before the tear are intact. It tolerates nothing else. A record that checksums
 * correctly and carries a type, a scope discriminator or a source-type ordinal this build does not
 * know was written by something that understood more than this build does - and every one of those
 * bytes decides which edges get destroyed. Skipping such a record would leave data a client was
 * told would be erased; coercing it would erase edges nobody named.</p>
 *
 * <p>These paths had no coverage: they are exactly the ones a later refactor would quietly turn
 * into a skip or a default, and nothing would have failed.</p>
 */
@DisplayName("Erasure log rejections")
class ErasureLogRejectionTest {

    private static Path logFile(Path dir) {
        return dir.resolve("erasure").resolve("requests.log");
    }

    /**
     * Writes a header plus one framed record with a correct checksum.
     *
     * @param file    Where to write
     * @param version Version to put in the header
     * @param body    The record body
     * @throws IOException if the file cannot be written
     */
    private static void writeLogWith(Path file, int version, byte[] body) throws IOException {
        Files.createDirectories(file.getParent());

        CRC32 crc = new CRC32();
        crc.update(body);

        ByteBuffer buffer = ByteBuffer.allocate(
            ErasureLog.HEADER_BYTES + Integer.BYTES + body.length + Integer.BYTES);
        buffer.putLong(ErasureLog.MAGIC);
        buffer.putInt(version);
        buffer.putInt(0);
        buffer.putInt(body.length);
        buffer.put(body);
        buffer.putInt((int) crc.getValue());

        Files.write(file, buffer.array(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * A submit record, framed the way the log writes one.
     *
     * <p>Layout, read from the decoder: type, request id, the instant as seconds and nanos, then
     * the scope discriminator and its payload.</p>
     *
     * @param scope         The scope discriminator
     * @param sourceOrdinal The source-type ordinal, used only by the source scope
     * @return The record body
     */
    private static byte[] submitBody(byte scope, byte sourceOrdinal) {
        ByteBuffer body = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        body.put((byte) 0x01);
        body.putLong(1L);
        body.putLong(0L);
        body.putInt(0);
        body.put(scope);
        if (scope == 0x02) {
            body.put(sourceOrdinal);
            body.putLong(7L);
        } else if (scope == 0x03) {
            body.putInt(7);
        } else {
            body.putLong(7L);
        }
        byte[] out = new byte[body.position()];
        body.flip();
        body.get(out);
        return out;
    }

    @Test
    @DisplayName("a log written by a newer version is refused")
    void anUnsupportedVersionIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        writeLogWith(file, ErasureLog.VERSION + 1, submitBody((byte) 0x01, (byte) 0));

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unsupported erasure log version");
    }

    @Test
    @DisplayName("a file that is not a log at all is refused")
    void aForeignFileIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[ErasureLog.HEADER_BYTES + 8]);

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not an erasure log");
    }

    @Test
    @DisplayName("an intact record of an unknown type is refused")
    void anUnknownRecordTypeIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        writeLogWith(file, ErasureLog.VERSION, new byte[] { (byte) 0x7F, 0, 0, 0 });

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("an intact request of an unknown scope is refused")
    void anUnknownScopeIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        writeLogWith(file, ErasureLog.VERSION, submitBody((byte) 0x6F, (byte) 0));

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unknown scope");
    }

    @Test
    @DisplayName("a source-type ordinal this build does not know is refused, not coerced")
    void anUnknownSourceTypeIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        // SourceType.fromByte maps an unrecognised ordinal to UNKNOWN. Here the ordinal chooses
        // which edges are destroyed, so coercing it would erase a different tenant's data than
        // the request named.
        writeLogWith(file, ErasureLog.VERSION, submitBody((byte) 0x02, (byte) 0x5A));

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("source type");
    }

    @Test
    @DisplayName("a record shorter than its own fields is refused")
    void aTruncatedButChecksummedRecordIsRefused(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        // Checksums correctly and then runs out part way through the fields it declares - so it
        // is not a torn tail, it is a record that disagrees with this build about its own shape.
        writeLogWith(file, ErasureLog.VERSION, new byte[] { (byte) 0x01, 0x00 });

        assertThatThrownBy(() -> ErasureLog.open(file))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a well-formed record still opens")
    void aWellFormedRecordStillOpens(@TempDir Path dir) throws IOException {
        Path file = logFile(dir);
        writeLogWith(file, ErasureLog.VERSION, submitBody((byte) 0x01, (byte) 0));

        assertThatCode(() -> {
            try (ErasureLog log = ErasureLog.open(file)) {
                assertThat(log.outstandingCount()).isEqualTo(1);
            }
        }).doesNotThrowAnyException();
    }
}
