package io.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.excalibur.graph.format.EdgeColumn;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureTest {

    private static final int KNOWS = 0;
    private static final int WORKS_AT = 1;

    private static Provenance from(SourceType type, long sourceId, int extractorVersion) {
        return new Provenance(type, sourceId, 90, extractorVersion);
    }

    private static MappedEdgeStore build(Path dir, long entityCount,
                                         java.util.function.Consumer<EdgeStoreWriter> edges)
            throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        edges.accept(writer);
        writer.build(dir, entityCount);
        return MappedEdgeStore.open(dir);
    }

    @Nested
    @DisplayName("scope resolution")
    class Scopes {

        @Test
        @DisplayName("erasing an entity covers its edges in both directions")
        void entityScopeCoversBothDirections(@TempDir Path dir) throws IOException {
            // Keeping the incoming edges would leave the entity reconstructible from its
            // neighbours, which is not erasure.
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(1, KNOWS, 2)
                    .add(3, KNOWS, 1)
                    .add(2, KNOWS, 3))) {

                RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store,
                    List.of(new ErasureRequest(1, new ErasureScope.Entity(1), Instant.now())));

                assertThat(tombstoned.getCardinality())
                    .as("both the outgoing and the incoming edge")
                    .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("erasing a source covers only that source's edges")
        void sourceScope(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 100, 1))
                    .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 200, 1))
                    .add(0, KNOWS, 3, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.CONVERSATION, 100, 1)))) {

                RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store, List.of(
                    new ErasureRequest(1,
                        new ErasureScope.Source(SourceType.DOCUMENT, 100), Instant.now())));

                assertThat(tombstoned.getCardinality())
                    .as("same source id but a different source type must not match")
                    .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("erasing an extractor version covers only its output")
        void extractorVersionScope(@TempDir Path dir) throws IOException {
            // What extractor_version is stored for: retracting one build's output without
            // touching what other versions produced.
            try (MappedEdgeStore store = build(dir, 4, w -> w
                    .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 1, 7))
                    .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 2, 8))
                    .add(0, KNOWS, 3, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 3, 7)))) {

                RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store,
                    List.of(new ErasureRequest(1, new ErasureScope.ExtractorVersion(7),
                        Instant.now())));

                assertThat(tombstoned.getCardinality()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("several requests are resolved in one pass")
        void multipleRequests(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 5, w -> w
                    .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 1, 1))
                    .add(2, KNOWS, 3, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 2, 1))
                    .add(3, KNOWS, 4, Integer.MIN_VALUE, TemporalFilter.OPEN,
                        from(SourceType.DOCUMENT, 3, 1)))) {

                RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store, List.of(
                    new ErasureRequest(1, new ErasureScope.Entity(0), Instant.now()),
                    new ErasureRequest(2, new ErasureScope.Source(SourceType.DOCUMENT, 3),
                        Instant.now())));

                assertThat(tombstoned.getCardinality()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("no requests tombstones nothing")
        void noRequests(@TempDir Path dir) throws IOException {
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThat(EdgeStoreCompactor.resolve(store, List.of()).isEmpty()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("compaction")
    class Compaction {

        @Test
        @DisplayName("erased data is gone from the files, not merely hidden")
        void dataIsActuallyRemoved(@TempDir Path parent) throws IOException {
            // The point of the whole mechanism. A tombstone leaves the subject, object, relation,
            // source id and confidence sitting in the column files for anyone with the disk.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = build(dir, 5, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 555, 1))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 777, 1))
                .add(3, WORKS_AT, 4, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 999, 1)));

            long sizeBefore = Files.size(dir.resolve(EdgeColumn.EDGES_FWD.fileName()));
            RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(store,
                List.of(new ErasureRequest(1,
                    new ErasureScope.Source(SourceType.DOCUMENT, 777), Instant.now())));

            long removed = EdgeStoreCompactor.compact(store, dir, tombstoned);

            assertThat(removed).isEqualTo(1);
            assertThat(Files.size(dir.resolve(EdgeColumn.EDGES_FWD.fileName())))
                .as("the adjacency file itself is smaller")
                .isLessThan(sizeBefore);

            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                assertThat(reopened.edgeCount()).isEqualTo(2);
                assertThat(reopened.neighbors(0, null))
                    .as("the erased edge's object is no longer reachable")
                    .containsExactly(1);

                // Nothing anywhere in the store still carries the erased source id.
                boolean[] found = { false };
                reopened.forEachEdge((subject, relation, object, index) -> {
                    if (reopened.provenanceOf(index).sourceId() == 777) {
                        found[0] = true;
                    }
                });
                assertThat(found[0]).isFalse();
            }
        }

        @Test
        @DisplayName("surviving edges keep their intervals and provenance exactly")
        void survivorsAreUnchanged(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            int from = 100;
            int to = 500;
            Provenance kept = new Provenance(SourceType.KNOWLEDGE_BASE, 42, 87, 3);

            MappedEdgeStore store = build(dir, 4, w -> w
                .add(0, KNOWS, 1, from, to, kept)
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 999, 1)));

            EdgeStoreCompactor.compact(store, dir, EdgeStoreCompactor.resolve(store,
                List.of(new ErasureRequest(1,
                    new ErasureScope.Source(SourceType.DOCUMENT, 999), Instant.now()))));

            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                assertThat(reopened.edgeCount()).isEqualTo(1);
                assertThat(reopened.validFrom(0)).isEqualTo(from);
                assertThat(reopened.validTo(0)).isEqualTo(to);
                assertThat(reopened.provenanceOf(0)).isEqualTo(kept);
            }
        }

        @Test
        @DisplayName("the entity id space is preserved rather than renumbered")
        void entityIdsAreStable(@TempDir Path parent) throws IOException {
            // Renumbering would invalidate every entity id held anywhere else in the system.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = build(dir, 100, w -> w
                .add(0, KNOWS, 50)
                .add(0, KNOWS, 99));

            EdgeStoreCompactor.compact(store, dir, EdgeStoreCompactor.resolve(store,
                List.of(new ErasureRequest(1, new ErasureScope.Entity(50), Instant.now()))));

            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                assertThat(reopened.entityCount()).isEqualTo(100);
                assertThat(reopened.neighbors(0, null)).containsExactly(99);
            }
        }

        @Test
        @DisplayName("compacting nothing leaves the store alone")
        void emptyTombstoneSetIsANoOp(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            try (MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1))) {
                assertThat(EdgeStoreCompactor.compact(store, dir, new RoaringBitmap())).isZero();
                assertThat(store.edgeCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("no temporary directories survive a successful compaction")
        void noStrayDirectories(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            MappedEdgeStore store = build(dir, 3, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));

            EdgeStoreCompactor.compact(store, dir, EdgeStoreCompactor.resolve(store,
                List.of(new ErasureRequest(1, new ErasureScope.Entity(2), Instant.now()))));

            try (var entries = Files.list(parent)) {
                assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("store");
            }
        }
    }

    @Nested
    @DisplayName("compaction policy")
    class Policy {

        private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        private final CompactionPolicy policy =
            CompactionPolicy.standard(0.2, Duration.ofHours(24), 8);

        @Test
        @DisplayName("a high tombstone ratio triggers compaction")
        void ratioTriggers() {
            assertThat(policy.shouldCompact(
                new CompactionPolicy.CompactionStats(100, 25, Optional.empty(), 1), now)).isTrue();
            assertThat(policy.shouldCompact(
                new CompactionPolicy.CompactionStats(100, 5, Optional.empty(), 1), now)).isFalse();
        }

        @Test
        @DisplayName("one long-outstanding erasure triggers compaction on its own")
        void oldestRequestTriggersRegardlessOfRatio() {
            // The case the ratio can never catch: a single edge under a deletion request never
            // moves the ratio, and its deadline does not care how much other data there is.
            CompactionPolicy.CompactionStats barelyAnyTombstones =
                new CompactionPolicy.CompactionStats(
                    1_000_000, 1, Optional.of(now.minus(Duration.ofDays(2))), 1);

            assertThat(barelyAnyTombstones.tombstoneRatio()).isLessThan(0.000_01);
            assertThat(policy.shouldCompact(barelyAnyTombstones, now)).isTrue();
        }

        @Test
        @DisplayName("a recent request does not trigger on its own")
        void recentRequestWaits() {
            assertThat(policy.shouldCompact(new CompactionPolicy.CompactionStats(
                1_000_000, 1, Optional.of(now.minus(Duration.ofHours(1))), 1), now)).isFalse();
        }

        @Test
        @DisplayName("too many segments triggers compaction")
        void segmentCountTriggers() {
            assertThat(policy.shouldCompact(
                new CompactionPolicy.CompactionStats(100, 0, Optional.empty(), 9), now)).isTrue();
            assertThat(policy.shouldCompact(
                new CompactionPolicy.CompactionStats(100, 0, Optional.empty(), 8), now)).isFalse();
        }

        @Test
        @DisplayName("the oldest request is the one that counts")
        void oldestOutstandingIsTheOldest() {
            Instant old = now.minus(Duration.ofDays(5));
            Optional<Instant> oldest = EdgeStoreCompactor.oldestOutstanding(List.of(
                new ErasureRequest(1, new ErasureScope.Entity(1), now),
                new ErasureRequest(2, new ErasureScope.Entity(2), old),
                new ErasureRequest(3, new ErasureScope.Entity(3), now.minus(Duration.ofDays(1)))));

            assertThat(oldest).contains(old);
        }

        @Test
        @DisplayName("an empty store has a zero tombstone ratio rather than dividing by zero")
        void emptyStoreRatio() {
            assertThat(new CompactionPolicy.CompactionStats(0, 0, Optional.empty(), 0)
                .tombstoneRatio()).isZero();
        }

        @Test
        @DisplayName("never() does not compact under any condition")
        void neverPolicy() {
            assertThat(CompactionPolicy.never().shouldCompact(
                new CompactionPolicy.CompactionStats(
                    100, 100, Optional.of(Instant.EPOCH), 100), now)).isFalse();
        }
    }
}
