package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a store does about segments that need rewriting for what they are.
 *
 * <p>A segment that cannot be opened is kept out of every query, which is the safe half of the
 * answer; these tests are for the other half. Its documents must not be reported as searchable
 * while they are not, and when its key table is sound a compaction can rebuild it - the store
 * never needed the postings to do that, only the names and the text the caller reads back. A
 * segment written in an older format is rewritten into the current one the same way, so an
 * upgrade finishes on its own rather than waiting for an operator to force it.</p>
 */
@DisplayName("Lexical store repair")
class LexicalStoreRepairTest {

    private static final long READ_AT = 99;

    /** Everything ever added, which is what storage would hand a compaction back. */
    private final Map<String, String> storage = new LinkedHashMap<>();

    private Map<String, String> docs(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        storage.putAll(map);
        return map;
    }

    private static double[] ones(int n) {
        double[] weights = new double[n];
        java.util.Arrays.fill(weights, 1.0);
        return weights;
    }

    private static long hits(LexicalStore store, String term) throws IOException {
        return store.search(new String[] {term}, ones(1), 100, READ_AT).size();
    }

    /** A store of two segments whose first one will be damaged; returns that segment's name. */
    private String twoSegments(Path dir) throws IOException {
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(docs("a1", "quorum replication latency", "a2", "quorum election timeout"), 1);
            store.add(docs("b1", "quorum snapshot transfer"), 2);
            return store.segments().get(0).name();
        }
    }

    private static void damagePostings(Path dir, String segment) throws IOException {
        LexicalSegmentCorruptionTest.flipByte(
            dir.resolve(segment).resolve(SegmentFiles.POST_DAT), 0);
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    /** Rewrites a segment's metadata as a build that wrote no column checksums produced it. */
    private static void downgrade(Path segment, int version) throws IOException {
        Path meta = segment.resolve(IndexMeta.FILE_NAME);
        byte[] legacy = java.util.Arrays.copyOf(Files.readAllBytes(meta),
            IndexMeta.BYTES_WITH_BM25);
        ByteBuffer.wrap(legacy).order(ByteOrder.BIG_ENDIAN).putInt(12, version);
        Files.write(meta, legacy);
    }

    @Nested
    @DisplayName("a segment that cannot be opened")
    class Damaged {

        @Test
        @DisplayName("is not counted as searchable, and is counted as unsearchable")
        void countedApart(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.liveDocuments(READ_AT))
                    .as("live is what a query can match, and nothing matches in that segment")
                    .isEqualTo(1);
                assertThat(store.unsearchableDocuments(READ_AT)).isEqualTo(2);
                assertThat(store.damagedSegments()).singleElement().satisfies(segment -> {
                    assertThat(segment.name()).isEqualTo(damaged);
                    assertThat(segment.liveDocuments()).isEqualTo(2);
                    assertThat(segment.rebuildable()).isTrue();
                    assertThat(segment.reason()).contains(SegmentFiles.POST_DAT);
                });
            }
        }

        @Test
        @DisplayName("is rebuilt by the next compaction, though nothing in it was erased")
        void rebuiltWithoutBeingDue(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.planCompaction(0)).get()
                    .satisfies(plan -> assertThat(plan.reason()).contains("damaged"));

                CompactionResult result = store.compactIfDue(storage::get, 0);

                assertThat(result.performed()).isTrue();
                assertThat(result.segmentsRebuilt()).isEqualTo(1);
                assertThat(result.segmentsRewritten()).isEqualTo(1);
                assertThat(store.damagedSegments()).isEmpty();
                assertThat(store.unreadableSegments()).isEmpty();
                assertThat(store.unsearchableDocuments(READ_AT)).isZero();
                assertThat(store.liveDocuments(READ_AT)).isEqualTo(3);
                assertThat(hits(store, "quorum")).isEqualTo(3);
                assertThat(store.planCompaction(0)).as("nothing left to rebuild").isEmpty();
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.damagedSegments()).isEmpty();
                assertThat(hits(reopened, "quorum")).isEqualTo(3);
                assertThat(dir.resolve(damaged)).as("the damaged bytes went with the rebuild")
                    .doesNotExist();
            }
        }

        @Test
        @DisplayName("is rebuilt when its whole directory was moved aside")
        void rebuiltAfterQuarantine(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            // What excalibur-fsck --repair does with a segment it finds corrupt.
            deleteRecursively(dir.resolve(damaged));

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement()
                    .satisfies(segment -> assertThat(segment.rebuildable()).isTrue());

                CompactionResult result = store.compactIfDue(storage::get, 0);

                assertThat(result.segmentsRebuilt()).isEqualTo(1);
                assertThat(hits(store, "quorum")).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("keeps a document erased in it erased through the rebuild")
        void erasureSurvivesTheRebuild(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);

            try (LexicalStore store = LexicalStore.open(dir)) {
                store.erase(java.util.Set.of("a2"));
                assertThat(store.unsearchableDocuments(READ_AT)).isEqualTo(1);

                CompactionResult result = store.compactIfDue(storage::get, 0);

                assertThat(result.segmentsRebuilt()).isEqualTo(1);
                assertThat(store.search(new String[] {"quorum"}, ones(1), 10, READ_AT))
                    .extracting(LexicalStore.Hit::key).containsExactlyInAnyOrder("a1", "b1");
                assertThat(store.outstandingErasureRequests()).isEmpty();
            }
        }

        @Test
        @DisplayName("stays damaged, and is retried, when its text cannot be read back")
        void abandonedWhenTextIsUnavailable(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);

            try (LexicalStore store = LexicalStore.open(dir)) {
                CompactionResult failed = store.compactIfDue(key -> {
                    throw new UncheckedIOException(new IOException("storage is unavailable"));
                }, 0);

                assertThat(failed.performed()).isFalse();
                assertThat(failed.segmentsRebuilt()).isZero();
                assertThat(store.damagedSegments()).hasSize(1);
                assertThat(store.planCompaction(0)).as("the next compaction tries again")
                    .isPresent();

                assertThat(store.compactIfDue(storage::get, 0).segmentsRebuilt()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("is reported but not planned when its key table is damaged too")
        void unrecoverableWithoutItsKeys(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);
            Files.write(dir.resolve(damaged + SegmentKeys.INDEX_SUFFIX), new byte[] {0, 0, 0});

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement().satisfies(segment -> {
                    assertThat(segment.rebuildable())
                        .as("without the names there is nothing to read the text back under")
                        .isFalse();
                    assertThat(segment.reason()).contains("key table");
                });
                assertThat(store.unsearchableDocuments(READ_AT)).isEqualTo(2);
                assertThat(store.planCompaction(0)).isEmpty();
            }
        }

        @Test
        @DisplayName("is not rebuilt from a key table that names a different number of documents")
        void keyTableCountMustAgree(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);
            Path offsets = dir.resolve(damaged + SegmentKeys.INDEX_SUFFIX);
            byte[] bytes = Files.readAllBytes(offsets);
            Files.write(offsets, java.util.Arrays.copyOf(bytes, bytes.length - Integer.BYTES));
            Files.deleteIfExists(dir.resolve(damaged + SegmentKeys.MAP_SUFFIX));

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement()
                    .satisfies(segment -> assertThat(segment.rebuildable()).isFalse());
            }
        }

        @Test
        @DisplayName("is rebuilt by a forced compaction too")
        void forcedCompactionRebuilds(@TempDir Path dir) throws Exception {
            String damaged = twoSegments(dir);
            damagePostings(dir, damaged);

            try (LexicalStore store = LexicalStore.open(dir)) {
                CompactionResult result = store.compactNow(storage::get, 0);

                assertThat(result.segmentsRebuilt()).isEqualTo(1);
                assertThat(store.damagedSegments()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("a segment written in an older format")
    class Outdated {

        @Test
        @DisplayName("is counted, and upgraded by the next compaction without being forced")
        void upgradedOnItsOwn(@TempDir Path dir) throws Exception {
            String old = twoSegments(dir);
            downgrade(dir.resolve(old), IndexMeta.VERSION_WITH_STATISTIC_FREE_BLOCKS);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.outdatedSegments()).containsExactly(old);
                assertThat(store.liveDocuments(READ_AT)).as("still searchable").isEqualTo(3);

                CompactionResult result = store.compactIfDue(storage::get, 0);

                assertThat(result.performed()).isTrue();
                assertThat(result.segmentsUpgraded()).isEqualTo(1);
                assertThat(result.segmentsRebuilt()).isZero();
                assertThat(result.reason()).contains("older format");
                assertThat(store.outdatedSegments()).isEmpty();
                assertThat(hits(store, "quorum")).isEqualTo(3);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.outdatedSegments()).isEmpty();
                for (SegmentManifest.Entry entry : reopened.segments()) {
                    assertThat(IndexMeta.read(dir.resolve(entry.name())).checksummed()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("from before statistic-free block bounds is upgraded the same way")
        void bakedInBoundsUpgraded(@TempDir Path dir) throws Exception {
            String old = twoSegments(dir);
            downgrade(dir.resolve(old), 1);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.outdatedSegments()).containsExactly(old);
                assertThat(store.compactIfDue(storage::get, 0).segmentsUpgraded()).isEqualTo(1);
                assertThat(store.outdatedSegments()).isEmpty();
            }
        }

        @Test
        @DisplayName("is left alone on a store whose segments are all current")
        void currentStoreIsNotRewritten(@TempDir Path dir) throws Exception {
            twoSegments(dir);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.outdatedSegments()).isEmpty();
                assertThat(store.planCompaction(0)).isEmpty();
            }
        }
    }
}
