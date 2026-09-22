package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asking what a log owes without taking it over.
 *
 * <p>{@link ErasureLog#open} is the only way there was to learn whether anything is outstanding,
 * and it is not a question - it truncates a torn tail, takes an append handle and hands back a
 * resource somebody has to close. The sweep that enforces the erasure deadline needs the answer
 * for namespaces nobody is serving, and opening each one's whole tenant to get it spends the
 * mapping budget the eviction protected. {@link ErasureLog#inspect} is that question asked
 * directly: a file read, no handle, no truncation, no mappings.</p>
 *
 * <p>What it must never do is answer "nothing" for a file it could not read. An obligation
 * silently dropped is the one failure this whole mechanism exists to prevent, and a sweep that
 * reads a damaged log as empty is exactly that failure with a clean log line.</p>
 */
@DisplayName("Erasure log inspection")
class ErasureLogInspectionTest {

    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    private static Path logFile(Path dir) {
        return dir.resolve("erasure").resolve("requests.log");
    }

    /** Cuts {@code bytes} off the end, which is what a process killed mid-append leaves. */
    private static void tear(Path file, int bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(Files.size(file) - bytes);
        }
    }

    @Nested
    @DisplayName("reading what is owed")
    class Reading {

        @Test
        @DisplayName("a file that was never written owes nothing")
        void anAbsentLogOwesNothing(@TempDir Path dir) throws IOException {
            ErasureLog.Pending pending = ErasureLog.inspect(logFile(dir));

            assertThat(pending.count()).isZero();
            assertThat(pending.oldestRequestedAt()).isEmpty();
        }

        @Test
        @DisplayName("an outstanding request is counted, and its arrival time reported")
        void outstandingRequestsAreCounted(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN.plusSeconds(60));
                log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 42), WHEN);
            }

            ErasureLog.Pending pending = ErasureLog.inspect(file);

            assertThat(pending.count()).isEqualTo(2);
            assertThat(pending.oldestRequestedAt())
                .as("the deadline is measured from the longest-waiting request")
                .contains(WHEN);
        }

        @Test
        @DisplayName("a completed request is not still owed")
        void completedRequestsAreNotCounted(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                long id = log.submit(new ErasureScope.Entity(7), WHEN);
                log.complete(java.util.List.of(id));
            }

            assertThat(ErasureLog.inspect(file).count()).isZero();
        }

        @Test
        @DisplayName("inspecting leaves the file exactly as it found it")
        void inspectingDoesNotWrite(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
            }
            byte[] before = Files.readAllBytes(file);

            ErasureLog.inspect(file);

            assertThat(Files.readAllBytes(file))
                .as("a question must not be a write: the sweep asks this of namespaces nothing "
                    + "else has open, and the next real open still has to see the same bytes")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("a torn tail is read as the obligations before it, as an open would")
        void aTornTailIsToleratedWithoutBeingTruncated(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
                log.submit(new ErasureScope.Entity(8), WHEN.plusSeconds(1));
            }
            long sizeBefore = Files.size(file);
            tear(file, 5);

            ErasureLog.Pending pending = ErasureLog.inspect(file);

            assertThat(pending.count())
                .as("the record the tear landed in was never acknowledged; the one before it was")
                .isEqualTo(1);
            assertThat(Files.size(file))
                .as("inspection reports the tear, it does not repair it - repairing is "
                    + "ErasureLog.open's job and happens when a tenant is actually opened")
                .isEqualTo(sizeBefore - 5);
        }
    }

    @Nested
    @DisplayName("refusing to guess")
    class Refusing {

        @Test
        @DisplayName("a log written by a newer build is refused rather than read as empty")
        void anUnreadableVersionIsRefused(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            Files.createDirectories(file.getParent());
            ByteBuffer header = ByteBuffer.allocate(ErasureLog.HEADER_BYTES);
            header.putLong(ErasureLog.MAGIC);
            header.putInt(ErasureLog.VERSION + 1);
            header.putInt(0);
            Files.write(file, header.array());

            assertThatThrownBy(() -> ErasureLog.inspect(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported erasure log version");
        }

        @Test
        @DisplayName("a damaged log is refused, because records follow the damage")
        void aDamagedLogIsRefused(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
                log.submit(new ErasureScope.Entity(8), WHEN.plusSeconds(1));
                log.submit(new ErasureScope.Entity(9), WHEN.plusSeconds(2));
            }
            corruptFirstRecord(file);

            // Reading to the damage and stopping would report one obligation where three were
            // accepted - and the sweep would then believe it had swept a namespace clean.
            assertThatThrownBy(() -> ErasureLog.inspect(file))
                .isInstanceOf(IOException.class);
        }

        /** Flips a byte inside the first record body, leaving intact records after it. */
        private static void corruptFirstRecord(Path file) throws IOException {
            byte[] contents = Files.readAllBytes(file);
            int bodyStart = ErasureLog.HEADER_BYTES + Integer.BYTES;
            contents[bodyStart + 1] ^= (byte) 0xFF;
            Files.write(file, contents);
        }
    }

    @Nested
    @DisplayName("deciding whether a compaction is due")
    class Due {

        @Test
        @DisplayName("nothing outstanding is never due")
        void nothingOutstandingIsNeverDue() {
            assertThat(ErasureLog.Pending.NONE.dueAt(WHEN.plusSeconds(1_000_000),
                Duration.ofSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("a request younger than the bound is not yet due")
        void aYoungRequestIsNotDue() {
            ErasureLog.Pending pending =
                new ErasureLog.Pending(1, java.util.Optional.of(WHEN));

            assertThat(pending.dueAt(WHEN.plusSeconds(30), Duration.ofSeconds(60))).isFalse();
        }

        @Test
        @DisplayName("a request that has waited out the bound is due")
        void anOverdueRequestIsDue() {
            ErasureLog.Pending pending =
                new ErasureLog.Pending(1, java.util.Optional.of(WHEN));

            assertThat(pending.dueAt(WHEN.plusSeconds(60), Duration.ofSeconds(60)))
                .as("the bound is a deadline, so reaching it is reaching it")
                .isTrue();
            assertThat(pending.dueAt(WHEN.plusSeconds(61), Duration.ofSeconds(60))).isTrue();
        }
    }

    @Nested
    @DisplayName("agreeing with an open log")
    class Agreement {

        @Test
        @DisplayName("what inspection reports is what opening the log would report")
        void inspectionAgreesWithOpening(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
                long second = log.submit(new ErasureScope.ExtractorVersion(3), WHEN.plusSeconds(5));
                log.submit(new ErasureScope.Entity(9), WHEN.plusSeconds(10));
                log.complete(java.util.List.of(second));
            }

            ErasureLog.Pending pending = ErasureLog.inspect(file);
            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(pending.count()).isEqualTo(reopened.outstandingCount());
                assertThat(pending.oldestRequestedAt())
                    .contains(reopened.outstanding().get(0).requestedAt());
            }
        }

        /** Keeps the CRC import honest: the damage test relies on the framing this computes. */
        @Test
        @DisplayName("the framing these tests damage is the framing the log writes")
        void framingIsWhatTheLogWrites(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
            }
            byte[] contents = Files.readAllBytes(file);
            ByteBuffer buffer = ByteBuffer.wrap(contents);
            buffer.position(ErasureLog.HEADER_BYTES);
            int length = buffer.getInt();
            byte[] body = new byte[length];
            buffer.get(body);
            CRC32 crc = new CRC32();
            crc.update(body);

            assertThat(buffer.getInt()).isEqualTo((int) crc.getValue());
        }
    }
}
