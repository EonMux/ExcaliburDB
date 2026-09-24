package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading back where one erasure request stands.
 *
 * <p>An id is a promise made to a caller, and the caller under an obligation has to be able to
 * come back and ask whether it was kept - after a restart as much as before one, and whether or
 * not anything has the namespace open. The answer is the log's, so it has to be the same from
 * the open log and from the file.</p>
 */
@DisplayName("Erasure request status")
class ErasureLogStatusTest {

    private static final Instant REQUESTED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant APPLIED = Instant.parse("2026-01-01T00:05:00Z");

    private static Path logFile(Path dir) {
        return dir.resolve("erasure").resolve("requests.log");
    }

    @Nested
    @DisplayName("from an open log")
    class Open {

        @Test
        @DisplayName("a submitted request is pending, with its scope and arrival time")
        void submittedIsPending(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                long id = log.submit(new ErasureScope.Entity(42), REQUESTED);

                ErasureLog.RequestStatus status = log.status(id).orElseThrow();

                assertThat(status.state()).isEqualTo(ErasureLog.State.PENDING);
                assertThat(status.scope()).contains(new ErasureScope.Entity(42));
                assertThat(status.requestedAt()).contains(REQUESTED);
                assertThat(status.completedAt()).isEmpty();
            }
        }

        @Test
        @DisplayName("a completed request is discharged, and says when")
        void completedIsDischarged(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                long id = log.submit(new ErasureScope.ExtractorVersion(3), REQUESTED);
                log.complete(List.of(id), APPLIED);

                ErasureLog.RequestStatus status = log.status(id).orElseThrow();

                assertThat(status.state()).isEqualTo(ErasureLog.State.DISCHARGED);
                assertThat(status.requestedAt()).contains(REQUESTED);
                assertThat(status.completedAt()).contains(APPLIED);
            }
        }

        @Test
        @DisplayName("an id the log never handed out has no status")
        void unknownIdsAreEmpty(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                long id = log.submit(new ErasureScope.Entity(1), REQUESTED);

                assertThat(log.status(id + 1)).isEmpty();
                assertThat(log.status(0)).isEmpty();
                assertThat(log.status(-5)).isEmpty();
            }
        }

        @Test
        @DisplayName("only the most recent completions keep their detail")
        void retentionIsBounded(@TempDir Path dir) throws IOException {
            // The log never forgets a completion, so memory for them has to be bounded by
            // something other than the file. What falls out is still reported as discharged:
            // the id was handed out and nothing owes it.
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                List<Long> ids = new ArrayList<>();
                for (int i = 0; i <= ErasureLog.RETAINED_COMPLETIONS; i++) {
                    ids.add(log.submit(new ErasureScope.Entity(i), REQUESTED));
                }
                log.complete(ids, APPLIED);

                ErasureLog.RequestStatus oldest = log.status(ids.get(0)).orElseThrow();
                ErasureLog.RequestStatus newest = log.status(ids.get(ids.size() - 1))
                    .orElseThrow();

                assertThat(oldest.state()).isEqualTo(ErasureLog.State.DISCHARGED);
                assertThat(oldest.scope()).isEmpty();
                assertThat(oldest.requestedAt()).isEmpty();
                assertThat(oldest.completedAt()).isEmpty();
                assertThat(newest.completedAt()).contains(APPLIED);
                assertThat(newest.scope()).isPresent();
            }
        }
    }

    @Nested
    @DisplayName("several at once")
    class Batch {

        @Test
        @DisplayName("the open log and the file answer a batch the same way, leaving out ids "
            + "never handed out")
        void batchesAgree(@TempDir Path dir) throws IOException {
            List<Long> asked = new ArrayList<>();
            java.util.Map<Long, ErasureLog.RequestStatus> fromOpen;
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                long done = log.submit(new ErasureScope.Entity(1), REQUESTED);
                long owed = log.submit(
                    new ErasureScope.Source(SourceType.DOCUMENT, 7), REQUESTED);
                log.complete(List.of(done), APPLIED);
                asked.addAll(List.of(done, owed, owed + 100));

                fromOpen = log.statuses(asked);
            }

            java.util.Map<Long, ErasureLog.RequestStatus> fromFile =
                ErasureLog.statusesOf(logFile(dir), asked);

            assertThat(fromOpen).containsOnlyKeys(asked.get(0), asked.get(1));
            assertThat(fromOpen.get(asked.get(0)).state())
                .isEqualTo(ErasureLog.State.DISCHARGED);
            assertThat(fromOpen.get(asked.get(1)).state()).isEqualTo(ErasureLog.State.PENDING);
            assertThat(fromFile).isEqualTo(fromOpen);
        }

        @Test
        @DisplayName("a log that was never written answers an empty batch")
        void absentFile(@TempDir Path dir) throws IOException {
            assertThat(ErasureLog.statusesOf(logFile(dir), List.of(1L, 2L))).isEmpty();
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Restart {

        @Test
        @DisplayName("pending and discharged survive a reopen, completion time included")
        void survivesReopen(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            long owed;
            long done;
            try (ErasureLog log = ErasureLog.open(file)) {
                owed = log.submit(new ErasureScope.Entity(7), REQUESTED);
                done = log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 99), REQUESTED);
                log.complete(List.of(done), APPLIED);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.status(owed).orElseThrow().state())
                    .isEqualTo(ErasureLog.State.PENDING);
                ErasureLog.RequestStatus discharged = reopened.status(done).orElseThrow();
                assertThat(discharged.state()).isEqualTo(ErasureLog.State.DISCHARGED);
                assertThat(discharged.completedAt()).contains(APPLIED);
                assertThat(discharged.scope())
                    .contains(new ErasureScope.Source(SourceType.DOCUMENT, 99));
            }
        }

        @Test
        @DisplayName("the file answers exactly what the open log answers")
        void fileMatchesOpenLog(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            List<Long> ids = new ArrayList<>();
            try (ErasureLog log = ErasureLog.open(file)) {
                ids.add(log.submit(new ErasureScope.Entity(1), REQUESTED));
                ids.add(log.submit(new ErasureScope.Entity(2), REQUESTED));
                log.complete(List.of(ids.get(1)), APPLIED);
                for (long id : ids) {
                    assertThat(ErasureLog.statusOf(file, id)).isEqualTo(log.status(id));
                }
                assertThat(ErasureLog.statusOf(file, 99)).isEmpty();
            }
        }

        @Test
        @DisplayName("a log that was never written has handed out nothing")
        void absentFileIsEmpty(@TempDir Path dir) throws IOException {
            assertThat(ErasureLog.statusOf(logFile(dir), 1)).isEmpty();
        }

        @Test
        @DisplayName("a torn creation has handed out nothing, and a stranger file is refused")
        void shortFiles(@TempDir Path dir) throws IOException {
            Path torn = dir.resolve("torn.log");
            Files.write(torn, new byte[] {0x45, 0x58});
            assertThat(ErasureLog.statusOf(torn, 1)).isEmpty();

            Path stranger = dir.resolve("stranger.log");
            Files.writeString(stranger, "hi", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ErasureLog.statusOf(stranger, 1))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("damage with an intact record behind it is refused rather than answered")
        void damageIsRefused(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), REQUESTED);
                log.submit(new ErasureScope.Entity(2), REQUESTED);
            }
            byte[] bytes = Files.readAllBytes(file);
            // The first record's body: flipping it breaks its checksum while the second record
            // stays intact behind it.
            bytes[ErasureLog.HEADER_BYTES + Integer.BYTES + 2] ^= 0x7F;
            Files.write(file, bytes);

            assertThatThrownBy(() -> ErasureLog.statusOf(file, 2))
                .isInstanceOf(LogCorruptionException.class);
        }
    }

    @Nested
    @DisplayName("a completion written before completions carried a time")
    class Legacy {

        @Test
        @DisplayName("is still read as discharged, with no completion time")
        void legacyCompletionIsDischarged(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            Files.createDirectories(file.getParent());
            Files.write(file, legacyLog(1L, 3L));

            Optional<ErasureLog.RequestStatus> status = ErasureLog.statusOf(file, 1);

            assertThat(status).isPresent();
            assertThat(status.get().state()).isEqualTo(ErasureLog.State.DISCHARGED);
            assertThat(status.get().requestedAt()).contains(REQUESTED);
            assertThat(status.get().completedAt()).isEmpty();
            try (ErasureLog opened = ErasureLog.open(file)) {
                assertThat(opened.status(1)).isEqualTo(status);
                assertThat(opened.outstandingCount()).isZero();
            }
        }

        /** A header, an entity submission and a completion in the untimed form. */
        private static byte[] legacyLog(long id, long entityId) throws IOException {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(file);
            out.writeLong(ErasureLog.MAGIC);
            out.writeInt(ErasureLog.VERSION);
            out.writeInt(0);

            ByteArrayOutputStream submit = new ByteArrayOutputStream();
            DataOutputStream body = new DataOutputStream(submit);
            body.writeByte(0x01);
            body.writeLong(id);
            body.writeLong(REQUESTED.getEpochSecond());
            body.writeInt(REQUESTED.getNano());
            body.writeByte(0x01);
            body.writeLong(entityId);
            frame(out, submit.toByteArray());

            ByteArrayOutputStream complete = new ByteArrayOutputStream();
            body = new DataOutputStream(complete);
            body.writeByte(0x02);
            body.writeInt(1);
            body.writeLong(id);
            frame(out, complete.toByteArray());
            out.flush();
            return file.toByteArray();
        }

        private static void frame(DataOutputStream out, byte[] body) throws IOException {
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt(body.length);
            out.write(body);
            out.writeInt((int) crc.getValue());
        }
    }

    @Test
    @DisplayName("a pending status cannot carry a completion time")
    void pendingRejectsCompletion() {
        assertThatThrownBy(() -> new ErasureLog.RequestStatus(1, ErasureLog.State.PENDING,
            Optional.empty(), Optional.empty(), Optional.of(APPLIED)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
