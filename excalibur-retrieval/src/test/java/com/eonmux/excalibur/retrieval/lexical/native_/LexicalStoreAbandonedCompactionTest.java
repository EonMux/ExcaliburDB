package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A compaction that cannot finish a rewrite leaves the store as it found it.
 *
 * <p>Two ways a rewrite ends short of its plan. The caller asks it to stop - a shutdown that has
 * to close the storage its text comes from, or an operator's cancel - and a rewrite still reading
 * that text after storage closed would be reading freed memory. Or storage fails to answer for
 * one key, and a rewrite that took the failure for "the chunk is gone" would drop the key's
 * postings for good from a segment whose text is still there. Both have to leave every segment
 * they did not finish exactly as it was, and no files behind them.</p>
 */
@DisplayName("Lexical store: compactions that end early")
class LexicalStoreAbandonedCompactionTest {

    /** Three segments above the floor, so a full rewrite plans three separate groups. */
    private static Map<String, String> threeSegments(LexicalStore store) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, String> batch = Map.of("k" + i, "shared term" + i);
            all.putAll(batch);
            store.add(batch, i);
        }
        return all;
    }

    /** Every file name under the store, for a before-and-after comparison. */
    private static List<String> files(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> directory.relativize(path).toString())
                .sorted()
                .toList();
        }
    }

    private static double[] one() {
        return new double[] { 1.0 };
    }

    @Nested
    @DisplayName("asked to stop")
    class Stopped {

        @Test
        @DisplayName("stops between groups, discards what it built and leaves the manifest alone")
        void stopsBetweenGroups(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = threeSegments(store);
                List<SegmentManifest.Entry> before = store.segments();
                List<String> filesBefore = files(dir);

                AtomicInteger reads = new AtomicInteger();
                AtomicBoolean stop = new AtomicBoolean();
                Function<String, String> textOf = key -> {
                    // The first group is built whole; the request arrives before the second.
                    if (reads.incrementAndGet() == 1) {
                        stop.set(true);
                    }
                    return all.get(key);
                };

                Optional<LexicalStore.PreparedCompaction> prepared = store.buildUnlessStopped(
                    textOf, store.snapshot(store.fullPlan(0)), stop::get);

                assertThat(prepared).as("a stopped build has nothing to publish").isEmpty();
                assertThat(reads.get()).as("nothing was read after the stop").isEqualTo(1);
                assertThat(store.segments()).isEqualTo(before);
                assertThat(files(dir))
                    .as("the group it had finished is discarded, not left for a sweep")
                    .isEqualTo(filesBefore);
                assertThat(store.search(new String[] {"shared"}, one(), 10, 10)).hasSize(3);
            }
        }

        @Test
        @DisplayName("stops inside a group without publishing the part it had read")
        void stopsInsideAGroup(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = new LinkedHashMap<>();
                for (int i = 0; i < 4; i++) {
                    all.put("d" + i, "merged term" + i);
                }
                store.add(all, 1);
                List<String> filesBefore = files(dir);

                AtomicBoolean stop = new AtomicBoolean();
                Optional<LexicalStore.PreparedCompaction> prepared = store.buildUnlessStopped(
                    key -> {
                        stop.set(true);
                        return all.get(key);
                    },
                    store.snapshot(store.fullPlan(10)), stop::get);

                assertThat(prepared).isEmpty();
                assertThat(files(dir)).isEqualTo(filesBefore);
                assertThat(store.liveDocuments(10)).isEqualTo(4);
            }
        }

        @Test
        @DisplayName("a build nobody stops publishes as before")
        void anUnstoppedBuildPublishes(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = threeSegments(store);
                store.erase(Set.of("k2"));

                Optional<LexicalStore.PreparedCompaction> prepared = store.buildUnlessStopped(
                    all::get, store.snapshot(store.fullPlan(10)), () -> false);

                assertThat(prepared).isPresent();
                CompactionResult result = store.commit(prepared.orElseThrow());
                assertThat(result.documentsRemoved()).isEqualTo(1);
                assertThat(store.liveDocuments(10)).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("when storage cannot answer")
    class FailedReads {

        @Test
        @DisplayName("a failed read abandons its group, and the old segment keeps the chunk")
        void aFailedReadKeepsTheSegment(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = threeSegments(store);
                String untouched = store.segments().get(1).name();

                Function<String, String> textOf = key -> {
                    if (key.equals("k2")) {
                        throw new UncheckedIOException(new IOException("device busy"));
                    }
                    return all.get(key);
                };

                CompactionResult result = store.compact(textOf, store.fullPlan(0));

                assertThat(result.segmentsRewritten())
                    .as("the other two groups are rewritten")
                    .isEqualTo(2);
                assertThat(result.documentsRemoved()).isZero();
                assertThat(store.segments())
                    .extracting(SegmentManifest.Entry::name)
                    .as("the segment whose text could not be read is still the one served")
                    .contains(untouched);
                assertThat(store.search(new String[] {"term2"}, one(), 10, 10))
                    .singleElement()
                    .satisfies(hit -> assertThat(hit.key()).isEqualTo("k2"));
                assertThat(files(dir))
                    .as("the abandoned rewrite leaves no files behind")
                    .noneMatch(name -> name.startsWith(".compact-"));
            }
        }

        @Test
        @DisplayName("an erasure in an abandoned group stays owed until a rewrite reaches it")
        void anAbandonedGroupKeepsItsErasureOwed(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(Map.of("keep", "retained", "gone", "erased"), 1);
                store.erase(Set.of("gone"));

                store.compact(key -> {
                    throw new UncheckedIOException(new IOException("device busy"));
                }, store.forcedPlan(10));

                assertThat(store.outstandingErasures()).isEqualTo(1);
                assertThat(store.outstandingErasureRequests()).hasSize(1);

                store.compact(Map.of("keep", "retained")::get, store.forcedPlan(10));
                assertThat(store.outstandingErasures()).isZero();
                assertThat(store.outstandingErasureRequests()).isEmpty();
            }
        }

        @Test
        @DisplayName("a compaction whose every group was abandoned is not reported as performed")
        void everyGroupAbandonedIsNotPerformed(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(Map.of("keep", "retained", "gone", "erased"), 1);
                store.erase(Set.of("gone"));
                List<SegmentManifest.Entry> before = store.segments();

                CompactionResult result = store.compact(key -> {
                    throw new UncheckedIOException(new IOException("device busy"));
                }, store.forcedPlan(10));

                assertThat(result.performed())
                    .as("nothing reached the manifest, so nothing was compacted")
                    .isFalse();
                assertThat(result.groupsAbandoned()).isEqualTo(1);
                assertThat(result.partial()).isFalse();
                assertThat(result.segmentsRewritten()).isZero();
                assertThat(result.documentsRemoved()).isZero();
                assertThat(result.reason()).contains("nothing published").contains("1 group(s)");
                assertThat(store.segments()).isEqualTo(before);
                assertThat(store.outstandingErasures()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a compaction that published some groups and abandoned one is partial")
        void someGroupsAbandonedIsPartial(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = threeSegments(store);

                CompactionResult result = store.compact(key -> {
                    if (key.equals("k2")) {
                        throw new UncheckedIOException(new IOException("device busy"));
                    }
                    return all.get(key);
                }, store.fullPlan(0));

                assertThat(result.performed()).isTrue();
                assertThat(result.groupsAbandoned()).isEqualTo(1);
                assertThat(result.partial()).isTrue();
                assertThat(result.reason()).startsWith("partial:");
            }
        }

        @Test
        @DisplayName("a compaction that abandoned nothing reports no abandoned groups")
        void nothingAbandoned(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> all = threeSegments(store);

                CompactionResult result = store.compact(all::get, store.fullPlan(0));

                assertThat(result.performed()).isTrue();
                assertThat(result.groupsAbandoned()).isZero();
                assertThat(result.partial()).isFalse();
            }
        }

        @Test
        @DisplayName("null still means the chunk is gone, and drops it")
        void nullStillDrops(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(Map.of("here", "present", "absent", "missing"), 1);

                CompactionResult result = store.compact(Map.of("here", "present")::get,
                    store.fullPlan(10));

                assertThat(result.documentsRemoved()).isEqualTo(1);
                assertThat(store.search(new String[] {"missing"}, one(), 10, 10)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("when a key's text is permanently unreadable")
    class UnrecoverableReads {

        @Test
        @DisplayName("the key is dropped, counted, and an erasure in its group is discharged")
        void dropsTheKeyAndDischargesTheGroup(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(Map.of("keep", "retained", "damaged", "corrupted", "gone", "erased"), 1);
                store.erase(Set.of("gone"));

                CompactionResult result = store.compact(key -> {
                    if (key.equals("damaged")) {
                        throw new UnrecoverableTextException(key,
                            new IllegalStateException("checksum mismatch"));
                    }
                    return Map.of("keep", "retained").get(key);
                }, store.forcedPlan(10));

                assertThat(result.performed()).isTrue();
                assertThat(result.groupsAbandoned())
                    .as("a permanent failure does not abandon the group")
                    .isZero();
                assertThat(result.documentsRemoved())
                    .as("the erased chunk and the unreadable one both left the segment")
                    .isEqualTo(2);
                assertThat(store.unreadableKeysDropped()).isEqualTo(1);
                assertThat(store.outstandingErasures()).isZero();
                assertThat(store.outstandingErasureRequests()).isEmpty();
                assertThat(store.search(new String[] {"corrupted"}, one(), 10, 10)).isEmpty();
                assertThat(store.search(new String[] {"retained"}, one(), 10, 10)).hasSize(1);
            }
        }

        @Test
        @DisplayName("a transient failure beside it still abandons, and is not counted as dropped")
        void transientFailuresAreNotCounted(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(Map.of("keep", "retained"), 1);

                store.compact(key -> {
                    throw new UncheckedIOException(new IOException("device busy"));
                }, store.fullPlan(10));

                assertThat(store.unreadableKeysDropped()).isZero();
                assertThat(store.liveDocuments(10)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the exception names the key and keeps its cause")
        void exceptionNamesTheKey() {
            IllegalStateException cause = new IllegalStateException("fails its checksum");
            UnrecoverableTextException e = new UnrecoverableTextException("k", cause);

            assertThat(e.key()).isEqualTo("k");
            assertThat(e.getCause()).isSameAs(cause);
            assertThat(e.getMessage()).contains("k").contains("fails its checksum");
            assertThat(new UnrecoverableTextException("k", null).getMessage())
                .endsWith("permanently unreadable");
        }
    }
}
