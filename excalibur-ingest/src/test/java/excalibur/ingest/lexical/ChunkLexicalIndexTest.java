package excalibur.ingest.lexical;

import io.excalibur.retrieval.expand.QueryExpander;
import io.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        @DisplayName("Lucene owes no erasure and never reports a compaction as due")
        void owesNothing(@TempDir Path dir) throws Exception {
            // It reclaims deleted documents on its own merge schedule, so there is no obligation
            // this node holds and no moment at which it would decide to act. Reporting a deadline
            // it does not enforce would be the more misleading answer.
            try (ChunkLexicalIndex index = open(dir)) {
                index.add("kb:a.txt:chunk:0", "alpha");
                index.refresh();
                index.erase(Set.of("kb:a.txt:chunk:0"));
                index.refresh();

                ChunkIndexStats stats = index.stats();
                assertThat(stats.erasureRequests()).isZero();
                assertThat(stats.oldestErasureRequest()).isEmpty();
                assertThat(stats.compactionDue()).isFalse();
                assertThat(index.compactIfDue(key -> null).performed()).isFalse();
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
