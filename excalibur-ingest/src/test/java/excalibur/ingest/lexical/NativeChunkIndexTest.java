package excalibur.ingest.lexical;

import io.excalibur.graph.erasure.CompactionPolicy;
import io.excalibur.retrieval.expand.QueryExpander;
import io.excalibur.retrieval.expand.SearchFormulation;
import io.excalibur.retrieval.lexical.native_.CompactionPlanner;
import io.excalibur.retrieval.lexical.native_.CompactionResult;
import io.excalibur.retrieval.rank.RankedList;
import io.excalibur.retrieval.rank.Ranker;
import io.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chunk index the ingestion path writes into.
 *
 * <p>Two properties carry the weight here. The first is that a run's chunks become searchable at
 * one instant however many times the buffer had to spill on the way - a spill lands in the middle
 * of a document, and half a document answering a query is wrong rather than merely incomplete. The
 * second is that a chunk taken out of storage is taken out of the index with it, since a hit
 * naming a chunk that storage no longer holds is a result a caller cannot use and cannot tell
 * apart from one it can.
 */
@DisplayName("Native chunk index")
class NativeChunkIndexTest {

    /** A planner whose policy always fires, so a compaction test does not need to age a clock. */
    private static CompactionPlanner eager() {
        return new CompactionPlanner((stats, now) -> true, 0.2, Duration.ofHours(1), 16, 8);
    }

    private static CompactionPlanner standard() {
        return CompactionPlanner.standard(0.2, Duration.ofHours(1), 16);
    }

    private static QueryExpander expanding(String... alternatives) {
        return text -> {
            var formulations = new java.util.ArrayList<SearchFormulation>();
            formulations.add(SearchFormulation.original(text));
            for (String alternative : alternatives) {
                formulations.add(new SearchFormulation(alternative, 0.8));
            }
            return formulations;
        };
    }

    private static List<String> namesOf(NativeChunkIndex index, RankedList ranked) {
        return ranked.documents().stream().map(d -> index.chunkIdOf(d.documentId())).toList();
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
    @DisplayName("Publication")
    class Publication {

        @Test
        @DisplayName("chunks are not searchable until the run is refreshed")
        void addingIsNotPublishing(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(5);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                Ranker ranker = index.ranker(QueryExpander.identity());

                assertThat(ranker.rank(RetrievalQuery.ofText("certificate"), 5).documents())
                    .as("written, but a query has not been told about it")
                    .isEmpty();

                index.refresh();
                epoch.incrementAndGet();

                assertThat(ranker.rank(RetrievalQuery.ofText("certificate"), 5).documents())
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("a run's chunks appear together even when the buffer spills mid-run")
        void aSpilledRunStillPublishesAtOneEpoch(@TempDir Path dir) throws Exception {
            // The buffer holds two chunks, so five chunks spill three times. Every one of them
            // must still become visible at the same epoch: a spill lands mid-document, and a
            // query seeing half a document is a wrong answer rather than a partial one.
            AtomicLong epoch = new AtomicLong(10);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard(), 2)) {
                for (int i = 0; i < 5; i++) {
                    index.add("kb:doc.txt:chunk:" + i, "shared body term" + i);
                }
                Ranker ranker = index.ranker(QueryExpander.identity());

                assertThat(ranker.rank(RetrievalQuery.ofText("shared"), 10).documents())
                    .as("nothing published while the buffer was spilling")
                    .isEmpty();

                index.refresh();

                assertThat(index.liveChunks())
                    .as("still invisible at the epoch the run started on")
                    .isZero();

                epoch.incrementAndGet();

                assertThat(index.liveChunks()).isEqualTo(5);
                assertThat(ranker.rank(RetrievalQuery.ofText("shared"), 10).documents())
                    .as("all five arrive at once, from however many segments")
                    .hasSize(5);
            }
        }

        @Test
        @DisplayName("a hit resolves to the chunk name a caller can look up")
        void hitsResolveToNames(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:oom.txt:chunk:0",
                    "the OOM killer terminated the process under memory pressure");
                index.add("kb:tls.txt:chunk:0",
                    "the certificate chain could not be verified during the handshake");
                index.refresh();
                epoch.incrementAndGet();

                Ranker ranker = index.ranker(QueryExpander.identity());
                assertThat(namesOf(index, ranker.rank(RetrievalQuery.ofText("memory pressure"), 5)))
                    .first().asString().contains("oom.txt");
                assertThat(namesOf(index,
                    ranker.rank(RetrievalQuery.ofText("certificate handshake"), 5)))
                    .first().asString().contains("tls.txt");
                assertThat(index.collisionCount()).isZero();
                assertThat(index.indexedCount()).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("Erasure")
    class Erasure {

        @Test
        @DisplayName("a chunk erased while buffered never reaches disk")
        void erasingABufferedChunk(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "confidentialpayload and other words");
                index.add("kb:b.txt:chunk:0", "ordinary retained content");

                assertThat(index.erase(Set.of("kb:a.txt:chunk:0"))).isEqualTo(1);

                index.refresh();
                epoch.incrementAndGet();

                assertThat(index.liveChunks()).isEqualTo(1);
                assertThat(anyFileContains(dir, "confidentialpayload"))
                    .as("it was never written, so there is nothing to compact away")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a chunk erased after it spilled is still erased when the run publishes")
        void erasingAStagedChunk(@TempDir Path dir) throws Exception {
            // Rollback happens while a document is being written, which may be after the buffer
            // spilled it into a segment that is not in the manifest yet. An erase that quietly
            // missed it would publish the chunk a moment later.
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard(), 2)) {
                index.add("kb:a.txt:chunk:0", "confidentialpayload and other words");
                index.add("kb:a.txt:chunk:1", "more of the same document");
                index.add("kb:b.txt:chunk:0", "ordinary retained content");

                index.erase(Set.of("kb:a.txt:chunk:0", "kb:a.txt:chunk:1"));
                index.refresh();
                epoch.incrementAndGet();

                Ranker ranker = index.ranker(QueryExpander.identity());
                assertThat(ranker.rank(RetrievalQuery.ofText("confidentialpayload"), 5).documents())
                    .as("a rolled-back chunk must not become searchable when the run publishes")
                    .isEmpty();
                assertThat(index.liveChunks()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("compaction takes the erased text off disk, not just out of queries")
        void compactionPhysicallyRemoves(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            storage.put("kb:a.txt:chunk:0", "confidentialpayload and other words");
            storage.put("kb:b.txt:chunk:0", "ordinary retained content");
            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                for (Map.Entry<String, String> chunk : storage.entrySet()) {
                    index.add(chunk.getKey(), chunk.getValue());
                }
                index.refresh();
                epoch.incrementAndGet();

                index.erase(Set.of("kb:a.txt:chunk:0"));
                assertThat(anyFileContains(dir, "confidentialpayload"))
                    .as("a tombstone hides it but has not removed it")
                    .isTrue();

                CompactionResult result = index.compactIfDue(storage::get);

                assertThat(result.performed()).isTrue();
                assertThat(result.documentsRemoved()).isEqualTo(1);
                assertThat(anyFileContains(dir, "confidentialpayload")).isFalse();
                assertThat(index.liveChunks()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a rewrite does not carry forward a chunk storage has lost")
        void compactionDropsChunksStorageLost(@TempDir Path dir) throws Exception {
            // The index cannot tell on its own that a name it holds has nothing behind it any
            // more - finding out would mean asking storage about every chunk it has. So the drop
            // is opportunistic: when a segment is being rewritten for some other reason, what is
            // gone is not written out again. Rewriting it as an empty document instead would
            // leave an entry present in every count and findable by nothing.
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            storage.put("kb:erased.txt:chunk:0", "confidentialpayload to be erased");
            storage.put("kb:gone.txt:chunk:0", "vanishedpayload from a rolled back write");
            storage.put("kb:kept.txt:chunk:0", "ordinary retained content");
            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                for (Map.Entry<String, String> chunk : storage.entrySet()) {
                    index.add(chunk.getKey(), chunk.getValue());
                }
                index.refresh();
                epoch.incrementAndGet();

                index.erase(Set.of("kb:erased.txt:chunk:0"));
                storage.remove("kb:gone.txt:chunk:0");

                CompactionResult result = index.compactIfDue(storage::get);

                assertThat(result.documentsRemoved())
                    .as("the erased chunk and the one storage lost")
                    .isEqualTo(2);
                assertThat(index.liveChunks()).isEqualTo(1);
                assertThat(anyFileContains(dir, "vanishedpayload")).isFalse();
                assertThat(anyFileContains(dir, "confidentialpayload")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("queries are answered while a compaction is rewriting")
        void queriesRunDuringCompaction(@TempDir Path dir) throws Exception {
            // A compaction can rewrite the whole corpus when an erasure deadline demands it. If
            // it held the index while it did that, every query would wait for it - which turns
            // keeping a promise about one chunk into an outage for everyone.
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            for (int i = 0; i < 20; i++) {
                storage.put("kb:d" + i + ".txt:chunk:0", "shared body term" + i);
            }
            java.util.concurrent.CountDownLatch building =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                for (Map.Entry<String, String> chunk : storage.entrySet()) {
                    index.add(chunk.getKey(), chunk.getValue());
                }
                index.refresh();
                epoch.incrementAndGet();
                index.erase(Set.of("kb:d3.txt:chunk:0"));

                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
                Thread compactor = new Thread(() -> {
                    try {
                        index.compactIfDue(key -> {
                            building.countDown();
                            try {
                                release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return storage.get(key);
                        });
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                }, "compactor");
                compactor.start();

                assertThat(building.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the rewrite started")
                    .isTrue();

                // On another thread with a deadline, so that a query which merely waits for the
                // rewrite to finish fails here instead of passing slowly. The latch is released
                // in the finally: a failure that left the rewrite parked would hang the suite
                // rather than report itself.
                java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
                RankedList ranked;
                try {
                    java.util.concurrent.Future<RankedList> query = pool.submit(() ->
                        index.ranker(QueryExpander.identity())
                            .rank(RetrievalQuery.ofText("shared"), 5));
                    ranked = query.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                    release.countDown();
                }
                assertThat(ranked.documents())
                    .as("answered from the pre-compaction segments, while the rewrite stalls")
                    .isNotEmpty();

                compactor.join(30_000);
                assertThat(compactor.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
                assertThat(index.liveChunks()).isEqualTo(19);
            }
        }

        @Test
        @DisplayName("a second compaction stands aside rather than rewriting the same segments")
        void oneCompactionAtATime(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                storage.put("kb:d" + i + ".txt:chunk:0", "shared body term" + i);
            }
            java.util.concurrent.CountDownLatch building =
                new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                for (Map.Entry<String, String> chunk : storage.entrySet()) {
                    index.add(chunk.getKey(), chunk.getValue());
                }
                index.refresh();
                epoch.incrementAndGet();
                index.erase(Set.of("kb:d3.txt:chunk:0"));

                Thread compactor = new Thread(() -> {
                    try {
                        index.compactIfDue(key -> {
                            building.countDown();
                            try {
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return storage.get(key);
                        });
                    } catch (java.io.IOException ignored) {
                        // Asserted through the store's state below.
                    }
                }, "compactor");
                compactor.start();
                assertThat(building.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                CompactionResult second = index.compactIfDue(storage::get);

                assertThat(second.performed()).isFalse();
                assertThat(second.reason()).contains("already running");

                release.countDown();
                compactor.join(30_000);
                assertThat(index.liveChunks()).isEqualTo(9);
            }
        }
    }

    @Nested
    @DisplayName("Ranking")
    class Ranking {

        @Test
        @DisplayName("an expansion reaches a document the user's own words do not")
        void expansionReachesFurther(@TempDir Path dir) throws Exception {
            // The whole argument for permuting the query rather than the corpus: the vocabulary
            // gap is closed by a few generated tokens, not by embedding every document.
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:oom.txt:chunk:0",
                    "the kernel terminated the process under memory pressure");
                index.refresh();
                epoch.incrementAndGet();

                assertThat(index.ranker(QueryExpander.identity())
                    .rank(RetrievalQuery.ofText("oomkiller"), 5).documents())
                    .as("the corpus does not contain the word asked for")
                    .isEmpty();

                assertThat(index.ranker(expanding("kernel terminated process"))
                    .rank(RetrievalQuery.ofText("oomkiller"), 5).documents())
                    .as("an expansion in the corpus's own vocabulary finds it")
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("a formulation of words the corpus lacks changes nothing")
        void unknownFormulationsAreDropped(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "memory pressure on the host");
                index.add("kb:b.txt:chunk:0", "certificate chain verification");
                index.refresh();
                epoch.incrementAndGet();

                RankedList plain = index.ranker(QueryExpander.identity())
                    .rank(RetrievalQuery.ofText("memory"), 5);
                RankedList padded = index.ranker(expanding("zzabsent zzmissing"))
                    .rank(RetrievalQuery.ofText("memory"), 5);

                assertThat(namesOf(index, padded))
                    .as("vocabulary the corpus has never seen cannot change a ranking")
                    .isEqualTo(namesOf(index, plain));
            }
        }

        @Test
        @DisplayName("an empty expansion and a zero limit are answers, not failures")
        void degenerateQueries(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "memory pressure on the host");
                index.refresh();
                epoch.incrementAndGet();

                Ranker none = index.ranker(text -> List.of());
                assertThat(none.rank(RetrievalQuery.ofText("memory"), 5).documents()).isEmpty();
                assertThat(index.ranker(QueryExpander.identity())
                    .rank(RetrievalQuery.ofText("memory"), 0).documents()).isEmpty();
                assertThat(index.ranker(QueryExpander.identity()).name()).isEqualTo("native-bm25");
                assertThat(index.engine()).isEqualTo("native");
            }
        }
    }

    @Nested
    @DisplayName("Reingestion")
    class Reingestion {

        @Test
        @DisplayName("a chunk ingested again is one chunk, not two")
        void reingestingReplaces(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();
                epoch.incrementAndGet();

                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();
                epoch.incrementAndGet();

                RankedList hits = index.ranker(QueryExpander.identity())
                    .rank(RetrievalQuery.ofText("certificate"), 10);
                assertThat(hits.documents())
                    .as("a re-crawled chunk must not take two of the caller's result slots")
                    .hasSize(1);
                assertThat(index.liveChunks()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a rewritten chunk stops matching the words it used to hold")
        void staleTextStopsMatching(@TempDir Path dir) throws Exception {
            // Storage holds the new text under this identifier, so a hit on the old words would
            // be reported against a chunk that does not contain them.
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                index.add("kb:a.txt:chunk:0", "the TLS handshake failed");
                index.refresh();
                epoch.incrementAndGet();

                index.add("kb:a.txt:chunk:0", "the certificate expired");
                index.refresh();
                epoch.incrementAndGet();

                Ranker ranker = index.ranker(QueryExpander.identity());
                assertThat(ranker.rank(RetrievalQuery.ofText("handshake"), 10).documents())
                    .isEmpty();
                assertThat(ranker.rank(RetrievalQuery.ofText("expired"), 10).documents())
                    .hasSize(1);
            }
        }

        @Test
        @DisplayName("compaction reclaims the replaced text from disk")
        void replacedTextIsReclaimed(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                index.add("kb:a.txt:chunk:0", "reorganisation of the buffer pool");
                index.refresh();
                epoch.incrementAndGet();

                index.add("kb:a.txt:chunk:0", "replacement text");
                storage.put("kb:a.txt:chunk:0", "replacement text");
                index.refresh();
                epoch.incrementAndGet();

                CompactionResult result = index.compactIfDue(storage::get);
                assertThat(result.performed()).isTrue();
                assertThat(anyFileContains(dir, "reorganisation"))
                    .as("the version a chunk used to hold has to leave disk with it")
                    .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("Reporting")
    class Reporting {

        @Test
        @DisplayName("an empty index reports itself as owing nothing")
        void emptyIndexOwesNothing(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index =
                     NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
                ChunkIndexStats stats = index.stats();

                assertThat(stats.engine()).isEqualTo("native");
                assertThat(stats.indexed()).isZero();
                assertThat(stats.segments()).isZero();
                assertThat(stats.liveDocuments()).isZero();
                assertThat(stats.erasedDocuments()).isZero();
                assertThat(stats.erasureRequests()).isZero();
                assertThat(stats.oldestErasureRequest()).isEmpty();
                assertThat(stats.compactionDue()).isFalse();
                assertThat(stats.erasedRatio()).isZero();
            }
        }

        @Test
        @DisplayName("an accepted erasure is reported as outstanding until it is reclaimed")
        void erasureIsReportedUntilReclaimed(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            Map<String, String> storage = new LinkedHashMap<>();
            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                index.add("kb:a.txt:chunk:0", "alpha shared");
                index.add("kb:a.txt:chunk:1", "beta shared");
                storage.put("kb:a.txt:chunk:1", "beta shared");
                index.refresh();
                epoch.incrementAndGet();
                index.erase(Set.of("kb:a.txt:chunk:0"));

                ChunkIndexStats owed = index.stats();
                assertThat(owed.indexed()).isEqualTo(2);
                assertThat(owed.liveDocuments()).isEqualTo(1);
                assertThat(owed.erasedDocuments()).isEqualTo(1);
                assertThat(owed.erasureRequests())
                    .as("the request is a promise, and stays visible until it is kept")
                    .isEqualTo(1);
                assertThat(owed.oldestErasureRequest()).isPresent();
                assertThat(owed.erasedRatio()).isEqualTo(0.5);

                index.compactIfDue(storage::get);

                ChunkIndexStats settled = index.stats();
                assertThat(settled.erasedDocuments()).isZero();
                assertThat(settled.erasureRequests()).isZero();
                assertThat(settled.oldestErasureRequest()).isEmpty();
                assertThat(settled.liveDocuments()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a due compaction is reported before it is run")
        void dueCompactionIsVisible(@TempDir Path dir) throws Exception {
            AtomicLong epoch = new AtomicLong(1);
            try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 0, eager())) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.refresh();
                epoch.incrementAndGet();
                index.erase(Set.of("kb:a.txt:chunk:0"));

                assertThat(index.stats().compactionDue())
                    .as("an operator should be able to see the work before it happens")
                    .isTrue();
            }
        }
    }
}
