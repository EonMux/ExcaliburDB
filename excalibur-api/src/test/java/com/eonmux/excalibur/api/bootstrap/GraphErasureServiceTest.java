package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.LexicalEntityLinker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an erasure attempt costs the node when there is nothing to erase.
 *
 * <p>A compaction replaces the directory the served stack is mapped over, so the service closes
 * that stack and reopens it afterwards. It was doing so before asking whether a compaction was
 * due at all - which made every declined attempt an outage of {@code /v1/graph/query} for the
 * length of a reopen, in exchange for no work. That was survivable while the only caller was an
 * erasure request; it is not once the latency bound is swept on a schedule.</p>
 */
@DisplayName("Graph erasure service")
class GraphErasureServiceTest {

    /** The pipeline being served, for identity checks; the reading is released at once. */
    private static java.util.Optional<com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline>
            served(GraphRagHolder holder) {
        java.util.Optional<GraphRagHolder.Reading> reading = holder.acquire();
        reading.ifPresent(GraphRagHolder.Reading::close);
        return reading.map(GraphRagHolder.Reading::pipeline);
    }

    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int COLLABORATED_WITH = 0;
    private static final int UNKNOWN_ENTITY = 99;

    private GraphRagHolder holder;

    @AfterEach
    void tearDown() {
        if (holder != null) {
            holder.close();
        }
    }

    @Nested
    @DisplayName("When nothing is owed")
    class NothingOwed {

        @Test
        @DisplayName("leaves the served graph mapped rather than closing and reopening it")
        void doesNotDisturbTheServedGraph(@TempDir Path root) throws IOException {
            GraphErasureService service = serviceOver(root);
            GraphRagPipeline before = served(holder).orElseThrow();

            GraphErasureService.ErasureOutcome outcome = service.compactIfDue();

            assertThat(outcome.ran()).isFalse();
            assertThat(served(holder).orElseThrow())
                .as("a reopened stack is a different pipeline, and a query in the gap would "
                    + "have been told no graph is built")
                .isSameAs(before);
        }

        @Test
        @DisplayName("says nothing is outstanding, rather than quoting a policy it never asked")
        void namesTheReason(@TempDir Path root) throws IOException {
            assertThat(serviceOver(root).compactIfDue().reason()).contains("no outstanding");
        }

        @Test
        @DisplayName("an explicit compaction still goes through, since a caller asked for it")
        void forcingStillRuns(@TempDir Path root) throws IOException {
            GraphErasureService service = serviceOver(root);
            GraphRagPipeline before = served(holder).orElseThrow();

            service.compactNow();

            assertThat(served(holder).orElseThrow()).isNotSameAs(before);
        }
    }

    @Nested
    @DisplayName("When an obligation is outstanding")
    class Owed {

        @Test
        @DisplayName("the served graph is closed around the attempt, because one may rewrite it")
        void closesAroundTheAttempt(@TempDir Path root) throws IOException {
            // Past its deadline, so the policy says run. What is being checked is that the close
            // happened at all: the reopened stack is a different pipeline.
            GraphErasureService service = serviceOver(root, Duration.ZERO);
            GraphRagPipeline before = served(holder).orElseThrow();
            service.submit(new ErasureScope.Entity(BABBAGE));

            service.compactIfDue();

            assertThat(served(holder).orElseThrow()).isNotSameAs(before);
        }

        @Test
        @DisplayName("the obligation is counted while it waits")
        void countsWhatIsOwed(@TempDir Path root) throws IOException {
            GraphErasureService service = serviceOver(root);

            assertThat(service.outstandingCount()).isZero();
            service.submit(new ErasureScope.Entity(ADA));
            assertThat(service.outstandingCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Which entities an erasure may name")
    class Existence {

        @Test
        @DisplayName("an id nothing in the namespace was given is refused, and nothing recorded")
        void anUnassignedIdIsRefused(@TempDir Path root) throws IOException {
            GraphErasureService service = serviceOver(root);

            assertThatThrownBy(() -> service.submit(new ErasureScope.Entity(UNKNOWN_ENTITY)))
                .isInstanceOf(GraphErasureService.UnknownEntityException.class)
                .hasMessage("no entity in this namespace has id " + UNKNOWN_ENTITY)
                .extracting(e -> ((GraphErasureService.UnknownEntityException) e).entityId())
                .isEqualTo((long) UNKNOWN_ENTITY);
            assertThat(service.outstandingCount()).isZero();
        }

        @Test
        @DisplayName("scopes that select by provenance are recorded whatever the graph holds")
        void otherScopesAreNotChecked(@TempDir Path root) throws IOException {
            GraphErasureService service = serviceOver(root);

            service.submit(new ErasureScope.Source(SourceType.DOCUMENT, 404));
            service.submit(new ErasureScope.ExtractorVersion(404));

            assertThat(service.outstandingCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("an id only the served graph names waits out a build or compaction, which "
            + "could replace that graph with one that does not name it")
        void aServedOnlyIdWaitsForAReplacement(@TempDir Path root) throws Exception {
            GraphErasureService service = serviceOver(root);
            java.util.concurrent.ExecutorService other =
                java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.locks.ReentrantLock replacement = holder.replacementLock();
            replacement.lock();
            java.util.concurrent.Future<Long> submitted;
            try {
                submitted = other.submit(() -> service.submit(new ErasureScope.Entity(ADA)));
                Thread.sleep(200);
                assertThat(submitted.isDone())
                    .as("judged and recorded under the lock a replacement holds")
                    .isFalse();
            } finally {
                replacement.unlock();
            }
            try {
                assertThat(submitted.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                assertThat(service.outstandingCount()).isEqualTo(1);
            } finally {
                other.shutdownNow();
            }
        }

        @Test
        @DisplayName("a staged id is accepted without waiting on a replacement")
        void aStagedIdDoesNotWait(@TempDir Path root) throws Exception {
            Path graph = root.resolve("graph");
            buildGraph(graph);
            Config config = new Config(Map.of());
            holder = new GraphRagHolder(graph, null);
            try (com.eonmux.excalibur.graph.staging.GraphStagingStore staging =
                     com.eonmux.excalibur.graph.staging.GraphStagingStore.open(
                         root.resolve("graph-staging"));
                 ErasureLog log = ErasureLog.open(root.resolve("erasure.log"))) {
                GraphErasureService service = new GraphErasureService(
                    new GraphErasureCoordinator(log,
                        CompactionPolicy.standard(0.2, Duration.ofSeconds(3600),
                            Integer.MAX_VALUE),
                        Clock.systemUTC(), staging),
                    holder, config, GraphModelClients.forConfig(config));
                int hypatia = staging.entityIdOf("Hypatia");
                java.util.concurrent.ExecutorService other =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
                java.util.concurrent.locks.ReentrantLock replacement = holder.replacementLock();
                replacement.lock();
                try {
                    assertThat(other.submit(() -> service.submit(new ErasureScope.Entity(hypatia)))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("a staged id is never given up, so nothing a replacement does can "
                            + "change the answer")
                        .isNotNull();
                } finally {
                    replacement.unlock();
                    other.shutdownNow();
                }
            }
        }
    }

    @Nested
    @DisplayName("After a publish was interrupted")
    class InterruptedPublish {

        @Test
        @DisplayName("the displaced graph is restored and erased, not left beside the staging pass")
        void erasesTheDisplacedGraph(@TempDir Path root) throws IOException {
            // A crash between a publish's two renames leaves only graph.previous. Treating that
            // as "no graph built" would discharge the obligation against staging alone while the
            // displaced copy, still holding the facts, sat on disk until the next build.
            Path graph = root.resolve("graph");
            buildGraph(graph);
            java.nio.file.Files.move(graph, GraphBuilder.previousFor(graph));
            Config config = new Config(Map.of());
            holder = new GraphRagHolder(graph, null);
            GraphErasureService service = new GraphErasureService(
                new GraphErasureCoordinator(ErasureLog.open(root.resolve("erasure.log")),
                    CompactionPolicy.standard(0.2, Duration.ZERO, Integer.MAX_VALUE),
                    Clock.systemUTC()),
                holder, config, GraphModelClients.forConfig(config));
            service.submit(new ErasureScope.Entity(ADA));

            GraphErasureService.ErasureOutcome outcome = service.compactNow();

            assertThat(outcome.reason()).doesNotContain("no graph has been built");
            assertThat(outcome.edgesRemoved()).isEqualTo(1);
            assertThat(graph).isDirectory();
            assertThat(GraphBuilder.previousFor(graph)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("After an erasure was interrupted")
    class InterruptedErasure {

        /** A service over a built graph that is not being served, as a tenant opens one. */
        private GraphErasureService withheldServiceOver(Path graph, Duration maxLatency)
                throws IOException {
            buildGraph(graph);
            Config config = new Config(Map.of());
            holder = new GraphRagHolder(graph, null);
            return new GraphErasureService(
                new GraphErasureCoordinator(
                    ErasureLog.open(graph.resolveSibling("erasure.log")),
                    CompactionPolicy.standard(0.2, maxLatency, Integer.MAX_VALUE),
                    Clock.systemUTC()),
                holder, config, GraphModelClients.forConfig(config), markerBeside(graph));
        }

        private Path markerBeside(Path graph) {
            return graph.resolveSibling("withheld");
        }

        @Test
        @DisplayName("what is outstanding is applied, and only then is the graph served")
        void appliesThenServes(@TempDir Path root) throws IOException {
            GraphErasureService service = withheldServiceOver(root.resolve("graph"),
                Duration.ofSeconds(3600));
            service.submit(new ErasureScope.Entity(ADA));

            GraphErasureService.ErasureOutcome outcome = service.finishInterruptedErasure();

            assertThat(outcome.edgesRemoved())
                .as("applied although the policy would not yet call it due")
                .isEqualTo(1);
            assertThat(outcome.ran()).isTrue();
            assertThat(service.servingWithheld()).isFalse();
            assertThat(holder.isPresent())
                .as("the graph is served once the erasure is through")
                .isTrue();
        }

        @Test
        @DisplayName("a graph the erasure could not be applied to stays unserved until it can be")
        void staysWithheldUntilApplied(@TempDir Path root) throws IOException {
            Path graph = root.resolve("graph");
            GraphErasureService service = withheldServiceOver(graph, Duration.ofSeconds(3600));
            service.submit(new ErasureScope.Entity(ADA));
            // A failure that says nothing about the bytes: the descriptor cannot be read at all.
            // A damaged graph is discarded instead, which GraphTenantDamageTest covers.
            Path descriptor = graph.resolve(
                com.eonmux.excalibur.graph.format.EdgeStoreDescriptor.FILE_NAME);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> readable =
                java.nio.file.Files.getPosixFilePermissions(descriptor);
            java.nio.file.Files.setPosixFilePermissions(descriptor, java.util.Set.of());
            org.junit.jupiter.api.Assumptions.assumeTrue(
                !java.nio.file.Files.isReadable(descriptor),
                "needs a user file permissions apply to");

            org.assertj.core.api.Assertions.assertThatThrownBy(service::finishInterruptedErasure)
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(com.eonmux.excalibur.graph.file.GraphCorruptionException.class);

            assertThat(holder.isPresent())
                .as("a graph that may still hold what was erased is not served")
                .isFalse();
            assertThat(service.servingWithheld()).isTrue();
            assertThat(service.outstandingCount()).isEqualTo(1);
            assertThat(markerBeside(graph))
                .as("written down, so an eviction or a restart does not forget it")
                .exists();

            java.nio.file.Files.setPosixFilePermissions(descriptor, readable);
            GraphErasureService.ErasureOutcome retried = service.compactIfDue();

            assertThat(retried.edgesRemoved())
                .as("the scheduled attempt applies it without waiting for the policy")
                .isEqualTo(1);
            assertThat(holder.isPresent()).isTrue();
            assertThat(service.servingWithheld()).isFalse();
            assertThat(markerBeside(graph)).doesNotExist();
        }

        @Test
        @DisplayName("an ordinary compaction of an unserved graph leaves it unserved")
        void anOrdinaryCompactionDoesNotStartServing(@TempDir Path root) throws IOException {
            GraphErasureService service = withheldServiceOver(root.resolve("graph"),
                Duration.ZERO);
            service.submit(new ErasureScope.Entity(ADA));

            service.compactNow();

            assertThat(holder.isPresent())
                .as("only a graph withheld for an erasure is put back afterwards")
                .isFalse();
        }
    }

    private GraphErasureService serviceOver(Path root) throws IOException {
        return serviceOver(root, Duration.ofSeconds(3600));
    }

    private GraphErasureService serviceOver(Path root, Duration maxLatency) throws IOException {
        Path graph = root.resolve("graph");
        buildGraph(graph);
        Config config = new Config(Map.of());
        holder = new GraphRagHolder(graph, GraphRagFactory.openAt(config, graph));
        ErasureLog log = ErasureLog.open(root.resolve("erasure.log"));
        return new GraphErasureService(
            new GraphErasureCoordinator(log,
                CompactionPolicy.standard(0.2, maxLatency, Integer.MAX_VALUE),
                Clock.systemUTC()),
            holder, config, GraphModelClients.forConfig(config));
    }

    /** Writes a complete, minimal graph store into {@code directory}. */
    private static void buildGraph(Path directory) throws IOException {
        EdgeStoreWriter edges = new EdgeStoreWriter();
        edges.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN,
            new Provenance(SourceType.KNOWLEDGE_BASE, 1, 96, 7));
        edges.build(directory, 8);

        try (LexicalIndex documents = LexicalIndex.open(
                directory.resolve(GraphRagFactory.DOCUMENT_INDEX), 16.0)) {
            documents.put(ADA, "Ada Lovelace mathematician");
            documents.put(BABBAGE, "Charles Babbage engineer");
            documents.refresh();
        }
        try (LexicalIndex labels = LexicalIndex.open(
                directory.resolve(GraphRagFactory.LABEL_INDEX), 16.0)) {
            LexicalEntityLinker.index(labels, ADA, "Ada Lovelace");
            LexicalEntityLinker.index(labels, BABBAGE, "Charles Babbage");
            labels.refresh();
        }

        new LabelDictionaryWriter()
            .put(ADA, "Ada Lovelace")
            .put(BABBAGE, "Charles Babbage")
            .write(directory, GraphRagFactory.ENTITY_DICTIONARY);
        new LabelDictionaryWriter()
            .put(COLLABORATED_WITH, "collaboratedWith")
            .write(directory, GraphRagFactory.RELATION_DICTIONARY);
    }
}
