package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        from(SourceType.USER_ASSERTED, 100, 1)))) {

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
        @DisplayName("everything else in the graph directory survives the rewrite")
        void sidecarsSurvive(@TempDir Path parent) throws IOException {
            // The compaction rebuilds the edge store's columns and swaps the directory those
            // columns live in. That directory also holds the label dictionaries, the two Lucene
            // indexes and the entity vectors - everything GraphRagFactory.openAt reads back - and
            // rebuilding only the columns once meant the swap deleted the rest. A compaction is
            // the one operation that must not lose data: it is how an erasure obligation is
            // discharged, and the retired copy is deleted afterwards, so the loss was final.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = build(dir, 3, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 555, 1))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 777, 1)));

            Files.writeString(dir.resolve("entity_dictionary.dat"), "alice\nbob\n");
            Files.writeString(dir.resolve("entity_dictionary.idx"), "0 6\n");
            Path lucene = Files.createDirectories(dir.resolve("documents"));
            Files.writeString(lucene.resolve("segments_1"), "lucene segment");
            Path nested = Files.createDirectories(lucene.resolve("sub"));
            Files.writeString(nested.resolve("deeper"), "still here");

            EdgeStoreCompactor.compact(store, dir, EdgeStoreCompactor.resolve(store,
                List.of(new ErasureRequest(1,
                    new ErasureScope.Source(SourceType.DOCUMENT, 777), Instant.now()))));

            assertThat(dir.resolve("entity_dictionary.dat"))
                .as("the entity dictionary is not rebuilt by a compaction, so it has to be kept")
                .hasContent("alice\nbob\n");
            assertThat(dir.resolve("entity_dictionary.idx")).hasContent("0 6\n");
            assertThat(lucene.resolve("segments_1")).hasContent("lucene segment");
            assertThat(nested.resolve("deeper"))
                .as("carried across a whole tree, not just its top level")
                .hasContent("still here");

            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                assertThat(reopened.edgeCount())
                    .as("and the compaction still did its own job")
                    .isEqualTo(1);
            }
        }

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
            Provenance kept = new Provenance(SourceType.USER_ASSERTED, 42, 87, 3);

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

    @Nested
    @DisplayName("compaction durability")
    class Durable {

        /** Records what was synced, and can be told to fail at a chosen call. */
        private static final class Recording implements Durability {

            private final List<Path> files = new ArrayList<>();
            private final List<Path> directories = new ArrayList<>();
            private final int failAtFile;

            Recording(int failAtFile) {
                this.failAtFile = failAtFile;
            }

            @Override
            public void fsyncFile(Path file) throws IOException {
                files.add(file);
                if (files.size() == failAtFile) {
                    throw new IOException("simulated device failure syncing " + file);
                }
                Durability.super.fsyncFile(file);
            }

            @Override
            public void fsyncDirectory(Path directory) throws IOException {
                directories.add(directory);
                Durability.super.fsyncDirectory(directory);
            }
        }

        private MappedEdgeStore twoEdges(Path dir) throws IOException {
            return build(dir, 3, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 555, 1))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 777, 1)));
        }

        private RoaringBitmap eraseDocument777(MappedEdgeStore store) {
            return EdgeStoreCompactor.resolve(store, List.of(new ErasureRequest(1,
                new ErasureScope.Source(SourceType.DOCUMENT, 777), Instant.now())));
        }

        @Test
        @DisplayName("the replacement is forced to the device before the original is destroyed")
        void theReplacementIsForcedBeforeTheOriginalGoes(@TempDir Path parent) throws IOException {
            // Compaction deletes the retired original, so the replacement is the only copy from
            // that moment on. Nothing in the rewrite was ever fsynced, so a power loss between
            // the rename and writeback left a present descriptor over short or empty columns -
            // and MappedColumns.open refuses those, leaving a graph that will not open at all.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);
            Recording durability = new Recording(0);

            EdgeStoreCompactor.compact(store, dir, eraseDocument777(store), durability);

            List<String> syncedNames = durability.files.stream()
                .map(path -> path.getFileName().toString())
                .toList();
            List<String> present;
            try (var entries = Files.list(dir)) {
                present = entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).sorted().toList();
            }
            assertThat(syncedNames)
                .as("every file of the replacement store has to be on the device")
                .containsAll(present);
            assertThat(durability.directories)
                .as("and the directory entry naming them, or the files can come back unreferenced")
                .contains(dir.getParent());
        }

        @Test
        @DisplayName("a failed sync leaves the original store in place and openable")
        void aFailedSyncKeepsTheOriginal(@TempDir Path parent) throws IOException {
            // The sync has to happen while the original is still the store being served. If it
            // were attempted after the swap, a device error would have already cost the only
            // durable copy.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);
            RoaringBitmap tombstoned = eraseDocument777(store);

            assertThatThrownBy(() ->
                EdgeStoreCompactor.compact(store, dir, tombstoned, new Recording(1)))
                .isInstanceOf(IOException.class);

            store.close();
            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                assertThat(reopened.edgeCount())
                    .as("nothing was erased, and nothing was lost either")
                    .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("the retired original is removed only once the replacement is durable")
        void theRetiredCopyGoesLast(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);

            EdgeStoreCompactor.compact(store, dir, eraseDocument777(store), new Recording(0));

            try (var entries = Files.list(parent)) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly("store");
            }
        }
    }

    /**
     * A compaction killed part-way through putting its replacement in place.
     *
     * <p>A process that dies cannot be simulated by an exception the code under test catches, so
     * these stop it with an {@link Error} nothing in the compaction handles: whatever was on disk
     * at that instant is what the next start finds, exactly as after a power cut.</p>
     */
    @Nested
    @DisplayName("a crash during the swap")
    class CrashDuringSwap {

        /** Stands in for the process dying: nothing between here and the test catches it. */
        private static final class Crash extends Error {
            Crash(String where) {
                super("simulated crash " + where);
            }
        }

        private MappedEdgeStore twoEdges(Path dir) throws IOException {
            return build(dir, 3, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 555, 1))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 777, 1)));
        }

        private RoaringBitmap eraseDocument777(MappedEdgeStore store) {
            return EdgeStoreCompactor.resolve(store, List.of(new ErasureRequest(1,
                new ErasureScope.Source(SourceType.DOCUMENT, 777), Instant.now())));
        }

        private long edgesAt(Path dir) throws IOException {
            try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
                return reopened.edgeCount();
            }
        }

        private List<String> namesIn(Path parent) throws IOException {
            try (var entries = Files.list(parent)) {
                return entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
        }

        @Test
        @DisplayName("between the renames, the served graph is recovered from where a publish "
            + "sets it aside")
        void betweenTheRenames(@TempDir Path parent) throws IOException {
            // The window the compaction's own swap left unrecoverable: the served name gone and
            // the whole graph under a name startup recovery never looked at, so the tenant
            // served nothing - and the copy holding the erased facts stayed on disk for good.
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);
            Durability firstRenameThenDie = new Durability() {
                @Override
                public void fsyncTree(Path root) throws IOException {
                    Durability.super.fsyncTree(root);
                    if (!root.equals(EdgeStoreCompactor.scratchFor(dir))) {
                        return;
                    }
                    // The replacement is forced; the publish's first rename is the next thing
                    // it does. Doing it here and dying is the state a crash between the two
                    // renames leaves, reached through the compaction's own scratch directory.
                    Files.move(dir, com.eonmux.excalibur.graph.staging.GraphBuilder
                        .previousFor(dir), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    throw new Crash("between the renames");
                }
            };

            try {
                assertThatThrownBy(() -> EdgeStoreCompactor.compact(store, dir,
                    eraseDocument777(store), List.of(), List.of(), firstRenameThenDie))
                    .isInstanceOf(Crash.class);
                assertThat(dir).doesNotExist();

                assertThat(com.eonmux.excalibur.graph.staging.GraphBuilder
                    .recoverInterruptedPublish(dir))
                    .as("recovery knows where the graph went")
                    .isTrue();
            } finally {
                store.close();
            }

            assertThat(edgesAt(dir))
                .as("the graph from before the erasure is back, for the obligation to be "
                    + "applied to again; nothing marked it discharged")
                .isEqualTo(2);
            assertThat(namesIn(parent))
                .as("no displaced copy is left behind for anything to find")
                .doesNotContain("store.previous", "store.retired");
            assertThat(EdgeStoreCompactor.scratchFor(dir))
                .as("the interrupted compaction is visible to whoever opens the graph next")
                .isDirectory();
        }

        @Test
        @DisplayName("after both renames, the replacement stands and the displaced copy goes")
        void afterBothRenames(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);
            Durability dieOnTheParentSync = new Durability() {
                @Override
                public void fsyncDirectory(Path directory) throws IOException {
                    if (directory.equals(parent)) {
                        throw new Crash("after both renames");
                    }
                    Durability.super.fsyncDirectory(directory);
                }
            };

            try {
                assertThatThrownBy(() -> EdgeStoreCompactor.compact(store, dir,
                    eraseDocument777(store), List.of(), List.of(), dieOnTheParentSync))
                    .isInstanceOf(Crash.class);
                assertThat(com.eonmux.excalibur.graph.staging.GraphBuilder.previousFor(dir))
                    .as("the pre-erasure graph sits where recovery looks")
                    .isDirectory();

                assertThat(com.eonmux.excalibur.graph.staging.GraphBuilder
                    .recoverInterruptedPublish(dir)).isFalse();
            } finally {
                store.close();
            }

            assertThat(edgesAt(dir)).as("the erased edge stays erased").isEqualTo(1);
            assertThat(namesIn(parent))
                .as("the copy still holding the erased edge is deleted, not kept")
                .containsExactly("store");
        }

        @Test
        @DisplayName("the graph is set aside under the name a build's publish uses")
        void sharesThePublishLayout(@TempDir Path parent) throws IOException {
            Path dir = parent.resolve("store");
            MappedEdgeStore store = twoEdges(dir);
            List<List<String>> seen = new ArrayList<>();
            Durability watching = new Durability() {
                @Override
                public void fsyncDirectory(Path directory) throws IOException {
                    if (directory.equals(parent)) {
                        seen.add(namesIn(parent));
                    }
                    Durability.super.fsyncDirectory(directory);
                }
            };

            EdgeStoreCompactor.compact(store, dir, eraseDocument777(store), List.of(), List.of(),
                watching);

            assertThat(seen).first().asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .contains("store", "store.previous")
                .doesNotContain("store.retired", "store.compacting");
            assertThat(namesIn(parent)).containsExactly("store");
        }
    }
}
