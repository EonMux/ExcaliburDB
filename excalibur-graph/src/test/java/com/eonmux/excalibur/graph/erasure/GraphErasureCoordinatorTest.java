package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.eonmux.excalibur.graph.erasure.CompactionPolicy.CompactionStats;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator.CompactionOutcome;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GraphErasureCoordinatorTest {

    private static final int KNOWS = 0;

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final CompactionPolicy ALWAYS = (stats, now) -> true;

    private static Provenance from(SourceType type, long sourceId) {
        return new Provenance(type, sourceId, 90, 1);
    }

    /** Writes a store and leaves nothing open, so only the coordinator holds a handle. */
    private static void buildStore(Path directory, long entityCount,
                                   Consumer<EdgeStoreWriter> edges) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        edges.accept(writer);
        writer.build(directory, entityCount);
    }

    /**
     * Whether the store's mappings are still live.
     *
     * <p>Reading through a closed arena fails, which is the only handle a test has on whether the
     * store was closed: the coordinator never lets the reference out.
     */
    private static boolean isOpen(MappedEdgeStore store) {
        try {
            store.provenanceOf(0);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Hands the coordinator a real store while keeping every handle it was given. */
    private static final class RecordingOpener implements GraphErasureCoordinator.StoreOpener {

        private final List<MappedEdgeStore> opened = new ArrayList<>();

        @Override
        public MappedEdgeStore open(Path directory) throws IOException {
            MappedEdgeStore store = MappedEdgeStore.open(directory);
            opened.add(store);
            return store;
        }
    }

    @Nested
    @DisplayName("submitting")
    class Submitting {

        @Test
        @DisplayName("a request is recorded in the log under the id it returns")
        void submitDelegatesToTheLog(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK);

                long id = coordinator.submit(new ErasureScope.Entity(5));

                assertThat(log.outstanding())
                    .containsExactly(new ErasureRequest(id, new ErasureScope.Entity(5), NOW));
            }
        }

        @Test
        @DisplayName("the request is timed from the coordinator's clock")
        void submitUsesTheClock(@TempDir Path dir) throws IOException {
            // The policy is asked with the same clock, so a request timed from another one would
            // be compared against an age that means nothing.
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                new GraphErasureCoordinator(log, ALWAYS, CLOCK)
                    .submit(new ErasureScope.Entity(1));

                assertThat(log.outstanding().get(0).requestedAt()).isEqualTo(NOW);
            }
        }

        @Test
        @DisplayName("null arguments name the argument")
        void nullArguments(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                assertThatThrownBy(() -> new GraphErasureCoordinator(null, ALWAYS, CLOCK))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("log");
                assertThatThrownBy(() -> new GraphErasureCoordinator(log, null, CLOCK))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("policy");
                assertThatThrownBy(() -> new GraphErasureCoordinator(log, ALWAYS, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("clock");

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK);
                assertThatThrownBy(() -> coordinator.submit(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("scope");
                assertThatThrownBy(() -> coordinator.compactIfDue(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("graphDirectory");
            }
        }
    }

    @Nested
    @DisplayName("deciding whether to compact")
    class Deciding {

        @Test
        @DisplayName("reports what is owed and when the oldest obligation arrived")
        void reportsWhatIsPending(@TempDir Path dir) throws IOException {
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, CompactionPolicy.never(), CLOCK);
                assertThat(coordinator.pending().count()).isZero();

                coordinator.submit(new ErasureScope.Entity(1));

                assertThat(coordinator.pending())
                    .isEqualTo(new ErasureLog.Pending(1, java.util.Optional.of(NOW)));
            }
        }

        @Test
        @DisplayName("a declining policy leaves the store and the request alone")
        void policyDeclines(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2).add(2, KNOWS, 3));

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, CompactionPolicy.never(), CLOCK);
                coordinator.submit(new ErasureScope.Entity(1));

                CompactionOutcome outcome = coordinator.compactIfDue(graph);

                assertThat(outcome.ran()).isFalse();
                assertThat(outcome.edgesRemoved()).isZero();
                assertThat(outcome.requestsSatisfied()).isZero();
                assertThat(outcome.reason()).contains("policy declined");
                assertThat(log.outstandingCount())
                    .as("the obligation is still owed and must stay recorded")
                    .isEqualTo(1);
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(graph)) {
                assertThat(store.edgeCount()).as("nothing was rewritten").isEqualTo(3);
            }
        }

        @Test
        @DisplayName("an accepting policy rewrites the store and clears the request")
        void policyAccepts(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2).add(2, KNOWS, 3));

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK);
                coordinator.submit(new ErasureScope.Entity(1));

                CompactionOutcome outcome = coordinator.compactIfDue(graph);

                assertThat(outcome.ran()).isTrue();
                assertThat(outcome.edgesRemoved()).isEqualTo(1);
                assertThat(outcome.requestsSatisfied()).isEqualTo(1);
                assertThat(log.outstandingCount()).isZero();
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(graph)) {
                assertThat(store.edgeCount()).isEqualTo(2);
                assertThat(store.neighbors(0, null)).containsExactly(2);
            }
        }

        @Test
        @DisplayName("the policy is asked about the edges the requests actually match")
        void policySeesResolvedStats(@TempDir Path dir) throws IOException {
            // The tombstone count is not something the store carries; it only exists once the
            // requests have been resolved against every edge, which is why that pass comes first.
            Path graph = dir.resolve("graph");
            buildStore(graph, 5, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 100))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 100))
                .add(3, KNOWS, 4, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 200)));

            AtomicReference<CompactionStats> seen = new AtomicReference<>();
            Instant earlier = NOW.minus(Duration.ofHours(3));
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 100), earlier);
                log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 999), NOW);

                new GraphErasureCoordinator(log, (stats, now) -> {
                    seen.set(stats);
                    return false;
                }, CLOCK).compactIfDue(graph);
            }

            assertThat(seen.get().totalRecords()).isEqualTo(3);
            assertThat(seen.get().tombstonedRecords()).isEqualTo(2);
            assertThat(seen.get().oldestOutstanding())
                .as("the deadline closest to expiring, not the newest request")
                .contains(earlier);
            assertThat(seen.get().segmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the standard policy fires on an overdue request even for one edge")
        void overdueRequestDrivesCompaction(@TempDir Path dir) throws IOException {
            // One edge never moves the tombstone ratio, and it is exactly the case a deletion
            // deadline is about.
            Path graph = dir.resolve("graph");
            buildStore(graph, 50, w -> {
                for (int i = 1; i < 40; i++) {
                    w.add(0, KNOWS, i);
                }
            });

            Instant requestedAt = NOW.minus(Duration.ofDays(2));
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                log.submit(new ErasureScope.Entity(7), requestedAt);
                CompactionPolicy standard =
                    CompactionPolicy.standard(0.5, Duration.ofHours(24), 8);

                CompactionOutcome outcome =
                    new GraphErasureCoordinator(log, standard, CLOCK).compactIfDue(graph);

                assertThat(outcome.ran()).isTrue();
                assertThat(outcome.edgesRemoved()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("compactNow ignores a policy that would have refused")
        void compactNowSkipsThePolicy(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, CompactionPolicy.never(), CLOCK);
                coordinator.submit(new ErasureScope.Entity(1));

                CompactionOutcome outcome = coordinator.compactNow(graph);

                assertThat(outcome.ran()).isTrue();
                assertThat(outcome.edgesRemoved()).isEqualTo(1);
                assertThat(log.outstandingCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("requests that match nothing")
    class MatchesNothing {

        @Test
        @DisplayName("are marked satisfied so they stop driving compaction forever")
        void requestsMatchingNoEdgeAreSatisfied(@TempDir Path dir) throws IOException {
            // Left outstanding, an unsatisfiable request keeps the oldest-request condition true
            // on every call, and the store would be examined again on every tick for nothing.
            Path graph = dir.resolve("graph");
            buildStore(graph, 10, w -> w.add(0, KNOWS, 1));

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK);
                coordinator.submit(new ErasureScope.Entity(9));

                CompactionOutcome outcome = coordinator.compactIfDue(graph);

                assertThat(outcome.ran()).as("no rewrite, because nothing matched").isFalse();
                assertThat(outcome.edgesRemoved()).isZero();
                assertThat(outcome.requestsSatisfied()).isEqualTo(1);
                assertThat(log.outstandingCount()).isZero();
            }
        }

        @Test
        @DisplayName("no outstanding requests is reported rather than rewritten")
        void nothingOutstanding(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1));

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                CompactionOutcome outcome =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK).compactNow(graph);

                assertThat(outcome.ran()).isFalse();
                assertThat(outcome.requestsSatisfied()).isZero();
                assertThat(outcome.reason()).contains("no outstanding erasure requests");
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(graph)) {
                assertThat(store.edgeCount()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("store ownership")
    class Ownership {

        @Test
        @DisplayName("the compactor closes the store only when it rewrites it")
        void compactorInvariant(@TempDir Path dir) throws IOException {
            // The invariant the coordinator's ownership handling is built on. If this ever
            // changes, the branch in GraphErasureCoordinator.compact is wrong and either leaks a
            // set of mappings or unmaps memory a reader is inside.
            Path empty = dir.resolve("empty");
            buildStore(empty, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));
            MappedEdgeStore untouched = MappedEdgeStore.open(empty);
            try {
                assertThat(EdgeStoreCompactor.compact(untouched, empty, new RoaringBitmap()))
                    .isZero();
                assertThat(isOpen(untouched))
                    .as("an empty tombstone set returns before the swap and does not close")
                    .isTrue();
            } finally {
                untouched.close();
            }

            Path rewritten = dir.resolve("rewritten");
            buildStore(rewritten, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));
            MappedEdgeStore closedByCompactor = MappedEdgeStore.open(rewritten);
            RoaringBitmap tombstoned = EdgeStoreCompactor.resolve(closedByCompactor,
                List.of(new ErasureRequest(1, new ErasureScope.Entity(1), NOW)));

            EdgeStoreCompactor.compact(closedByCompactor, rewritten, tombstoned);

            assertThat(isOpen(closedByCompactor))
                .as("a non-empty set closes the store once the swap is done")
                .isFalse();
        }

        @Test
        @DisplayName("the store is closed when the policy declines")
        void declinedPathClosesTheStore(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));
            RecordingOpener opener = new RecordingOpener();

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator = new GraphErasureCoordinator(
                    log, CompactionPolicy.never(), CLOCK, opener);
                coordinator.submit(new ErasureScope.Entity(1));
                coordinator.compactIfDue(graph);
            }

            assertThat(opener.opened).hasSize(1);
            assertThat(isOpen(opener.opened.get(0)))
                .as("the compactor never saw this store, so the coordinator owed the close")
                .isFalse();
        }

        @Test
        @DisplayName("the store is closed when nothing matched")
        void emptyTombstonePathClosesTheStore(@TempDir Path dir) throws IOException {
            // The path the compactor would have left open. The coordinator does not call it at
            // all here, so the close is unambiguously its own.
            Path graph = dir.resolve("graph");
            buildStore(graph, 10, w -> w.add(0, KNOWS, 1));
            RecordingOpener opener = new RecordingOpener();

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, opener);
                coordinator.submit(new ErasureScope.Entity(9));
                coordinator.compactIfDue(graph);
            }

            assertThat(opener.opened).hasSize(1);
            assertThat(isOpen(opener.opened.get(0))).isFalse();
        }

        @Test
        @DisplayName("the store is closed exactly once when it is rewritten")
        void compactedPathDoesNotDoubleClose(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));
            RecordingOpener opener = new RecordingOpener();

            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, opener);
                coordinator.submit(new ErasureScope.Entity(1));

                CompactionOutcome outcome = coordinator.compactIfDue(graph);

                assertThat(outcome.ran()).isTrue();
            }

            assertThat(opener.opened).hasSize(1);
            assertThat(isOpen(opener.opened.get(0)))
                .as("the compactor closed it and the coordinator did not close it again")
                .isFalse();
        }

        @Test
        @DisplayName("a store that cannot be rewritten is closed rather than leaked")
        void failedRewriteClosesTheStore(@TempDir Path dir) throws IOException {
            // The compactor closes the store as its last statement, after the swap, so a failure
            // before that leaves it open. The ownership flag is cleared only once compact has
            // returned, which is what makes this path close it here.
            assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "needs a filesystem with POSIX permissions to deny the working directory");

            Path graphs = dir.resolve("graphs");
            Files.createDirectories(graphs);
            Path graph = graphs.resolve("graph");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2));
            RecordingOpener opener = new RecordingOpener();

            // The compactor builds the replacement in a sibling directory. Denying writes to the
            // parent fails it there, before the swap and before the close.
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(graphs);
            Files.setPosixFilePermissions(graphs,
                PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                assumeTrue(!Files.isWritable(graphs),
                    "permissions are not enforced for this user, so the rewrite cannot be failed");

                try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                    GraphErasureCoordinator coordinator =
                        new GraphErasureCoordinator(log, ALWAYS, CLOCK, opener);
                    coordinator.submit(new ErasureScope.Entity(1));

                    assertThatThrownBy(() -> coordinator.compactIfDue(graph))
                        .isInstanceOf(IOException.class);

                    assertThat(log.outstandingCount())
                        .as("the erasure did not happen, so the obligation stays recorded")
                        .isEqualTo(1);
                }
            } finally {
                Files.setPosixFilePermissions(graphs, original);
            }

            assertThat(opener.opened).hasSize(1);
            assertThat(isOpen(opener.opened.get(0))).isFalse();
        }
    }

    @Nested
    @DisplayName("across a restart")
    class Restart {

        @Test
        @DisplayName("a completed erasure is not applied again after the log is reopened")
        void completedErasureIsNotReRun(@TempDir Path dir) throws IOException {
            Path graph = dir.resolve("graph");
            Path file = dir.resolve("requests.log");
            buildStore(graph, 4, w -> w.add(0, KNOWS, 1).add(0, KNOWS, 2).add(2, KNOWS, 3));

            try (ErasureLog log = ErasureLog.open(file)) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK);
                coordinator.submit(new ErasureScope.Entity(1));
                assertThat(coordinator.compactIfDue(graph).edgesRemoved()).isEqualTo(1);
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                assertThat(reopened.outstandingCount()).isZero();

                CompactionOutcome second = new GraphErasureCoordinator(reopened, ALWAYS, CLOCK)
                    .compactIfDue(graph);

                assertThat(second.ran()).isFalse();
                assertThat(second.requestsSatisfied()).isZero();
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(graph)) {
                assertThat(store.edgeCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a request submitted before a restart is applied after it")
        void outstandingRequestIsAppliedAfterARestart(@TempDir Path dir) throws IOException {
            // What the log is for. The request is accepted by one process and honoured by the
            // next, with nothing held in memory in between.
            Path graph = dir.resolve("graph");
            Path file = dir.resolve("requests.log");
            buildStore(graph, 5, w -> w
                .add(0, KNOWS, 1, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 777))
                .add(0, KNOWS, 2, Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 111)));

            try (ErasureLog log = ErasureLog.open(file)) {
                new GraphErasureCoordinator(log, CompactionPolicy.never(), CLOCK)
                    .submit(new ErasureScope.Source(SourceType.DOCUMENT, 777));
            }

            try (ErasureLog reopened = ErasureLog.open(file)) {
                CompactionOutcome outcome = new GraphErasureCoordinator(reopened, ALWAYS, CLOCK)
                    .compactNow(graph);

                assertThat(outcome.ran()).isTrue();
                assertThat(outcome.edgesRemoved()).isEqualTo(1);
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(graph)) {
                boolean[] survives = { false };
                store.forEachEdge((subject, relation, object, index) -> {
                    if (store.provenanceOf(index).sourceId() == 777) {
                        survives[0] = true;
                    }
                });
                assertThat(survives[0]).as("the erased source is gone from the files").isFalse();
            }
        }
    }

    @Nested
    @DisplayName("staged facts")
    class StagedFacts {

        @Test
        @DisplayName("an erasure removes the staged facts, so the next build cannot restore them")
        void erasureClearsStaging(@TempDir Path dir) throws IOException {
            // Rewriting the store removes the erased edges from the graph being served, and
            // leaves the facts they were built from sitting in the staging log. The next
            // POST /v1/graph/build replays that log and puts back exactly what was erased -
            // and until it does, the data is still on disk, which for an erasure obligation
            // is the whole point rather than a detail.
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {

                int alice = staging.entityIdOf("alice");
                int bob = staging.entityIdOf("bob");
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 555, 90, 1)));
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "carol", "knows", "dave", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 777, 90, 1)));
                staging.appendEntityText(alice, "Alice lives at 14 Elm Street");
                staging.appendEntityText(bob, "Bob is unrelated to the request");
                staging.flush();

                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Entity(alice));
                coordinator.compactNow(graphDir);

                List<String> survivors = new ArrayList<>();
                staging.forEach((subject, relation, object, from, to, provenance) ->
                    survivors.add(subject + "-" + relation + "-" + object));
                assertThat(survivors)
                    .as("the erased entity's fact is gone from staging, not only from the store")
                    .hasSize(1);

                List<String> text = new ArrayList<>();
                staging.forEachEntityText((entityId, snippet) -> text.add(snippet));
                assertThat(text)
                    .as("and the verbatim text recorded against them went with it")
                    .containsExactly("Bob is unrelated to the request");
            }
        }

        @Test
        @DisplayName("withdrawing a document erases the prose copied out of it")
        void sourceScopedErasureReachesSnippets(@TempDir Path dir) throws IOException {
            // The finding: snippets carried no provenance, so a source scope matched none of
            // them. Withdrawing a document removed its triples, left up to 2 KiB of its prose per
            // mention to be re-indexed and re-embedded on the next build, and reported the
            // obligation discharged.
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {

                int alice = staging.entityIdOf("alice");
                int bob = staging.entityIdOf("bob");
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 42)));
                staging.appendEntityText(alice, "withdrawn prose from document 42",
                    from(SourceType.DOCUMENT, 42));
                staging.appendEntityText(bob, "prose from a document nobody withdrew",
                    from(SourceType.DOCUMENT, 77));
                staging.flush();
                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, 42));
                CompactionOutcome outcome = coordinator.compactNow(graphDir);

                assertThat(outcome.requestsSatisfied()).isEqualTo(1);
                List<String> text = new ArrayList<>();
                staging.forEachEntityText((entityId, snippet) -> text.add(snippet));
                assertThat(text).containsExactly("prose from a document nobody withdrew");
            }
        }

        @Test
        @DisplayName("a source erasure it cannot fully reach is not reported as satisfied")
        void anUnreachableSourceErasureStaysOutstanding(@TempDir Path dir) throws IOException {
            // A snippet whose origin was never recorded cannot be matched against a source scope,
            // and must not be removed on suspicion either. What must not happen is the third
            // option: reporting the request discharged with the prose still on disk and about to
            // be re-indexed. A lie about an erasure is worse than an admitted gap.
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {

                int alice = staging.entityIdOf("alice");
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 42)));
                staging.appendEntityText(alice, "prose whose origin was never recorded");
                staging.flush();
                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, 42));
                CompactionOutcome outcome = coordinator.compactNow(graphDir);

                assertThat(outcome.requestsSatisfied())
                    .as("the snippet is still there, so the obligation is not discharged")
                    .isZero();
                assertThat(outcome.requestsWithheld()).isEqualTo(1);
                assertThat(outcome.reason()).contains("origin");
                assertThat(coordinator.outstandingCount())
                    .as("and it stays outstanding so an operator has to resolve it")
                    .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an entity erasure is satisfied even beside prose of unrecorded origin")
        void entityScopedErasureIsStillSatisfied(@TempDir Path dir) throws IOException {
            // An entity scope matches on the entity id, which every snippet carries, so an
            // unrecorded origin does not stop it reaching anything.
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {

                int alice = staging.entityIdOf("alice");
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 42)));
                staging.appendEntityText(alice, "prose whose origin was never recorded");
                staging.flush();
                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Entity(alice));
                CompactionOutcome outcome = coordinator.compactNow(graphDir);

                assertThat(outcome.requestsSatisfied()).isEqualTo(1);
                assertThat(outcome.requestsWithheld()).isZero();
            }
        }

        @Test
        @DisplayName("an erased entity's name does not survive into the next build")
        void anErasedNameIsNotRepublished(@TempDir Path dir) throws IOException {
            // The name is a person's name, and it lived in entities.labels, which the erasure
            // never touched. The next build read it straight back out and published it into the
            // searchable label index, where the person who asked to be forgotten was findable by
            // searching for themselves.
            String personal = "Jane Doe (DOB 1972-04-03, NHS 4857392)";
            Path graphDir = dir.resolve("graph");
            Path stagingDir = dir.resolve("staging");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(stagingDir);
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {

                int jane = staging.entityIdOf(personal);
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    personal, "treatedAt", "St Mary's", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    from(SourceType.DOCUMENT, 42)));
                staging.flush();
                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Entity(jane));
                coordinator.compactNow(graphDir);

                assertThat(staging.entityLabels())
                    .as("what the next build writes into the dictionary and the label index")
                    .doesNotContain(personal);
                assertThat(staging.entityLabels())
                    .as("and the id space is unchanged, because no id may move")
                    .hasSize(2);
            }

            assertThat(new String(Files.readAllBytes(
                stagingDir.resolve(com.eonmux.excalibur.graph.staging.GraphStagingStore.ENTITY_LABELS)),
                java.nio.charset.StandardCharsets.UTF_8))
                .as("gone from the bytes, not merely from the view of them")
                .doesNotContain("Jane Doe");
        }

        @Test
        @DisplayName("the guard hears of it before staging is erased, and a refusal leaves "
            + "staging whole")
        void theGuardComesFirst(@TempDir Path dir) throws IOException {
            // Staging is erased ahead of the served graph, so between the two the graph is the
            // only copy of what is being erased. Whoever serves it has to be able to say so on
            // disk before that gap opens - and, when it cannot, to keep the gap from opening.
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                int alice = staging.entityIdOf("alice");
                staging.entityIdOf("bob");
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 555, 90, 1)));
                staging.flush();
                writeStore(graphDir, staging);

                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
                coordinator.submit(new ErasureScope.Entity(alice));

                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        coordinator.compactNow(graphDir, () -> {
                            throw new IOException("marker directory is read-only");
                        }))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("read-only");
                assertThat(staging.stagedCount())
                    .as("a guard that cannot record the gap keeps it from opening")
                    .isEqualTo(1);
                assertThat(coordinator.outstandingCount()).isEqualTo(1);

                List<Long> stagedWhenTold = new ArrayList<>();
                coordinator.compactNow(graphDir, () -> stagedWhenTold.add(staging.stagedCount()));

                assertThat(stagedWhenTold)
                    .as("told once, while staging still held the fact")
                    .containsExactly(1L);
                assertThat(staging.stagedCount()).isZero();
                assertThat(coordinator.outstandingCount()).isZero();
            }
        }

        @Test
        @DisplayName("the guard is not told when nothing is outstanding")
        void noGuardWithNothingOwed(@TempDir Path dir) throws IOException {
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 555, 90, 1)));
                staging.flush();
                writeStore(graphDir, staging);
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);

                coordinator.compactIfDue(graphDir, () -> {
                    throw new AssertionError("staging is not about to be erased");
                });

                assertThat(staging.stagedCount()).isEqualTo(1);
            }
        }

        private void writeStore(Path graphDir, com.eonmux.excalibur.graph.staging.GraphStagingStore staging)
                throws IOException {
            EdgeStoreWriter writer = new EdgeStoreWriter();
            staging.forEach((subject, relation, object, from, to, provenance) ->
                writer.add(subject, relation, object, from, to, provenance));
            writer.build(graphDir, staging.entityCount());
        }
    }

    @Nested
    @DisplayName("which entity ids exist")
    class AssignedEntities {

        @Test
        @DisplayName("an id is staged once the interner has handed it out, and never before")
        void stagedIdsAreTheInternersOwn(@TempDir Path dir) throws IOException {
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);

                assertThat(coordinator.stagedEntity(0)).isFalse();
                int alice = staging.entityIdOf("alice");

                assertThat(coordinator.stagedEntity(alice)).isTrue();
                assertThat(coordinator.stagedEntity(alice + 1)).isFalse();
                assertThat(coordinator.stagedEntity(-1)).isFalse();
                assertThat(new GraphErasureCoordinator(log, ALWAYS, CLOCK).stagedEntity(alice))
                    .as("a coordinator with no staging behind it has staged nothing")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("an id only the published graph names exists, served or displaced")
        void publishedIdsExist(@TempDir Path dir) throws IOException {
            Path graphDir = dir.resolve("graph");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"))) {
                staging.append(new com.eonmux.excalibur.graph.staging.ExtractedTriple(
                    "alice", "knows", "bob", Integer.MIN_VALUE, TemporalFilter.OPEN,
                    new Provenance(SourceType.DOCUMENT, 555, 90, 1)));
                staging.flush();
                com.eonmux.excalibur.graph.staging.GraphBuilder.build(staging, graphDir);
            }
            try (ErasureLog log = ErasureLog.open(dir.resolve("requests.log"))) {
                GraphErasureCoordinator coordinator = new GraphErasureCoordinator(log, ALWAYS, CLOCK);

                assertThat(coordinator.entityAssigned(1, graphDir)).isTrue();
                assertThat(coordinator.entityAssigned(2, graphDir)).isFalse();

                Files.move(graphDir,
                    com.eonmux.excalibur.graph.staging.GraphBuilder.previousFor(graphDir));
                assertThat(coordinator.entityAssigned(1, graphDir))
                    .as("a graph a publish set aside is restored on open, so it still counts")
                    .isTrue();
            }
        }

        @Test
        @DisplayName("an outstanding request naming an id nothing assigned is discharged, "
            + "durably, and every other request is left alone")
        void unassignedRequestsAreDischarged(@TempDir Path dir) throws IOException {
            Path requests = dir.resolve("requests.log");
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(dir.resolve("staging"));
                 ErasureLog log = ErasureLog.open(requests)) {
                int alice = staging.entityIdOf("alice");
                log.submit(new ErasureScope.Entity(alice), NOW);
                long stray = log.submit(new ErasureScope.Entity(42), NOW);
                log.submit(new ErasureScope.Source(SourceType.DOCUMENT, 42), NOW);
                GraphErasureCoordinator coordinator =
                    new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);

                List<ErasureRequest> discharged =
                    coordinator.dischargeUnassigned(dir.resolve("graph"));

                assertThat(discharged).extracting(ErasureRequest::id).containsExactly(stray);
                assertThat(coordinator.outstandingCount()).isEqualTo(2);
                assertThat(coordinator.dischargeUnassigned(dir.resolve("graph")))
                    .as("nothing is left to discharge")
                    .isEmpty();
            }
            assertThat(ErasureLog.inspect(requests).count())
                .as("the completion is on disk, not only in memory")
                .isEqualTo(2);
            assertThat(ErasureLog.entitiesOwed(requests)).containsExactly(0L);
        }
    }
}
