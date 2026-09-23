package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Growing, remembering when, and forgetting.
 *
 * <p>Three properties that are easy to claim and easy to get subtly wrong: that adding documents
 * does not disturb what is already there, that a query reads the corpus as it stood at one instant
 * however much is written while it runs, and that erasure removes data from disk rather than
 * hiding it from queries. The last is the one worth testing hardest, because a store that only
 * hides looks identical from every API and is a broken promise.
 */
@DisplayName("Lexical store")
class LexicalStoreTest {

    private static Map<String, String> docs(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static double[] ones(int n) {
        double[] w = new double[n];
        java.util.Arrays.fill(w, 1.0);
        return w;
    }

    /** Does any file under the store still contain this text? */
    private static boolean anyFileContains(Path directory, String needle) throws Exception {
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                outer:
                for (int i = 0; i + pattern.length <= bytes.length; i++) {
                    for (int j = 0; j < pattern.length; j++) {
                        if (bytes[i + j] != pattern[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Nested
    @DisplayName("incremental addition")
    class Incremental {

        @Test
        @DisplayName("documents added later are searchable without rebuilding")
        void addsWithoutRebuilding(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "the OOM killer terminated the process"), 1);
                store.add(docs("b", "the TLS handshake failed to verify"), 2);
                store.add(docs("c", "spider venom gland toxins"), 3);

                assertThat(store.segments()).hasSize(3);
                assertThat(store.search(new String[] {"oom"}, ones(1), 5, 99))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("a"));
                assertThat(store.search(new String[] {"venom"}, ones(1), 5, 99))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("c"));
            }
        }

        @Test
        @DisplayName("a store survives being closed and reopened")
        void survivesReopen(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "kernel memory pressure"), 1);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.segments()).hasSize(1);
                assertThat(reopened.search(new String[] {"memory"}, ones(1), 5, 99))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("a"));
                // Numbering must continue, not restart, or a new segment would overwrite one.
                reopened.add(docs("b", "certificate chain"), 2);
                assertThat(reopened.segments()).hasSize(2);
                assertThat(reopened.segments().get(1).name())
                    .isNotEqualTo(reopened.segments().get(0).name());
            }
        }

        @Test
        @DisplayName("results from several segments are ranked together, not concatenated")
        void ranksAcrossSegments(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("weak", "memory " + "filler ".repeat(200)), 1);
                store.add(docs("strong", "memory"), 2);

                List<LexicalStore.Hit> hits =
                    store.search(new String[] {"memory"}, ones(1), 5, 99);

                assertThat(hits).hasSize(2);
                assertThat(hits.get(0).key())
                    .as("the shorter document scores higher wherever it lives")
                    .isEqualTo("strong");
            }
        }
    }

    @Nested
    @DisplayName("epoch visibility")
    class Epochs {

        @Test
        @DisplayName("a query sees only what was published at or before its epoch")
        void readsAtAnEpoch(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("first", "sigkill"), 5);
                store.add(docs("second", "sigkill"), 10);

                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 4)).isEmpty();
                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 5)).hasSize(1);
                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 9)).hasSize(1);
                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 10)).hasSize(2);
            }
        }

        @Test
        @DisplayName("a segment becomes visible whole, never partly")
        void visibilityIsAtomic(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> batch = new LinkedHashMap<>();
                for (int i = 0; i < 50; i++) {
                    batch.put("doc" + i, "shared term unique" + i);
                }
                store.add(batch, 7);

                // The epoch before, none of it; the epoch itself, all of it. There is no epoch at
                // which a reader sees part of a batch.
                assertThat(store.search(new String[] {"shared"}, ones(1), 100, 6)).isEmpty();
                assertThat(store.search(new String[] {"shared"}, ones(1), 100, 7)).hasSize(50);
            }
        }

        @Test
        @DisplayName("writing during a read does not change what that read sees")
        void writesDoNotDisturbAReadInProgress(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "memory"), 1);
                long readingAt = 1;
                store.add(docs("b", "memory"), 2);

                assertThat(store.search(new String[] {"memory"}, ones(1), 5, readingAt))
                    .as("a query pinned to epoch 1 must not acquire epoch 2's documents")
                    .hasSize(1);
            }
        }
    }

    /**
     * The version a query result cache keys lexical answers on. It must move whenever a search
     * could answer differently, including when nothing is written at all and the epoch merely
     * crosses a publication, and must stand still otherwise or the cache never hits.
     */
    @Nested
    @DisplayName("search version")
    class SearchVersion {

        @Test
        @DisplayName("stands still while nothing a search reads changes")
        void stableWithoutChange(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "memory"), 1);

                assertThat(store.searchVersion(5)).isEqualTo(store.searchVersion(5));
                assertThat(store.searchVersion(5))
                    .as("a later epoch with no new publication reads the same segments")
                    .isEqualTo(store.searchVersion(9));
            }
        }

        @Test
        @DisplayName("moves when the epoch crosses a publication, with no write observed then")
        void movesWhenAPublicationBecomesVisible(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "memory"), 1);
                store.add(docs("b", "memory"), 10);

                assertThat(store.searchVersion(9)).isNotEqualTo(store.searchVersion(10));
            }
        }

        @Test
        @DisplayName("moves when an erasure hides a document")
        void movesOnErasure(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "memory", "b", "memory"), 1);
                long before = store.searchVersion(5);

                store.erase(Set.of("a"));

                assertThat(store.searchVersion(5)).isNotEqualTo(before);
            }
        }

        @Test
        @DisplayName("moves when compaction replaces the segments")
        void movesOnCompaction(@TempDir Path dir) throws Exception {
            Map<String, String> all = new LinkedHashMap<>();
            try (LexicalStore store = LexicalStore.open(dir)) {
                for (int i = 0; i < 3; i++) {
                    Map<String, String> batch = docs("d" + i, "term" + i + " shared");
                    all.putAll(batch);
                    store.add(batch, i + 1);
                }
                long before = store.searchVersion(10);

                store.compactNow(all::get, 10);

                assertThat(store.segments()).hasSize(1);
                assertThat(store.searchVersion(10)).isNotEqualTo(before);
            }
        }

        @Test
        @DisplayName("is the same for two stores holding the same published state")
        void survivesReopen(@TempDir Path dir) throws Exception {
            long before;
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "memory"), 1);
                before = store.searchVersion(5);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.searchVersion(5)).isEqualTo(before);
            }
        }
    }

    @Nested
    @DisplayName("erasure")
    class Erasure {

        @Test
        @DisplayName("an erased document stops being returned immediately")
        void erasedDocumentsDisappear(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("keep", "sigkill alpha", "drop", "sigkill beta"), 1);

                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 9)).hasSize(2);
                assertThat(store.erase(Set.of("drop"))).isEqualTo(1);
                assertThat(store.search(new String[] {"sigkill"}, ones(1), 5, 9))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("keep"));
            }
        }

        @Test
        @DisplayName("the obligation is on disk before the call returns")
        void obligationIsDurable(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha", "b", "beta"), 1);
                store.erase(Set.of("b"));
            }
            assertThat(Files.exists(dir.resolve("erasure.log")))
                .as("a request that is accepted and then lost to a restart is the one outcome "
                    + "erasure must not have")
                .isTrue();
            assertThat(Files.readString(dir.resolve("erasure.log"))).contains("b");
        }

        @Test
        @DisplayName("an erasure survives a reopen without being reapplied")
        void erasureSurvivesReopen(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("keep", "sigkill alpha", "drop", "sigkill beta"), 1);
                store.erase(Set.of("drop"));
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.search(new String[] {"sigkill"}, ones(1), 5, 9))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("keep"));
                assertThat(reopened.outstandingErasures()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("compaction removes the erased text from disk, not just from queries")
        void compactionPhysicallyRemoves(@TempDir Path dir) throws Exception {
            // The distinction the whole feature rests on. A store that only tombstones looks
            // identical through every API and has not erased anything.
            try (LexicalStore store = LexicalStore.open(dir)) {
                Map<String, String> batch = docs(
                    "keep", "ordinary retained content here",
                    "drop", "supercalifragilistic secret payload");
                store.add(batch, 1);

                assertThat(anyFileContains(dir, "supercalifragilistic"))
                    .as("before erasure the term is in the index")
                    .isTrue();

                store.erase(Set.of("drop"));
                assertThat(anyFileContains(dir, "supercalifragilistic"))
                    .as("a tombstone hides it but has not removed it")
                    .isTrue();

                int removed = store.compactNow(batch::get, 2).documentsRemoved();

                assertThat(removed).isEqualTo(1);
                assertThat(anyFileContains(dir, "supercalifragilistic"))
                    .as("after compaction the term must be gone from every file")
                    .isFalse();
                assertThat(store.search(new String[] {"ordinary"}, ones(1), 5, 9))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("keep"));
                assertThat(store.outstandingErasures()).isZero();
            }
        }

        @Test
        @DisplayName("compaction merges the surviving segments into one")
        void compactionMerges(@TempDir Path dir) throws Exception {
            Map<String, String> all = new LinkedHashMap<>();
            try (LexicalStore store = LexicalStore.open(dir)) {
                for (int i = 0; i < 5; i++) {
                    Map<String, String> batch = docs("d" + i, "term" + i + " shared");
                    all.putAll(batch);
                    store.add(batch, i + 1);
                }
                assertThat(store.segments()).hasSize(5);
                store.erase(Set.of("d2"));

                store.compactNow(all::get, 10);

                assertThat(store.segments()).hasSize(1);
                assertThat(store.liveDocuments(10)).isEqualTo(4);
                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 10)).hasSize(4);
                assertThat(store.search(new String[] {"term2"}, ones(1), 10, 10)).isEmpty();
            }
        }

        @Test
        @DisplayName("erasing something absent is not an error")
        void erasingAbsentKeys(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
                assertThat(store.erase(Set.of("nonexistent"))).isZero();
                assertThat(store.search(new String[] {"alpha"}, ones(1), 5, 9)).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("republishing a key")
    class Supersession {

        @Test
        @DisplayName("a key added again is one document, not two")
        void republishingReplaces(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("chunk:1", "the OOM killer terminated the process"), 1);
                store.add(docs("chunk:1", "the OOM killer terminated the process"), 2);

                assertThat(store.search(new String[] {"oom"}, ones(1), 10, 9))
                    .as("the same chunk ingested twice must not occupy two result slots")
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("chunk:1"));
                assertThat(store.liveDocuments(9)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the replaced text stops matching")
        void oldTextStopsMatching(@TempDir Path dir) throws Exception {
            // The chunk's storage entry holds the new text, so a hit on the old words would be
            // reported against a chunk that does not contain them - a wrong answer rather than a
            // stale one.
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("chunk:1", "the TLS handshake failed"), 1);
                store.add(docs("chunk:1", "the certificate expired"), 2);

                assertThat(store.search(new String[] {"handshake"}, ones(1), 10, 9)).isEmpty();
                assertThat(store.search(new String[] {"certificate"}, ones(1), 10, 9))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("chunk:1"));
            }
        }

        @Test
        @DisplayName("only the republished key is retired")
        void neighboursAreUntouched(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha shared", "b", "beta shared", "c", "gamma shared"), 1);
                store.add(docs("b", "delta shared"), 2);

                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 9))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactlyInAnyOrder("a", "b", "c");
                assertThat(store.search(new String[] {"beta"}, ones(1), 10, 9)).isEmpty();
                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 9)).hasSize(1);
            }
        }

        @Test
        @DisplayName("a reader before the replacement's epoch keeps the old copy, never both")
        void oldCopyAnswersUntilTheReplacementIsVisible(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("chunk:1", "shared handshake"), 1);
                store.add(docs("chunk:1", "shared certificate"), 5);

                assertThat(store.search(new String[] {"handshake"}, ones(1), 10, 4))
                    .as("a reader still before the replacement answers from the old copy")
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("chunk:1"));
                assertThat(store.search(new String[] {"certificate"}, ones(1), 10, 4)).isEmpty();
                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 4)).hasSize(1);
                assertThat(store.liveDocuments(4)).isEqualTo(1);

                assertThat(store.search(new String[] {"handshake"}, ones(1), 10, 5)).isEmpty();
                assertThat(store.search(new String[] {"certificate"}, ones(1), 10, 5))
                    .singleElement().satisfies(h -> assertThat(h.key()).isEqualTo("chunk:1"));
                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 5)).hasSize(1);
                assertThat(store.liveDocuments(5)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a segment wholly replaced still answers readers before its replacement")
        void whollyReplacedSegmentStillAnswersEarlierReaders(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha", "b", "alpha beta"), 1);
                store.add(docs("a", "gamma", "b", "gamma delta"), 3);

                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 2))
                    .extracting(LexicalStore.Hit::key).containsExactlyInAnyOrder("a", "b");
                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 3)).isEmpty();
            }
        }

        @Test
        @DisplayName("an erasure hides the old copy from every reader at once")
        void erasureOverridesAPendingReplacement(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
                store.add(docs("a", "beta"), 5);

                store.erase(Set.of("a"));

                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 4)).isEmpty();
                assertThat(store.search(new String[] {"beta"}, ones(1), 10, 9)).isEmpty();
            }
        }

        @Test
        @DisplayName("a copy a reader may still need is not compacted away beneath it")
        void compactionWaitsForReadersBeforeTheReplacement(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha", "b", "unrelated"), 1);
                store.add(docs("a", "beta"), 5);
                Map<String, String> now = docs("a", "beta", "b", "unrelated");

                store.compactNow(now::get, 4);
                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 4))
                    .as("a reader at epoch 4 still has only the old copy to answer from")
                    .hasSize(1);

                store.compactNow(now::get, 5);
                assertThat(anyFileContains(dir, "alpha"))
                    .as("once no reader precedes the replacement the old copy leaves disk")
                    .isFalse();
                assertThat(store.search(new String[] {"beta"}, ones(1), 10, 9)).hasSize(1);
                assertThat(store.search(new String[] {"unrelated"}, ones(1), 10, 9)).hasSize(1);
            }
        }

        @Test
        @DisplayName("a replaced copy is counted for compaction but owed to nobody")
        void supersessionIsNotAnErasure(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
                store.add(docs("a", "beta"), 2);

                assertThat(store.compactionStats().tombstonedRecords())
                    .as("the replaced copy is dead weight the policy should see")
                    .isEqualTo(1);
                assertThat(store.outstandingErasures()).isEqualTo(1);
                assertThat(store.outstandingErasureRequests())
                    .as("nobody asked for it to be gone by a deadline")
                    .isEmpty();
                assertThat(store.compactionStats().oldestOutstanding()).isEmpty();
            }
        }

        @Test
        @DisplayName("compaction reclaims the replaced text from disk")
        void compactionRemovesTheOldCopy(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "reorganisation", "b", "unrelated"), 1);
                store.add(docs("a", "replacement"), 2);

                Map<String, String> now = docs("a", "replacement", "b", "unrelated");
                store.compactNow(now::get, 9);

                assertThat(store.segments()).hasSize(1);
                assertThat(store.liveDocuments(9)).isEqualTo(2);
                assertThat(anyFileContains(dir, "reorganisation"))
                    .as("the replaced text must leave disk, not merely stop matching")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a key erased and then added again comes back")
        void erasureThenReingest(@TempDir Path dir) throws Exception {
            // Erasure removes what was there; it is not a refusal to hear the name again.
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
                store.erase(Set.of("a"));
                store.add(docs("a", "alpha"), 2);

                assertThat(store.search(new String[] {"alpha"}, ones(1), 10, 9)).hasSize(1);
                assertThat(store.liveDocuments(9)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("supersession survives a restart")
        void survivesReopening(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
                store.add(docs("a", "beta"), 2);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.search(new String[] {"alpha"}, ones(1), 10, 9)).isEmpty();
                assertThat(reopened.search(new String[] {"beta"}, ones(1), 10, 9)).hasSize(1);
                assertThat(reopened.liveDocuments(9)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a staged batch replaces every earlier copy at once")
        void stagedBatchesSupersede(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha one"), 1);
                store.add(docs("b", "beta one"), 2);

                LexicalStore.Staged first = store.stage(docs("a", "alpha two"));
                LexicalStore.Staged second = store.stage(docs("b", "beta two"));
                assertThat(store.publish(List.of(first, second), 3)).isEqualTo(2);

                assertThat(store.search(new String[] {"one"}, ones(1), 10, 9)).isEmpty();
                assertThat(store.search(new String[] {"two"}, ones(1), 10, 9)).hasSize(2);
            }
        }

        @Test
        @DisplayName("a segment merged from two holding one name loses both copies at once")
        void mergedDuplicatesAreBothRetired(@TempDir Path dir) throws Exception {
            // Compaction can leave two documents under one name only if something published them
            // that way; erasing the name has to reach every copy, not the first one found.
            try (LexicalStore store = LexicalStore.open(dir)) {
                LexicalStore.Staged first = store.stage(docs("dup", "alpha shared"));
                LexicalStore.Staged second = store.stage(docs("dup", "beta shared"));
                store.publish(List.of(first, second), 1);
                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 9)).hasSize(2);

                assertThat(store.erase(Set.of("dup"))).isEqualTo(2);
                assertThat(store.search(new String[] {"shared"}, ones(1), 10, 9)).isEmpty();
            }
        }
    }

    /**
     * Puts a store back in the state a crash between an erasure's promise and its application
     * leaves: the promise on the log, and neither the tombstones nor the confirmation that they
     * were written.
     */
    private static void interruptErasure(Path dir) throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".tomb"))
                    .toList()) {
                Files.delete(file);
            }
        }
        Path log = dir.resolve("erasure.log");
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        Files.write(log, lines.stream().filter(line -> !line.startsWith("D\t")).toList(),
            StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("erasure across a restart")
    class ErasureDurability {

        @Test
        @DisplayName("a tombstone lost to an interrupted write is restored from the log")
        void tombstonesAreRestored(@TempDir Path dir) throws Exception {
            // The log is forced to disk before the tombstones are written, and the confirmation
            // after them. A crash between the two must not make an erased document readable
            // again.
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "confidential", "b", "routine"), 1);
                store.erase(Set.of("a"));
            }
            interruptErasure(dir);

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.search(new String[] {"confidential"}, ones(1), 10, 9))
                    .as("an erased document must not come back because a write was interrupted")
                    .isEmpty();
                assertThat(reopened.search(new String[] {"routine"}, ones(1), 10, 9)).hasSize(1);
                assertThat(reopened.outstandingErasures()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a compaction after the restore discharges the promise")
        void restoredTombstonesAreThenReclaimed(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "confidential", "b", "routine"), 1);
                store.erase(Set.of("a"));
            }
            interruptErasure(dir);

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                reopened.compactNow(docs("b", "routine")::get, 9);

                assertThat(reopened.outstandingErasures()).isZero();
                assertThat(reopened.outstandingErasureRequests()).isEmpty();
                assertThat(anyFileContains(dir, "confidential"))
                    .as("the promise is kept on disk, not merely re-hidden")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a key erased and then written again survives a restart")
        void reingestSurvivesRestart(@TempDir Path dir) throws Exception {
            // The promise stays on the log until compaction removes the erased copy. Replaying
            // it on open would tombstone the copy written since - and the compaction after that
            // would remove it from disk for good.
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "first draft"), 1);
                store.erase(Set.of("a"));
                store.add(docs("a", "second draft"), 2);
            }

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.search(new String[] {"second"}, ones(1), 10, 9))
                    .as("the copy written after the erasure is not the one it was about")
                    .hasSize(1);
                assertThat(reopened.search(new String[] {"first"}, ones(1), 10, 9)).isEmpty();
                assertThat(reopened.outstandingErasureRequests()).hasSize(1);
            }
            try (LexicalStore again = LexicalStore.open(dir)) {
                again.compactNow(docs("a", "second draft")::get, 9);
                assertThat(again.search(new String[] {"second"}, ones(1), 10, 9)).hasSize(1);
                assertThat(anyFileContains(dir, "first draft")).isFalse();
            }
        }

        @Test
        @DisplayName("a store owing nothing opens without touching its segments")
        void nothingOwedIsNoWork(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha"), 1);
            }
            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.outstandingErasures()).isZero();
                assertThat(reopened.search(new String[] {"alpha"}, ones(1), 10, 9)).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("the key lookup table")
    class KeyTable {

        @Test
        @DisplayName("a segment missing its table rebuilds it on open")
        void rebuildsWhenMissing(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha", "b", "beta"), 1);
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".keymap"))
                        .toList()) {
                    Files.delete(file);
                }
            }

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.erase(Set.of("a")))
                    .as("a store whose lookup table was lost must rebuild it, not stop erasing")
                    .isEqualTo(1);
                assertThat(reopened.search(new String[] {"alpha"}, ones(1), 10, 9)).isEmpty();
            }
        }

        @Test
        @DisplayName("a truncated table is replaced rather than trusted")
        void rebuildsWhenTruncated(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha", "b", "beta"), 1);
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".keymap"))
                        .toList()) {
                    Files.write(file, new byte[8]);
                }
            }

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.erase(Set.of("b"))).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("names that collide in a slot are still told apart")
        void collidingNamesAreDistinguished(@TempDir Path dir) throws Exception {
            // Two names cannot be assumed to land in different slots: the table is smaller than
            // the space of names, so probing has to compare the name itself before it marks
            // anything.
            Map<String, String> many = new LinkedHashMap<>();
            for (int i = 0; i < 500; i++) {
                many.put("chunk:" + i, "document number " + i + " shared");
            }
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(many, 1);

                assertThat(store.erase(Set.of("chunk:37", "chunk:412"))).isEqualTo(2);
                assertThat(store.search(new String[] {"shared"}, ones(1), 1000, 9)).hasSize(498);
                assertThat(store.search(new String[] {"37"}, ones(1), 10, 9)).isEmpty();
                assertThat(store.search(new String[] {"38"}, ones(1), 10, 9)).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("ranking around tombstones")
    class RankingAroundTombstones {

        /** One segment of documents all matching "oom", ranked by how often they repeat it. */
        private Map<String, String> ranked(int count) {
            Map<String, String> documents = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                documents.put("d" + i, ("oom " .repeat(count - i)).trim());
            }
            return documents;
        }

        @Test
        @DisplayName("a page of live results is returned however many better ones are tombstoned")
        void tombstonesDoNotTruncateThePage(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(ranked(30), 1);

                Set<String> erased = new java.util.LinkedHashSet<>();
                for (int i = 0; i < 20; i++) {
                    erased.add("d" + i);
                }
                assertThat(store.erase(erased)).isEqualTo(20);

                // The twenty best documents are gone. The next three are d20, d21 and d22, and
                // asking for three has to return three: a caller cannot tell an exhausted corpus
                // from a scoring shortcut that stopped looking.
                assertThat(store.search(new String[] {"oom"}, ones(1), 3, 99))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactly("d20", "d21", "d22");
            }
        }

        @Test
        @DisplayName("asking for more than survive returns all of them, not a truncated page")
        void everySurvivorIsReturned(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(ranked(30), 1);

                Set<String> erased = new java.util.LinkedHashSet<>();
                for (int i = 0; i < 28; i++) {
                    erased.add("d" + i);
                }
                assertThat(store.erase(erased)).isEqualTo(28);

                assertThat(store.search(new String[] {"oom"}, ones(1), 10, 99))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactly("d28", "d29");
            }
        }
    }

    @Nested
    @DisplayName("opening on damaged files")
    class Damaged {

        private List<String> segmentDirectories(Path dir) throws Exception {
            try (Stream<Path> listing = Files.list(dir)) {
                return listing.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
            }
        }

        @Test
        @DisplayName("a manifest whose write never landed does not take the segments with it")
        void unreadableManifestKeepsTheSegments(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha oom"), 1);
                store.add(docs("b", "beta oom"), 2);
            }
            List<String> before = segmentDirectories(dir);
            assertThat(before).hasSize(2);
            // A rename that reached the directory while its contents were still in the page
            // cache leaves exactly this.
            Files.write(dir.resolve("manifest"), new byte[0]);

            assertThatThrownBy(() -> LexicalStore.open(dir)).isInstanceOf(IOException.class);

            assertThat(segmentDirectories(dir))
                .as("a manifest that cannot be trusted must never authorise a deletion")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("a manifest with no count marker still opens and is not swept from")
        void legacyManifestOpensWithoutSweeping(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha oom"), 1);
            }
            // What a directory written by the build before the marker existed looks like: the
            // older header, and records with nothing after them.
            List<String> lines = Files.readAllLines(dir.resolve("manifest"));
            List<String> records = lines.subList(1, lines.size() - 1);
            Files.writeString(dir.resolve("manifest"), "excalibur-lexical-store v1\n"
                + String.join("\n", records) + "\n");
            Files.createDirectories(dir.resolve("seg-99"));

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.search(new String[] {"alpha"}, ones(1), 10, 9))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactly("a");
            }
            assertThat(Files.exists(dir.resolve("seg-99")))
                .as("an unverified manifest is not evidence that seg-99 is an orphan")
                .isTrue();
        }

        @Test
        @DisplayName("a segment that cannot be opened is reported rather than wedging the store")
        void tornSegmentDoesNotWedgeTheStore(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha oom"), 1);
                store.add(docs("b", "beta oom"), 2);
            }
            Files.write(dir.resolve("seg-0").resolve("post.off"), new byte[4]);

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.unreadableSegments()).containsOnlyKeys("seg-0");
                assertThat(reopened.search(new String[] {"oom"}, ones(1), 10, 9))
                    .as("the healthy segment still answers")
                    .extracting(LexicalStore.Hit::key)
                    .containsExactly("b");
            }
            assertThat(Files.exists(dir.resolve("seg-0")))
                .as("the damaged segment's bytes are kept for whoever has to recover them")
                .isTrue();
        }

        @Test
        @DisplayName("a repaired segment answers again at the next open")
        void repairedSegmentComesBack(@TempDir Path dir) throws Exception {
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a", "alpha oom"), 1);
            }
            Path rowIndex = dir.resolve("seg-0").resolve("post.off");
            byte[] intact = Files.readAllBytes(rowIndex);
            Files.write(rowIndex, new byte[4]);
            try (LexicalStore broken = LexicalStore.open(dir)) {
                assertThat(broken.unreadableSegments()).containsOnlyKeys("seg-0");
            }

            Files.write(rowIndex, intact);

            try (LexicalStore repaired = LexicalStore.open(dir)) {
                assertThat(repaired.unreadableSegments()).isEmpty();
                assertThat(repaired.search(new String[] {"alpha"}, ones(1), 10, 9))
                    .extracting(LexicalStore.Hit::key)
                    .containsExactly("a");
            }
        }
    }
}
