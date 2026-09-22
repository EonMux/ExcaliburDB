package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the rule deciding what bounds the document atomicity filter.
 *
 * <p>Both ways of getting it wrong are silent. Bounding by a counter the query did not capture
 * from compares two unrelated numbers and admits or excludes documents arbitrarily, while
 * looking exactly like a snapshot. Never bounding gives up snapshot isolation without anything
 * in the response saying so.
 */
@DisplayName("Atomicity filter bound")
class AtomicityBoundTest {

    private static final long UNBOUNDED = Long.MAX_VALUE;

    /** A checker that only has to answer the one question the rule asks. */
    private static DocumentVisibilityChecker checker(boolean sharesClock) {
        return new DocumentVisibilityChecker() {
            @Override
            public boolean sharesNodeEpochClock() {
                return sharesClock;
            }

            @Override
            public boolean isDocumentVisible(String documentId, long queryEpoch) {
                return true;
            }

            @Override
            public boolean isChunkVisible(String chunkId, long queryEpoch) {
                return true;
            }

            @Override
            public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
                return chunkIds;
            }

            @Override
            public Set<String> getVisibleDocuments(long queryEpoch) {
                return Set.of();
            }

            @Override
            public long currentEpoch() {
                return 0;
            }

            @Override
            public long getCommitEpoch(String documentId) {
                return 0;
            }

            @Override
            public int getChunkCount(String documentId) {
                return 0;
            }

            @Override
            public int getDocumentCount() {
                return 1;
            }
        };
    }

    @Test
    @DisplayName("a shared clock means the query's own epoch bounds the filter")
    void sharedClockBoundsByTheQueryEpoch() {
        assertThat(RerankStage.atomicityBoundFor(checker(true), 42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("epoch zero is a real snapshot, not an absent one")
    void epochZeroIsABound() {
        // A fresh node serves queries at epoch 0; treating it as "no epoch" would silently
        // unbound the filter for the whole first tick of the process's life.
        assertThat(RerankStage.atomicityBoundFor(checker(true), 0L)).isZero();
    }

    @Test
    @DisplayName("a foreign clock is not compared against")
    void foreignClockIsUnbounded() {
        assertThat(RerankStage.atomicityBoundFor(checker(false), 42L)).isEqualTo(UNBOUNDED);
    }

    @Test
    @DisplayName("no checker means nothing to bound")
    void noCheckerIsUnbounded() {
        assertThat(RerankStage.atomicityBoundFor(null, 42L)).isEqualTo(UNBOUNDED);
    }

    @Test
    @DisplayName("a negative epoch falls back rather than failing the query")
    void negativeEpochFallsBack() {
        // The filter rejects a negative epoch outright. Passing one through would turn a
        // malformed context into a failed query rather than a degraded filter.
        assertThat(RerankStage.atomicityBoundFor(checker(true), -1L)).isEqualTo(UNBOUNDED);
    }
}
