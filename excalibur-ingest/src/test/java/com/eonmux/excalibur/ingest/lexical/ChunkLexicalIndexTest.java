package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lucene engine, held to the same contract as the native one.
 *
 * <p>Both are reachable through {@link ChunkIndex}, and a caller that switched between them and
 * got different answers would have no way of knowing which was right. The properties tested here
 * are the ones the interface promises rather than the ones Lucene happens to have: that a chunk
 * identifier names one chunk however many times it is written, and that the index will say what it
 * holds.
 */
@DisplayName("Lucene chunk index")
class ChunkLexicalIndexTest {

    private static ChunkLexicalIndex open(Path dir) throws Exception {
        return ChunkLexicalIndex.open(dir, 4.0);
    }

    /**
     * An index whose only reason to compact is a deadline.
     *
     * <p>A ratio of 1.0 and a segment ceiling no small index reaches leave the age of the oldest
     * outstanding request as the single condition that can fire, which is the one a lone erased
     * chunk in a large corpus depends on.
     */
    private static ChunkLexicalIndex onlyOnDeadline(Path dir, Duration bound, Clock clock)
            throws Exception {
        return ChunkLexicalIndex.open(dir, 4.0, Bm25Parameters.DEFAULT,
            CompactionPolicy.standard(1.0, bound, Integer.MAX_VALUE), clock);
    }

    /**
     * Enough chunks that Lucene's own merge policy will leave a single deletion alone.
     *
     * <p>{@code TieredMergePolicy} rewrites a segment once a fifth of it is deleted, so a
     * two-document index reclaims an erasure by itself and proves nothing. One erased chunk in a
     * corpus of two hundred is the case the deadline exists for: below every volume threshold
     * either engine has, and still owed.
     */
    private static void fill(ChunkLexicalIndex index, int chunks) throws Exception {
        for (int i = 0; i < chunks; i++) {
            index.add("kb:filler.txt:chunk:" + i, "routine capacity planning note " + i);
        }
    }

    private static boolean anyFileContains(Path dir, String needle) throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int hits(ChunkLexicalIndex index, String term) {
        return index.ranker(QueryExpander.identity())
            .rank(RetrievalQuery.ofText(term), 100).documents().size();
    }

    @Nested
    @DisplayName("Reingestion")
    class Reingestion {

        @Test
        @DisplayName("a chunk indexed again is one chunk, not two")
        void reindexingReplaces(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();

                assertThat(hits(index, "certificate")).isEqualTo(1);
                assertThat(index.stats().liveDocuments()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a rewritten chunk stops matching the words it used to hold")
        void staleTextStopsMatching(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "the TLS handshake failed");
                index.refresh();
                index.add("kb:a.txt:chunk:0", "the certificate expired");
                index.refresh();

                assertThat(hits(index, "handshake")).isZero();
                assertThat(hits(index, "expired")).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an erased chunk stops answering")
        void erasureHides(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "confidential postmortem");
                index.add("kb:a.txt:chunk:1", "routine planning");
                index.refresh();

                assertThat(index.erase(Set.of("kb:a.txt:chunk:0"))).isEqualTo(1);
                index.refresh();

                assertThat(hits(index, "postmortem")).isZero();
                assertThat(hits(index, "planning")).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Identifier collisions")
    class Collisions {

        @Test
        @DisplayName("a chunk written again is not reported as a collision")
        void reindexingIsNotACollision(@TempDir Path dir) throws Exception {
            // The index holds one document where two were written, which is what a collision
            // looks like from a counter - and what every reindex looks like too. Reporting it
            // as data lost to a hash collision is a false alarm on a routine operation.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();

                assertThat(index.collisionCount()).isZero();
            }
        }

        @Test
        @DisplayName("an erased chunk is not reported as a collision")
        void erasureIsNotACollision(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "confidential postmortem");
                index.add("kb:a.txt:chunk:1", "routine planning");
                index.refresh();

                index.erase(Set.of("kb:a.txt:chunk:0"));
                index.refresh();

                assertThat(index.collisionCount()).isZero();
            }
        }

        @Test
        @DisplayName("reopening an index and rewriting its chunks is not a collision")
        void reopeningIsNotACollision(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.refresh();
            }
            try (ChunkLexicalIndex reopened = open(dir)) {
                reopened.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                reopened.refresh();

                assertThat(reopened.collisionCount()).isZero();
            }
        }

        @Test
        @DisplayName("two chunk names arriving under one id are still reported")
        void twoNamesUnderOneIdAreReported(@TempDir Path dir) throws Exception {
            // What the check is for: a fresh index, every name minted once, and fewer documents
            // stored than were written. Whether the cause is a 64-bit collision or ingestion
            // minting a name twice, a chunk is missing from the corpus.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "the certificate chain could not be verified");
                index.add("kb:a.txt:chunk:0", "an entirely different chunk of text");
                index.refresh();

                assertThat(index.collisionCount()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Reporting")
    class Reporting {

        @Test
        @DisplayName("an index reports what it holds")
        void reportsItsContents(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.add("kb:a.txt:chunk:1", "beta");
                index.refresh();

                ChunkIndexStats stats = index.stats();
                assertThat(stats.engine()).isEqualTo("lucene");
                assertThat(stats.indexed()).isEqualTo(2);
                assertThat(stats.liveDocuments()).isEqualTo(2);
                assertThat(stats.segments()).isPositive();
            }
        }

        @Test
        @DisplayName("an index owing nothing says so")
        void owesNothing(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.refresh();

                ChunkIndexStats stats = index.stats();
                assertThat(stats.erasureRequests()).isZero();
                assertThat(stats.oldestErasureRequest()).isEmpty();
                assertThat(stats.compactionDue()).isFalse();
                assertThat(index.compactIfDue(key -> null).performed()).isFalse();
            }
        }

        @Test
        @DisplayName("an accepted erasure is outstanding until a compaction discharges it")
        void erasureIsOutstandingUntilCompacted(@TempDir Path dir) throws Exception {
            // The sweep that enforces the deadline skips an index reporting nothing outstanding,
            // so an obligation Lucene knew about but did not report would not merely be
            // misreported - it would never be acted on.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "zzconfidentialmarker");
                fill(index, 200);
                index.refresh();
                index.erase(Set.of("kb:a.txt:chunk:0"));
                index.refresh();

                assertThat(index.stats().erasureRequests()).isEqualTo(1);
                assertThat(index.stats().oldestErasureRequest()).isPresent();
                assertThat(index.compactIfDue(key -> null).performed())
                    .as("one chunk in two hundred is nowhere near the tombstone ratio")
                    .isFalse();
                assertThat(index.stats().erasureRequests())
                    .as("a declined compaction discharges nothing")
                    .isEqualTo(1);

                assertThat(index.compactNow(key -> null).performed()).isTrue();
                assertThat(index.stats().erasureRequests()).isZero();
                assertThat(index.stats().oldestErasureRequest()).isEmpty();
            }
        }

        @Test
        @DisplayName("a lone erasure waits for its deadline and is then reclaimed without being "
            + "asked twice")
        void aLoneErasureIsBoundedByItsDeadline(@TempDir Path dir) throws Exception {
            Instant accepted = Instant.parse("2026-01-01T00:00:00Z");
            Clock clock = Clock.fixed(accepted, ZoneOffset.UTC);
            Duration bound = Duration.ofHours(1);
            try (ChunkLexicalIndex index = onlyOnDeadline(dir, bound, clock)) {
                index.add("kb:a.txt:chunk:0", "zzconfidentialmarker");
                fill(index, 200);
                index.refresh();
                index.erase(Set.of("kb:a.txt:chunk:0"));
                index.refresh();

                assertThat(index.stats().compactionDue())
                    .as("a fresh request is not yet overdue")
                    .isFalse();
                assertThat(index.compactIfDue(key -> null).performed()).isFalse();
                assertThat(anyFileContains(dir, "zzconfidentialmarker"))
                    .as("hidden from queries, still on disk: that is what is owed")
                    .isTrue();
            }

            Clock later = Clock.fixed(accepted.plus(bound).plusSeconds(1), ZoneOffset.UTC);
            try (ChunkLexicalIndex index = onlyOnDeadline(dir, bound, later)) {
                assertThat(index.stats().erasureRequests())
                    .as("the promise was forced to disk, so a restart still owes it")
                    .isEqualTo(1);
                assertThat(index.stats().compactionDue()).isTrue();

                assertThat(index.compactIfDue(key -> null).performed()).isTrue();
                assertThat(anyFileContains(dir, "zzconfidentialmarker"))
                    .as("the bound is what makes an accepted erasure a promise")
                    .isFalse();
                assertThat(hits(index, "note"))
                    .as("the surviving chunks are rewritten, not lost with it")
                    .isEqualTo(100);
                assertThat(index.stats().erasureRequests()).isZero();
            }
        }

        @Test
        @DisplayName("an erasure the index never matched is still discharged")
        void anUnmatchedErasureDoesNotAgeForever(@TempDir Path dir) throws Exception {
            // Left on the log it would pass the latency bound on every sweep from then on and
            // demand a rewrite that could never settle it.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.refresh();
                index.erase(Set.of("kb:never-indexed.txt:chunk:0"));

                assertThat(index.stats().erasureRequests()).isEqualTo(1);
                index.compactNow(key -> null);
                assertThat(index.stats().erasureRequests()).isZero();
            }
        }

        @Test
        @DisplayName("a forced compaction reclaims the erased text from disk")
        void forcedCompactionReclaims(@TempDir Path dir) throws Exception {
            // Lucene keeps a copy of each chunk's text, so unlike the native index there is
            // content to reclaim here and not only postings. A caller under a deadline needs this
            // to have happened, not to be scheduled.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "zzconfidentialmarker");
                index.add("kb:a.txt:chunk:1", "routine capacity planning");
                index.refresh();
                index.erase(Set.of("kb:a.txt:chunk:0"));
                index.refresh();

                var result = index.compactNow(key -> null);

                assertThat(result.performed())
                    .as("the caller forced this rather than asking whether it was due")
                    .isTrue();
                assertThat(index.stats().erasedDocuments()).isZero();
                assertThat(hits(index, "planning"))
                    .as("the surviving chunk is rewritten, not lost with it")
                    .isEqualTo(1);
                assertThat(anyFileContains(dir, "zzconfidentialmarker"))
                    .as("the erased text has to leave disk, not merely stop matching")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a forced compaction with nothing to reclaim says so rather than pretending")
        void forcedCompactionWithNothingToDo(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.refresh();

                var result = index.compactNow(key -> null);

                assertThat(result.performed()).isTrue();
                assertThat(result.documentsRemoved()).isZero();
                assertThat(result.reason()).contains("no deletions left to reclaim");
            }
        }

        @Test
        @DisplayName("an index that has never been refreshed still answers")
        void reportsBeforeTheFirstRefresh(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");

                ChunkIndexStats stats = index.stats();
                assertThat(stats.indexed())
                    .as("what was handed to the index, whether or not it is searchable yet")
                    .isEqualTo(1);
                assertThat(stats.liveDocuments()).isZero();
                assertThat(stats.segments()).isZero();
            }
        }
    }
}
