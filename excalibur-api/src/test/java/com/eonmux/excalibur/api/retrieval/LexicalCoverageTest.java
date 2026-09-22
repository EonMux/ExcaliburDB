package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.query.IndexCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the lexical path may claim about how much of the corpus it searched.
 *
 * <p>The number a client acts on is coverage: an empty answer at 1.0 means the corpus does not
 * hold what was asked for, and the same empty answer at 0.3 means the node did not look. Reporting
 * a constant would make the two indistinguishable, which is worse than reporting nothing.</p>
 */
@DisplayName("Lexical coverage")
class LexicalCoverageTest {

    private static ChunkIndexStats stats(int segments, long live, long erased) {
        return new ChunkIndexStats("native", live, segments, live, erased, 0,
            Optional.empty(), false);
    }

    @Nested
    @DisplayName("A published segment")
    class Published {

        @Test
        @DisplayName("is active, because the store publishes by rename and has no warm-up")
        void everyPublishedSegmentIsSearchable() {
            IndexCoverage coverage = LexicalCoverage.of(stats(4, 100, 0));

            assertThat(coverage.totalSegments()).isEqualTo(4);
            assertThat(coverage.activeSegments()).isEqualTo(4);
            assertThat(coverage.indexingSegments()).isZero();
            assertThat(coverage.warmingSegments()).isZero();
            assertThat(coverage.compactingSegments()).isZero();
        }

        @Test
        @DisplayName("is searched, because BM25 visits every segment the manifest names")
        void thereIsNoSegmentSelection() {
            IndexCoverage coverage = LexicalCoverage.of(stats(4, 100, 0));

            assertThat(coverage.segmentsSearched()).isEqualTo(4);
            assertThat(coverage.segmentsExcluded()).isZero();
            assertThat(coverage.segmentsWithErrors()).isZero();
            assertThat(coverage.segmentsTimedOut()).isZero();
            assertThat(coverage.tailScanEntries()).isZero();
        }
    }

    @Nested
    @DisplayName("Corpus coverage")
    class Corpus {

        @Test
        @DisplayName("is whole when every segment was searched")
        void searchingEverythingIsFullCoverage() {
            assertThat(LexicalCoverage.of(stats(4, 100, 0)).estimatedCorpusCoverage())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("is zero on a node whose index holds nothing")
        void anEmptyIndexCoversNothing() {
            // Not 1.0. A query answered from an empty index found nothing because there was
            // nothing, and a client told it covered the whole corpus would read that as proof
            // the corpus does not contain the term.
            assertThat(LexicalCoverage.of(stats(0, 0, 0)).estimatedCorpusCoverage()).isZero();
        }
    }

    @Nested
    @DisplayName("Degradation")
    class Degraded {

        @Test
        @DisplayName("is always reported, because the node is answering from one signal")
        void lexicalOnlyIsAlwaysDegraded() {
            assertThat(LexicalCoverage.of(stats(4, 100, 0)).degraded()).isTrue();
        }
    }

    @Nested
    @DisplayName("Erased chunks")
    class Erased {

        @Test
        @DisplayName("are not counted as corpus a query failed to reach")
        void hiddenChunksDoNotReduceCoverage() {
            // An erased chunk is one a caller asked the node to forget. Counting it as missed
            // corpus would report a node discharging its erasure obligations as degraded.
            assertThat(LexicalCoverage.of(stats(2, 50, 50)).estimatedCorpusCoverage())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("do not make a segment look tombstoned")
        void erasureIsNotSegmentTombstoning() {
            assertThat(LexicalCoverage.of(stats(2, 50, 50)).tombstonedSegments()).isZero();
        }
    }

    @Nested
    @DisplayName("Stats that are absent")
    class Absent {

        @Test
        @DisplayName("report a query that covered nothing rather than a query that covered all")
        void nullStatsClaimNothing() {
            IndexCoverage coverage = LexicalCoverage.of(null);

            assertThat(coverage.estimatedCorpusCoverage()).isZero();
            assertThat(coverage.degraded()).isTrue();
            assertThat(coverage.totalSegments()).isZero();
        }
    }

    @Nested
    @DisplayName("An erasure obligation")
    class Obligation {

        @Test
        @DisplayName("does not change what the query covered")
        void outstandingErasureIsNotACoverageGap() {
            ChunkIndexStats owing = new ChunkIndexStats("native", 100, 2, 100, 0, 3,
                Optional.of(Instant.EPOCH), true);

            assertThat(LexicalCoverage.of(owing).estimatedCorpusCoverage()).isEqualTo(1.0);
        }
    }
}
