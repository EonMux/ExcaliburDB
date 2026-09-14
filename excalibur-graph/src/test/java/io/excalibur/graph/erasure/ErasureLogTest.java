package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErasureLogTest {

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


    /**
     * Byte offsets at which each record frame starts, by walking the framing the log writes.
     *
     * <p>Computed rather than assumed, so a test that damages "the second record" damages the
     * second record whatever the scope encodings happen to be sized at.
     */
    private static List<Integer> frameOffsets(Path file) throws IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(Files.readAllBytes(file));
        buffer.position(ErasureLog.HEADER_BYTES);
        List<Integer> offsets = new ArrayList<>();
        while (buffer.remaining() >= Integer.BYTES) {
            int start = buffer.position();
            int length = buffer.getInt();
            if (length <= 0 || buffer.remaining() < length + Integer.BYTES) {
                break;
            }
            buffer.position(buffer.position() + length + Integer.BYTES);
            offsets.add(start);
        }
        return offsets;
    }

    @Nested
    @DisplayName("durability across a reopen")
    class Reopen {

        @Test
        @DisplayName("a submitted request is still outstanding after a reopen")
        void submittedRequestSurvives(@TempDir Path dir) throws IOException {
            // The whole reason the file exists: a request accepted and then forgotten by a restart
            // leaves the data it named on disk with nothing left to say it should not be.
            Path file = logFile(dir);
            long id;
            try (ErasureLog log = ErasureLog.open(file)) {
                id = log.submit(new ErasureScope.Entity(42), WHEN);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding())
                    .containsExactly(new ErasureRequest(id, new ErasureScope.Entity(42), WHEN));
                assertThat(reopened.outstandingCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("all three scopes round-trip byte for byte")
        void everyScopeRoundTrips(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(7), WHEN);
                log.submit(new ErasureScope.Source(SourceType.CONVERSATION, 900), WHEN);
                log.submit(new ErasureScope.ExtractorVersion(13), WHEN);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::scope)
                    .containsExactly(
                        new ErasureScope.Entity(7),
                        new ErasureScope.Source(SourceType.CONVERSATION, 900),
                        new ErasureScope.ExtractorVersion(13));
            }
        }

        @Test
        @DisplayName("the request timestamp keeps its nanoseconds")
        void timestampIsNotRounded(@TempDir Path dir) throws IOException {
            // The timestamp is what a deadline is measured from, so it is stored as given.
            Path file = logFile(dir);
            Instant precise = Instant.parse("2026-03-04T05:06:07.123456789Z");
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), precise);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding().get(0).requestedAt()).isEqualTo(precise);
            }
        }

        @Test
        @DisplayName("a completed request is not outstanding again after a reopen")
        void completionIsNotReRun(@TempDir Path dir) throws IOException {
            // A completion that did not survive would rewrite the whole store on every start to
            // remove edges that were removed the first time.
            Path file = logFile(dir);
            long done;
            long pending;
            try (ErasureLog log = ErasureLog.open(file)) {
                done = log.submit(new ErasureScope.Entity(1), WHEN);
                pending = log.submit(new ErasureScope.Entity(2), WHEN);
                log.complete(List.of(done));
                assertThat(log.outstandingCount()).isEqualTo(1);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::id)
                    .containsExactly(pending);
            }
        }

        @Test
        @DisplayName("a completed id is never handed out again")
        void idsAreNotReused(@TempDir Path dir) throws IOException {
            // Ids are the audit trail of what was erased and when. Reusing one after its request
            // completed would make two different erasures indistinguishable in that record.
            Path file = logFile(dir);
            long first;
            try (ErasureLog log = ErasureLog.open(file)) {
                first = log.submit(new ErasureScope.Entity(1), WHEN);
                log.complete(List.of(first));
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.submit(new ErasureScope.Entity(2), WHEN)).isGreaterThan(first);
            }
        }

        @Test
        @DisplayName("an absent file opens empty rather than failing")
        void freshFile(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                assertThat(log.outstanding()).isEmpty();
                assertThat(log.outstandingCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("a torn tail")
    class TornTail {

        @Test
        @DisplayName("keeps the requests recorded before it")
        void keepsEarlierRequests(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            long first;
            long second;
            try (ErasureLog log = ErasureLog.open(file)) {
                first = log.submit(new ErasureScope.Entity(1), WHEN);
                second = log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 2), WHEN);
                log.submit(new ErasureScope.ExtractorVersion(3), WHEN);
            }
            tear(file, 4);

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::id)
                    .as("the two intact requests, not an unreadable file")
                    .containsExactly(first, second);
            }
        }

        @Test
        @DisplayName("a body cut off part way is dropped, not half-decoded")
        void partialBody(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
            }
            tear(file, 12);

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::scope)
                    .containsExactly(new ErasureScope.Entity(1));
            }
        }

        @Test
        @DisplayName("a record whose checksum does not match ends the replay")
        void corruptBody(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
            }
            byte[] bytes = Files.readAllBytes(file);
            bytes[bytes.length - 8] ^= (byte) 0xFF;
            Files.write(file, bytes);

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::scope)
                    .containsExactly(new ErasureScope.Entity(1));
            }
        }

        @Test
        @DisplayName("requests recorded after the tear survive the next open")
        void appendsAfterATearAreReadable(@TempDir Path dir) throws IOException {
            // The torn bytes are truncated away on open. Appending past them would leave every
            // later record behind a record the replay stops at, so an erasure request accepted
            // after a crash would vanish at the next restart with nothing to show for it.
            Path file = logFile(dir);
            long kept;
            try (ErasureLog log = ErasureLog.open(file)) {
                kept = log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
            }
            tear(file, 4);

            long afterTear;
            try (ErasureLog reopened = ErasureLog.open(file)) {
                afterTear = reopened.submit(new ErasureScope.Entity(3), WHEN);
            }

            try (ErasureLog again = ErasureLog.open(file)) {
                assertThat(again.outstanding()).extracting(ErasureRequest::id)
                    .containsExactly(kept, afterTear);
            }
        }

        @Test
        @DisplayName("a header cut off part way is rewritten, not appended past")
        void tornHeader(@TempDir Path dir) throws IOException {
            // Killed inside the header write there is nothing before the tear worth keeping, and
            // appending a second header after the partial one would leave a file whose first
            // eight bytes are not the magic and which therefore never opens again.
            Path file = logFile(dir);
            Files.createDirectories(file.getParent());
            // A genuine torn header is a prefix of the magic - that is what a write interrupted
            // inside it leaves behind. Five arbitrary bytes are somebody else's file.
            byte[] magic = java.nio.ByteBuffer.allocate(Long.BYTES)
                .putLong(ErasureLog.MAGIC).array();
            Files.write(file, java.util.Arrays.copyOf(magic, 5));

            long id;
            try (ErasureLog log = ErasureLog.open(file)) {
                id = log.submit(new ErasureScope.Entity(1), WHEN);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::id)
                    .containsExactly(id);
            }
        }

        @Test
        @DisplayName("a short file that is not a torn header is refused, not overwritten")
        void aShortForeignFileIsRefused(@TempDir Path dir) throws IOException {
            // Opening a log truncates a torn header and writes a fresh one. Deciding that on
            // length alone means a mistyped path silently destroys whatever is at it - which is
            // the one failure a class about erasing the right thing must not have.
            Path file = logFile(dir);
            Files.createDirectories(file.getParent());
            byte[] theirs = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, theirs);

            assertThatThrownBy(() -> ErasureLog.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not an erasure log");

            assertThat(Files.readAllBytes(file))
                .as("the file it was pointed at is still there")
                .isEqualTo(theirs);
        }

        @Test
        @DisplayName("a completion lost with the tail only costs a repeated erasure")
        void tornCompletion(@TempDir Path dir) throws IOException {
            // Erasure is idempotent, so re-applying a completed request removes nothing that is
            // still there. That asymmetry is why complete does not force and submit does.
            Path file = logFile(dir);
            long id;
            try (ErasureLog log = ErasureLog.open(file)) {
                id = log.submit(new ErasureScope.Entity(1), WHEN);
                log.complete(List.of(id));
            }
            tear(file, 4);

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::id)
                    .as("outstanding again, which repeats work rather than losing data")
                    .containsExactly(id);
            }
        }
    }

    @Nested
    @DisplayName("rejecting what it cannot interpret")
    class Rejects {

        @Test
        @DisplayName("a file that is not an erasure log")
        void wrongMagic(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("not-a-log");
            Files.write(file, new byte[64]);

            assertThatThrownBy(() -> ErasureLog.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not an erasure log");
        }

        @Test
        @DisplayName("a closed log refuses further requests rather than dropping them")
        void closedLog(@TempDir Path dir) throws IOException {
            ErasureLog log = ErasureLog.open(logFile(dir));
            log.close();

            assertThatThrownBy(() -> log.submit(new ErasureScope.Entity(1), WHEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("null arguments name the argument")
        void nullArguments(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                assertThatThrownBy(() -> log.submit(null, WHEN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("scope");
                assertThatThrownBy(() -> log.submit(new ErasureScope.Entity(1), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("requestedAt");
                assertThatThrownBy(() -> log.complete(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("requestIds");
            }
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        @DisplayName("an id that is not outstanding is ignored")
        void unknownIdIsIgnored(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                long id = log.submit(new ErasureScope.Entity(1), WHEN);
                long sizeBefore = Files.size(file);

                log.complete(List.of(id + 1000));

                assertThat(log.outstandingCount()).isEqualTo(1);
                log.flush();
                assertThat(Files.size(file))
                    .as("no record written for a request nobody is waiting on")
                    .isEqualTo(sizeBefore);
            }
        }

        @Test
        @DisplayName("completing twice leaves the request completed")
        void completingTwice(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(logFile(dir))) {
                long id = log.submit(new ErasureScope.Entity(1), WHEN);
                log.complete(List.of(id));
                log.complete(List.of(id));
                assertThat(log.outstanding()).isEmpty();
            }
        }

        @Test
        @DisplayName("a batch larger than one record survives a reopen")
        void largeBatchIsChunked(@TempDir Path dir) throws IOException {
            // Completions are split across records to stay inside the record size bound; the
            // split must not lose any of them.
            Path file = logFile(dir);
            List<Long> ids = new ArrayList<>();
            try (ErasureLog log = ErasureLog.open(file)) {
                for (int i = 0; i < 5000; i++) {
                    ids.add(log.submit(new ErasureScope.Entity(i), WHEN));
                }
                log.complete(ids);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("thread safety")
    class Concurrency {

        @Test
        @DisplayName("concurrent submissions each get their own id")
        void concurrentSubmitsAreUnique(@TempDir Path dir) throws Exception {
            // An id is decided by the same critical section that writes the record fixing it, so
            // two threads must not be able to leave with the same one.
            int threads = 8;
            int each = 50;
            Path file = logFile(dir);
            ConcurrentLinkedQueue<Long> ids = new ConcurrentLinkedQueue<>();

            try (ErasureLog log = ErasureLog.open(file)) {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                for (int t = 0; t < threads; t++) {
                    int base = t;
                    Thread.ofPlatform().start(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < each; i++) {
                                ids.add(log.submit(new ErasureScope.Entity(base * each + i),
                                    WHEN));
                            }
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                assertThat(Set.copyOf(ids)).hasSize(threads * each);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstandingCount()).isEqualTo(threads * each);
            }
        }
    }

    @Nested
    @DisplayName("damage in the middle of the log")
    class MidLogDamage {

        @Test
        @DisplayName("a request recorded after a damaged one is not truncated away in silence")
        void obligationsAfterDamageAreNotLost(@TempDir Path dir) throws IOException {
            // The failure this guards: submit() writes the body and then flushes, so one failed
            // flush - a full disk, a quota, an EIO - leaves a partial record. Every submit after
            // it appends past the damage and is told the request was accepted. Replaying to the
            // first bad record and truncating there then erases those acceptances entirely:
            // outstandingCount() is zero, the file looks healthy, and nobody is told that an
            // erasure obligation was dropped.
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
                log.submit(new ErasureScope.Entity(3), WHEN);
            }

            byte[] all = Files.readAllBytes(file);
            all[frameOffsets(file).get(0) + Integer.BYTES + 1] ^= 0x40;
            Files.write(file, all);

            assertThatThrownBy(() -> ErasureLog.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("intact record");
        }

        @Test
        @DisplayName("a partial record that misaligns the requests after it also fails the open")
        void misalignedRequestsAfterDamageFailTheOpen(@TempDir Path dir) throws IOException {
            // A body cut short leaves the records appended after it at offsets no framing points
            // at, so recognising them means scanning byte offsets and not frame boundaries.
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 42), WHEN);
                log.submit(new ErasureScope.ExtractorVersion(9), WHEN);
            }

            byte[] all = Files.readAllBytes(file);
            int second = frameOffsets(file).get(1);
            byte[] spliced = new byte[all.length + 7];
            System.arraycopy(all, 0, spliced, 0, second);
            System.arraycopy(all, second, spliced, second + 7, all.length - second);
            java.nio.ByteBuffer.wrap(spliced).putInt(second, 512);
            Files.write(file, spliced);

            assertThatThrownBy(() -> ErasureLog.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("intact record");
        }

        @Test
        @DisplayName("the failed open leaves the damaged log on disk rather than repairing it")
        void theDamagedLogIsNotTouched(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            try (ErasureLog log = ErasureLog.open(file)) {
                log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
            }
            byte[] all = Files.readAllBytes(file);
            all[frameOffsets(file).get(0) + Integer.BYTES + 1] ^= 0x40;
            Files.write(file, all);
            byte[] damaged = Files.readAllBytes(file);

            assertThatThrownBy(() -> ErasureLog.open(file)).isInstanceOf(IOException.class);

            assertThat(Files.readAllBytes(file)).isEqualTo(damaged);
        }

        @Test
        @DisplayName("a genuine torn tail is still trimmed and the requests before it survive")
        void aTornTailIsStillTolerated(@TempDir Path dir) throws IOException {
            Path file = logFile(dir);
            long kept;
            try (ErasureLog log = ErasureLog.open(file)) {
                kept = log.submit(new ErasureScope.Entity(1), WHEN);
                log.submit(new ErasureScope.Entity(2), WHEN);
            }
            tear(file, 4);

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstanding()).extracting(ErasureRequest::id)
                    .containsExactly(kept);
            }
        }
    }
}
