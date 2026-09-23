package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the store does with the files beside its segments - the key table and the tombstones -
 * when their checksums do not match, when a newer build wrote them, and when an older build did.
 */
@DisplayName("Lexical store sidecars")
class LexicalStoreSidecarTest {

    private static final long READ_AT = 99;

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

    /**
     * A store of two segments, the first holding a1 and a2 with a2 replaced by a later copy in
     * the second, so the first carries a tombstone; returns the first segment's name.
     */
    private String twoSegmentsWithAMark(Path dir) throws IOException {
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(docs("a1", "quorum replication latency", "a2", "quorum election timeout"), 1);
            store.add(docs("a2", "quorum election rewritten", "b1", "quorum snapshot transfer"), 2);
            return store.segments().get(0).name();
        }
    }

    /** Writes a segment's marks back as the headerless version an older build wrote. */
    private static void writeHeaderless(Path dir, String segment, int docCount)
            throws IOException {
        Path file = TombstoneFile.of(dir, segment);
        boolean[] marks = TombstoneFile.read(file, docCount).marks();
        byte[] bits = new byte[(docCount + 7) / 8];
        for (int i = 0; i < docCount; i++) {
            if (marks[i]) {
                bits[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        Files.write(file, bits);
    }

    @Nested
    @DisplayName("tombstones that fail their checksum")
    class DamagedTombstones {

        @Test
        @DisplayName("keep the segment out of every query, and it is not rebuilt")
        void segmentIsKeptOut(@TempDir Path dir) throws Exception {
            String damaged = twoSegmentsWithAMark(dir);
            LexicalSegmentCorruptionTest.flipByte(TombstoneFile.of(dir, damaged), 16);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement().satisfies(segment -> {
                    assertThat(segment.name()).isEqualTo(damaged);
                    assertThat(segment.rebuildable())
                        .as("a rebuild drops tombstoned documents, and which are is unknown")
                        .isFalse();
                    assertThat(segment.reason()).contains("tombstones")
                        .contains("checksum did not match");
                });
                assertThat(store.search(new String[] {"quorum"}, ones(1), 10, READ_AT))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactlyInAnyOrder("a2", "b1");
                assertThat(store.planCompaction(0)).isEmpty();
                assertThat(store.forcedPlan(0).groups()).allSatisfy(group ->
                    assertThat(group).doesNotContain(damaged));
                assertThat(store.fullPlan(0).groups()).allSatisfy(group ->
                    assertThat(group).doesNotContain(damaged));
            }
            assertThat(TombstoneFile.of(dir, damaged)).as("nothing is deleted on open").exists();
        }

        @Test
        @DisplayName("let documents be published over the segment")
        void publishingPassesOver(@TempDir Path dir) throws Exception {
            String damaged = twoSegmentsWithAMark(dir);
            LexicalSegmentCorruptionTest.flipByte(TombstoneFile.of(dir, damaged), 16);

            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a1", "quorum replication reindexed"), 3);

                assertThat(hits(store, "reindexed")).isEqualTo(1);
                assertThat(store.compactNow(storage::get, 0).performed()).isTrue();
                assertThat(store.damagedSegments()).extracting(LexicalStore.DamagedSegment::name)
                    .containsExactly(damaged);
            }
        }

        @Test
        @DisplayName("have the segment removed once an erasure is owed")
        void erasureRemovesTheSegment(@TempDir Path dir) throws Exception {
            String damaged = twoSegmentsWithAMark(dir);
            LexicalSegmentCorruptionTest.flipByte(TombstoneFile.of(dir, damaged), 16);

            try (LexicalStore store = LexicalStore.open(dir)) {
                store.erase(Set.of("b1"));

                assertThat(store.damagedSegments()).isEmpty();
                assertThat(store.unreadableKeysDropped()).isEqualTo(1);
                assertThat(TombstoneFile.of(dir, damaged)).doesNotExist();
                store.compactNow(storage::get, 0);
                assertThat(store.outstandingErasureRequests()).isEmpty();
            }
        }

        @Test
        @DisplayName("damaged after the store opened fail the operation that reads them")
        void damagedWhileOpen(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            try (LexicalStore store = LexicalStore.open(dir)) {
                LexicalSegmentCorruptionTest.flipByte(TombstoneFile.of(dir, first), 16);

                assertThatThrownBy(() -> store.erase(Set.of("a1")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("checksum did not match");
            }
        }
    }

    @Nested
    @DisplayName("tombstones from another build")
    class OtherBuildTombstones {

        @Test
        @DisplayName("from an older build are read, counted outdated, and rewritten by the next mark")
        void headerlessIsUpgraded(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            writeHeaderless(dir, first, 2);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).isEmpty();
                assertThat(store.outdatedSegments()).containsExactly(first);
                assertThat(store.search(new String[] {"election"}, ones(1), 10, READ_AT))
                    .extracting(LexicalStore.Hit::key).containsExactly("a2");
                assertThat(hits(store, "timeout")).as("the replaced copy stays hidden").isZero();

                store.erase(Set.of("a1"));
            }
            byte[] rewritten = Files.readAllBytes(TombstoneFile.of(dir, first));
            assertThat(ByteBuffer.wrap(rewritten).getLong(0)).isEqualTo(TombstoneFile.MAGIC);
            assertThat(TombstoneFile.read(TombstoneFile.of(dir, first), 2).count()).isEqualTo(2);
        }

        @Test
        @DisplayName("from an older build are upgraded by compaction too")
        void headerlessIsCompactedAway(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            writeHeaderless(dir, first, 2);

            try (LexicalStore store = LexicalStore.open(dir)) {
                store.compactNow(storage::get, 0);

                assertThat(store.outdatedSegments()).isEmpty();
                assertThat(hits(store, "timeout")).isZero();
                assertThat(hits(store, "quorum")).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("from a newer build stop the store from opening, and nothing is deleted")
        void newerIsRefused(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            Path file = TombstoneFile.of(dir, first);
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, TombstoneFile.VERSION + 1);
            Files.write(file, bytes);

            assertThatThrownBy(() -> LexicalStore.open(dir))
                .isInstanceOf(TombstoneFile.UnsupportedVersionException.class)
                .hasMessageContaining("newer build");
            assertThat(dir.resolve(first)).exists();
            assertThat(file).exists();
        }
    }

    @Nested
    @DisplayName("a key table that fails its checksum")
    class DamagedKeyTable {

        @Test
        @DisplayName("in its names keeps the segment out of every query, and it is not rebuilt")
        void names(@TempDir Path dir) throws Exception {
            String damaged = twoSegmentsWithAMark(dir);
            // A name still reads as a name with one bit changed: "a1" becomes "`1".
            LexicalSegmentCorruptionTest.flipByte(dir.resolve(damaged + SegmentKeys.KEYS_SUFFIX),
                0);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement().satisfies(segment -> {
                    assertThat(segment.rebuildable()).isFalse();
                    assertThat(segment.reason()).contains("key table")
                        .contains("checksum did not match");
                });
                assertThat(store.search(new String[] {"quorum"}, ones(1), 10, READ_AT))
                    .extracting(LexicalStore.Hit::key).containsExactlyInAnyOrder("a2", "b1");
            }
        }

        @Test
        @DisplayName("in its lookup table has the table rebuilt, and nothing else changes")
        void lookupTable(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            Path table = dir.resolve(first + SegmentKeys.MAP_SUFFIX);
            byte[] before = Files.readAllBytes(table);
            // Clearing every slot leaves a table of the right size that finds nothing: an
            // erasure through it would report the document absent and leave it readable.
            Files.write(table, new byte[before.length]);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).isEmpty();
                assertThat(Files.readAllBytes(table)).isEqualTo(before);
                assertThat(store.erase(Set.of("a1"))).isEqualTo(1);
                assertThat(hits(store, "replication")).isZero();
            }
        }

        @Test
        @DisplayName("in its checksum file alone is rebuilt from sound names")
        void checksumFile(@TempDir Path dir) throws Exception {
            String damaged = twoSegmentsWithAMark(dir);
            LexicalSegmentCorruptionTest.flipByte(KeyChecksums.of(dir, damaged), 20);

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).singleElement().satisfies(segment -> {
                    assertThat(segment.rebuildable()).isTrue();
                    assertThat(segment.reason()).contains("checksum did not match");
                });

                assertThat(store.compactIfDue(storage::get, 0).segmentsRebuilt()).isEqualTo(1);
                assertThat(store.damagedSegments()).isEmpty();
                assertThat(hits(store, "quorum")).isEqualTo(3);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.damagedSegments()).isEmpty();
                assertThat(reopened.outdatedSegments()).isEmpty();
            }
        }

        @Test
        @DisplayName("from a newer build stops the store from opening")
        void newerIsRefused(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            Path sums = KeyChecksums.of(dir, first);
            byte[] bytes = Files.readAllBytes(sums);
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, KeyChecksums.VERSION + 1);
            Files.write(sums, bytes);

            assertThatThrownBy(() -> LexicalStore.open(dir))
                .isInstanceOf(KeyChecksums.UnsupportedVersionException.class);
            assertThat(dir.resolve(first + SegmentKeys.KEYS_SUFFIX)).exists();
        }
    }

    @Nested
    @DisplayName("as a checker sees them")
    class Inspected {

        @Test
        @DisplayName("are every one intact as the store writes them, tombstones only where marked")
        void intact(@TempDir Path dir) throws Exception {
            twoSegmentsWithAMark(dir);
            SegmentManifest manifest = SegmentManifest.read(dir);

            var marked = LexicalSidecars.inspect(dir, manifest.entries().get(0));
            var unmarked = LexicalSidecars.inspect(dir, manifest.entries().get(1));

            assertThat(marked).hasSize(5).allSatisfy(report -> assertThat(report.status())
                .isEqualTo(com.eonmux.excalibur.core.integrity.IntegrityStatus.INTACT));
            assertThat(marked.get(4).file().getFileName().toString())
                .endsWith(LexicalSidecars.TOMBSTONES);
            assertThat(unmarked).hasSize(4);
        }
    }

    @Nested
    @DisplayName("a key table written before checksums")
    class LegacyKeyTable {

        @Test
        @DisplayName("is served, counted outdated, and given checksums by compaction")
        void upgradedByCompaction(@TempDir Path dir) throws Exception {
            String first = twoSegmentsWithAMark(dir);
            Files.delete(KeyChecksums.of(dir, first));

            try (LexicalStore store = LexicalStore.open(dir)) {
                assertThat(store.damagedSegments()).isEmpty();
                assertThat(store.outdatedSegments()).containsExactly(first);
                assertThat(hits(store, "quorum")).isEqualTo(3);

                store.compactIfDue(storage::get, 0);

                assertThat(store.outdatedSegments()).isEmpty();
                for (SegmentManifest.Entry entry : store.segments()) {
                    assertThat(KeyChecksums.read(dir, entry.name())).isNotNull();
                }
            }
        }
    }
}
