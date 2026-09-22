package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When tombstoned documents are actually removed, and which segments pay for it.
 *
 * <p>The property worth testing hardest is the one that distinguishes an obligation from a
 * preference: that a single erased document in a large, otherwise untouched store is removed on a
 * deadline. A policy driven by accumulated volume alone never reaches its threshold for that case,
 * which is precisely the case most likely to have been promised to someone. The tests below pin
 * both halves - that the deadline overrides cost, and that cost still governs when there is no
 * deadline pressing - because a policy that only did the first would rewrite the corpus on every
 * tick, and one that only did the second would never keep its word.
 */
@DisplayName("Lexical compaction policy")
class CompactionPolicyTest {

    /** A clock the test moves by hand, so a deadline can pass without one. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    /**
     * A store and the text that stands behind it.
     *
     * <p>Per test rather than shared: the suite runs its classes and methods concurrently, and a
     * static corpus would make every one of these tests depend on which others were running.</p>
     */
    private static final class Fixture implements AutoCloseable {
        private final Map<String, String> corpus = new LinkedHashMap<>();
        private final LexicalStore store;

        Fixture(Path dir, CompactionPlanner planner, Clock clock) throws IOException {
            this.store = LexicalStore.open(dir, planner, clock);
        }

        /** Supplies a surviving document's text back to compaction. */
        String text(String key) {
            return corpus.get(key);
        }

        void add(String prefix, int count, long epoch) throws IOException {
            Map<String, String> batch = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                batch.put(prefix + i, prefix + "term" + i + " shared filler text");
            }
            corpus.putAll(batch);
            store.add(batch, epoch);
        }

        void replace(String key, String text) {
            corpus.put(key, text);
        }

        CompactionResult compactIfDue(long floorEpoch) throws IOException {
            return store.compactIfDue(this::text, floorEpoch);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static boolean anyFileContains(Path dir, String needle) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nested
    @DisplayName("The obligation")
    class Obligations {

        @Test
        @DisplayName("a deadline removes a document that volume alone never would")
        void deadlineOverridesVolume(@TempDir Path dir) throws Exception {
            // One erased document in a hundred is far below any ratio worth rewriting for. It is
            // also the shape of every erasure that was promised to somebody, so the deadline has
            // to be able to override the arithmetic. If this test fails, erasure is advisory.
            TestClock clock = new TestClock();
            CompactionPlanner planner =
                CompactionPlanner.standard(0.5, Duration.ofHours(1), 16);
            try (Fixture fixture = new Fixture(dir, planner, clock)) {
                fixture.add("doc", 100, 1);
                fixture.replace("doc7", "confidentialpayload must not survive");
                fixture.store.erase(Set.of("doc7"));

                assertThat(fixture.store.planCompaction(1))
                    .as("one in a hundred does not repay a rewrite yet")
                    .isEmpty();
                assertThat(fixture.compactIfDue(1).performed())
                    .isFalse();

                clock.advance(Duration.ofHours(2));

                CompactionResult result = fixture.compactIfDue(1);
                assertThat(result.performed()).isTrue();
                assertThat(result.documentsRemoved()).isEqualTo(1);
                assertThat(result.reason()).contains("overdue");
                assertThat(fixture.store.outstandingErasures()).isZero();
                assertThat(fixture.store.outstandingErasureRequests()).isEmpty();
                assertThat(fixture.store.liveDocuments(1)).isEqualTo(99);
            }
        }

        @Test
        @DisplayName("an overdue erasure is purged even above the epoch floor")
        void deadlineIsNotBlockedByAReader(@TempDir Path dir) throws Exception {
            // A merge cannot cross the floor without changing what an old reader sees, but a purge
            // keeps its segment's epoch, so it changes nothing except what erasure is meant to.
            // Were purging blocked by the floor too, one long-running query could hold an erasure
            // deadline open indefinitely.
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 16), clock)) {
                fixture.add("old", 4, 1);
                fixture.add("new", 4, 9);
                fixture.replace("new2", "confidentialpayload must not survive");
                fixture.store.erase(Set.of("new2"));
                clock.advance(Duration.ofHours(2));

                CompactionResult result = fixture.compactIfDue(1);

                assertThat(result.documentsRemoved()).isEqualTo(1);
                assertThat(anyFileContains(dir, "confidentialpayload")).isFalse();
                assertThat(fixture.store.segments())
                    .as("the segment above the floor was rewritten, not merged into the one below")
                    .hasSize(2);
                assertThat(fixture.store.segments().get(1).epoch())
                    .as("a purge keeps the epoch, so a reader's view is unchanged but for erasure")
                    .isEqualTo(9);
                assertThat(fixture.store.liveDocuments(8))
                    .as("still invisible below its epoch")
                    .isEqualTo(4);
                assertThat(fixture.store.liveDocuments(9)).isEqualTo(7);
            }
        }

        @Test
        @DisplayName("a promise that matched nothing is discharged rather than demanded forever")
        void unmatchablePromiseIsSettled(@TempDir Path dir) throws Exception {
            // Erasing an absent key still records the promise, because refusing to would make the
            // durability of a promise depend on a lookup. But it can never be settled by removing
            // anything, so it would age past the deadline and demand a compaction on every tick
            // for the rest of the store's life.
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 16), clock)) {
                fixture.add("doc", 4, 1);
                assertThat(fixture.store.erase(Set.of("never-existed"))).isZero();
                clock.advance(Duration.ofHours(2));

                CompactionResult settled = fixture.compactIfDue(1);
                assertThat(settled.performed()).isTrue();
                assertThat(settled.documentsRemoved()).isZero();
                assertThat(settled.segmentsRewritten())
                    .as("nothing had to be rewritten to keep this promise")
                    .isZero();
                assertThat(fixture.store.outstandingErasureRequests()).isEmpty();

                assertThat(fixture.compactIfDue(1).performed())
                    .as("and it does not demand compaction again")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a promise keeps its age across a restart")
        void promisesSurviveRestart(@TempDir Path dir) throws Exception {
            TestClock clock = new TestClock();
            Instant promisedAt = clock.instant();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 16), clock)) {
                fixture.add("doc", 10, 1);
                fixture.store.erase(Set.of("doc3"));
            }

            clock.advance(Duration.ofHours(2));
            try (LexicalStore reopened = LexicalStore.open(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 16), clock)) {
                assertThat(reopened.outstandingErasureRequests())
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.key()).isEqualTo("doc3");
                        assertThat(record.requestedAt()).isEqualTo(promisedAt);
                    });
                assertThat(reopened.planCompaction(1))
                    .as("a deadline that passed while the process was down is still a deadline")
                    .isPresent();
            }
        }
    }

    @Nested
    @DisplayName("The cost")
    class Cost {

        @Test
        @DisplayName("a segment that would not repay its rewrite is left alone")
        void thinSegmentsAreNotRewritten(@TempDir Path dir) throws Exception {
            // The store-wide ratio decides that some reclaiming is due; it does not decide which
            // segments repay it. A crowded segment beside a quiet one must not drag the quiet one
            // into a rewrite that reclaims almost nothing.
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.2, Duration.ofHours(24), 16), clock)) {
                fixture.add("crowded", 10, 1);
                fixture.add("quiet", 10, 1);
                fixture.store.erase(Set.of("crowded0", "crowded1", "crowded2", "crowded3",
                    "crowded4", "crowded5", "crowded6", "crowded7"));
                fixture.store.erase(Set.of("quiet0"));

                String quietBefore = fixture.store.segments().get(1).name();
                CompactionResult result = fixture.compactIfDue(1);

                assertThat(result.performed()).isTrue();
                assertThat(result.segmentsRewritten())
                    .as("only the crowded segment repays a rewrite")
                    .isEqualTo(1);
                assertThat(result.documentsRemoved()).isEqualTo(8);
                assertThat(fixture.store.segments())
                    .extracting(SegmentManifest.Entry::name)
                    .contains(quietBefore);
                assertThat(fixture.store.outstandingErasures())
                    .as("the quiet segment's tombstone is still owed")
                    .isEqualTo(1);
                assertThat(fixture.store.outstandingErasureRequests())
                    .singleElement()
                    .satisfies(record -> assertThat(record.key()).isEqualTo("quiet0"));
            }
        }

        @Test
        @DisplayName("nothing is due when there is nothing to reclaim")
        void quietStoresAreLeftAlone(@TempDir Path dir) throws Exception {
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.2, Duration.ofHours(1), 16), clock)) {
                fixture.add("doc", 20, 1);

                CompactionResult result = fixture.compactIfDue(1);

                assertThat(result.performed()).isFalse();
                assertThat(result.reason()).contains("not due");
                assertThat(fixture.store.segments()).hasSize(1);
            }
        }

        @Test
        @DisplayName("a policy of its own is obeyed, and still makes progress")
        void customPolicyStillProgresses(@TempDir Path dir) throws Exception {
            // A pluggable policy owes the selection no relationship at all. One that fires on
            // grounds the selection does not model must still remove something, or it will fire
            // on identical numbers on every tick forever.
            TestClock clock = new TestClock();
            CompactionPolicy always = (stats, now) -> true;
            CompactionPlanner planner = new CompactionPlanner(
                always, 0.9, Duration.ofDays(365), 16, 8);
            try (Fixture fixture = new Fixture(dir, planner, clock)) {
                fixture.add("doc", 10, 1);
                fixture.replace("doc4", "confidentialpayload must not survive");
                fixture.store.erase(Set.of("doc4"));

                CompactionResult result = fixture.compactIfDue(1);

                assertThat(result.performed()).isTrue();
                assertThat(result.documentsRemoved())
                    .as("one tenth is under the 0.9 threshold, but doing nothing would loop")
                    .isEqualTo(1);
                assertThat(anyFileContains(dir, "confidentialpayload")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("The segment count")
    class Counts {

        @Test
        @DisplayName("merging takes the smallest segments, and only as many as the bound needs")
        void smallestSegmentsMergeFirst(@TempDir Path dir) throws Exception {
            // Count falls by one per segment merged whatever their size, so the smallest buy the
            // same reduction as the largest for the least writing.
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 3), clock)) {
                for (int i = 0; i < 5; i++) {
                    fixture.add("s" + i + "d", i + 1, 1);
                }
                assertThat(fixture.store.segments()).hasSize(5);

                CompactionResult result = fixture.compactIfDue(1);

                assertThat(result.performed()).isTrue();
                assertThat(fixture.store.segments()).hasSize(3);
                assertThat(fixture.store.segments())
                    .extracting(SegmentManifest.Entry::docCount)
                    .as("the three smallest (1+2+3) merged; the two largest were left alone")
                    .containsExactlyInAnyOrder(6, 4, 5);
                assertThat(fixture.store.liveDocuments(1)).isEqualTo(15);
            }
        }

        @Test
        @DisplayName("a merge will not cross the epoch floor")
        void mergesRespectTheFloor(@TempDir Path dir) throws Exception {
            // Merging segments published at different epochs changes what a reader between them
            // sees. The floor is the caller's statement of how far back a reader may be.
            TestClock clock = new TestClock();
            try (Fixture fixture = new Fixture(dir,
                    CompactionPlanner.standard(0.5, Duration.ofHours(1), 1), clock)) {
                fixture.add("a", 2, 1);
                fixture.add("b", 2, 2);
                fixture.add("c", 2, 3);

                CompactionResult held = fixture.compactIfDue(1);
                assertThat(held.performed()).isTrue();
                assertThat(held.segmentsRewritten())
                    .as("only one segment is at or below the floor, so there is no merge to make")
                    .isZero();
                assertThat(fixture.store.segments()).hasSize(3);
                assertThat(fixture.store.liveDocuments(2))
                    .as("a reader at epoch 2 still sees exactly what it saw")
                    .isEqualTo(4);

                CompactionResult released = fixture.compactIfDue(3);

                assertThat(released.performed()).isTrue();
                assertThat(fixture.store.segments()).hasSize(1);
                assertThat(fixture.store.segments().get(0).epoch())
                    .as("the merged segment claims the highest epoch of its inputs, never an "
                        + "earlier one it was not visible at")
                    .isEqualTo(3);
                assertThat(fixture.store.liveDocuments(3)).isEqualTo(6);
            }
        }
    }

    @Nested
    @DisplayName("The erasure log")
    class Log {

        @Test
        @DisplayName("a log left empty by a crash does not swallow the next promise")
        void emptyLogKeepsTheNextPromise(@TempDir Path dir) throws Exception {
            // Created but not yet written is exactly what a crash between the two leaves. Appending
            // to it without a header would make the first record look like one on the next read,
            // discharging a promise nobody agreed to discharge.
            Files.write(dir.resolve("erasure.log"), new byte[0]);

            DocumentErasureLog log = DocumentErasureLog.open(dir);
            log.append(List.of("doc1", "doc2"), Instant.parse("2026-01-01T00:00:00Z"));

            assertThat(DocumentErasureLog.open(dir).outstanding())
                .extracting(DocumentErasureLog.Record::key)
                .containsExactly("doc1", "doc2");
        }

        @Test
        @DisplayName("a file that is not an erasure log is refused, not partly believed")
        void foreignFileIsRefused(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("erasure.log"), "something else entirely\n");

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not an erasure log");
        }

        @Test
        @DisplayName("what is kept survives a rewrite, and the oldest is the oldest")
        void retainKeepsTheRest(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            Instant first = Instant.parse("2026-01-01T00:00:00Z");
            log.append(List.of("a"), first);
            log.append(List.of("b"), first.plus(Duration.ofHours(3)));

            assertThat(log.oldestOutstanding()).contains(first);

            log.retain(Set.of("b"));

            assertThat(DocumentErasureLog.open(dir).outstanding())
                .extracting(DocumentErasureLog.Record::key)
                .containsExactly("b");
            assertThat(DocumentErasureLog.open(dir).oldestOutstanding())
                .as("the oldest promise is now b's, since a's is kept")
                .contains(first.plus(Duration.ofHours(3)));

            log.retain(Set.of());
            assertThat(Files.exists(dir.resolve("erasure.log")))
                .as("nothing owed leaves no file to read")
                .isFalse();
            assertThat(DocumentErasureLog.open(dir).oldestOutstanding()).isEmpty();
        }

        @Test
        @DisplayName("a confirmed promise is still owed but no longer replayed, across a reopen")
        void confirmationSurvivesReopening(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            log.append(List.of("a", "b"), at);
            log.confirmApplied(List.of("a"));

            DocumentErasureLog reopened = DocumentErasureLog.open(dir);
            assertThat(reopened.outstandingKeys()).containsExactly("a", "b");
            assertThat(reopened.unappliedKeys()).containsExactly("b");
            assertThat(reopened.oldestOutstanding()).contains(at);
        }

        @Test
        @DisplayName("a later promise of a confirmed key is unapplied until it is confirmed too")
        void laterPromiseIsNotConfirmedByAnEarlierOne(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            log.append(List.of("a"), at);
            log.confirmApplied(List.of("a"));
            log.append(List.of("a"), at.plus(Duration.ofMinutes(1)));

            assertThat(DocumentErasureLog.open(dir).unappliedKeys()).containsExactly("a");
        }

        @Test
        @DisplayName("confirmation survives the rewrite a compaction makes")
        void retainKeepsConfirmation(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            log.append(List.of("a", "b", "c"), at);
            log.confirmApplied(List.of("a", "b"));
            log.retain(Set.of("a", "c"));

            DocumentErasureLog reopened = DocumentErasureLog.open(dir);
            assertThat(reopened.outstandingKeys()).containsExactly("a", "c");
            assertThat(reopened.unappliedKeys()).containsExactly("c");
        }

        @Test
        @DisplayName("a version 1 log is read as wholly unapplied, and upgraded before it grows")
        void versionOneIsUpgraded(@TempDir Path dir) throws Exception {
            // Version 1 recorded nothing about application, so replaying all of it is the only
            // reading that cannot lose a promise.
            Files.writeString(dir.resolve("erasure.log"),
                "excalibur-lexical-erasure v1\n1767225600000\told\n");

            DocumentErasureLog log = DocumentErasureLog.open(dir);
            assertThat(log.unappliedKeys()).containsExactly("old");

            log.append(List.of("new"), Instant.parse("2026-01-02T00:00:00Z"));
            log.confirmApplied(List.of("old"));

            assertThat(Files.readAllLines(dir.resolve("erasure.log")).get(0))
                .isEqualTo("excalibur-lexical-erasure v2");
            DocumentErasureLog reopened = DocumentErasureLog.open(dir);
            assertThat(reopened.outstandingKeys()).containsExactly("old", "new");
            assertThat(reopened.unappliedKeys()).containsExactly("new");
            assertThat(reopened.oldestOutstanding())
                .contains(Instant.ofEpochMilli(1767225600000L));
        }
    }

    @Nested
    @DisplayName("Inputs")
    class Inputs {

        @Test
        @DisplayName("a key that would corrupt the keys file is refused where it enters")
        void keysAreValidated(@TempDir Path dir) throws Exception {
            // Keys are read back positionally. A line break would shift every document after it,
            // so every hit in the segment would report a different document's key - correct index,
            // correct search, wrong answer, and nothing downstream able to notice.
            try (Fixture fixture = new Fixture(dir, CompactionPlanner.standard(), new TestClock())) {
                Map<String, String> bad = new LinkedHashMap<>();
                bad.put("fine", "text");
                bad.put("br\noken", "text");

                assertThatThrownBy(() -> fixture.store.add(bad, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("line break");
                assertThatThrownBy(() -> fixture.store.erase(Set.of("br\token")))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> fixture.store.add(Map.of("", "text"), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
            }
        }

        @Test
        @DisplayName("files an interrupted write left behind are removed on open")
        void unreferencedFilesAreRemoved(@TempDir Path dir) throws Exception {
            try (Fixture fixture = new Fixture(dir, CompactionPlanner.standard(), new TestClock())) {
                fixture.add("doc", 3, 1);
            }
            Path orphanSegment = Files.createDirectories(dir.resolve("seg-99"));
            Files.writeString(orphanSegment.resolve("post.dat"), "junk");
            Files.writeString(dir.resolve("seg-99.keys"), "ghost\n");
            Path scratch = Files.createDirectories(dir.resolve(".compact-seg-98"));
            Files.writeString(scratch.resolve("piece-0"), "junk");

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.segments()).hasSize(1);
                assertThat(Files.exists(orphanSegment)).isFalse();
                assertThat(Files.exists(dir.resolve("seg-99.keys"))).isFalse();
                assertThat(Files.exists(scratch)).isFalse();
                assertThat(reopened.liveDocuments(1)).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("a plan that names one segment twice is refused")
        void plansAreValidated() {
            assertThatThrownBy(() -> new CompactionPlan(
                List.of(List.of("seg-1", "seg-2"), List.of("seg-2")), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two groups");
            assertThatThrownBy(() -> new CompactionPlan(List.of(List.of()), "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name a segment");
            assertThat(CompactionPlan.nothingToRewrite("quiet").rewritesNothing()).isTrue();
        }

        @Test
        @DisplayName("thresholds that cannot be satisfied are refused at construction")
        void thresholdsAreValidated() {
            CompactionPolicy any = (stats, now) -> true;
            assertThatThrownBy(() -> new CompactionPlanner(any, 0, Duration.ZERO, 4, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstoneRatio");
            assertThatThrownBy(() -> new CompactionPlanner(any, 0.2, Duration.ofDays(-1), 4, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxErasureLatency");
            assertThatThrownBy(() -> new CompactionPlanner(any, 0.2, Duration.ZERO, 0, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSegments");
            assertThatThrownBy(() -> new CompactionPlanner(any, 0.2, Duration.ZERO, 4, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mergeFanIn");
            assertThatThrownBy(() -> new SegmentManifest.Entry("seg-0", 1, 4, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstoned");
        }
    }
}
