package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An erasure is discharged only once no copy of what it covers is left beside the graph: the
 * scratch a crashed build or compaction left, and a displaced copy a publish set aside, can each
 * hold what the erasure removed from staging and from the served graph.
 */
@DisplayName("Erasure leftovers")
class ErasureLeftoverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC);

    private static final CompactionPolicy ALWAYS = (stats, now) -> true;

    private static final String ERASED = "Alice lives at 14 Elm Street";

    private static ExtractedTriple fact(String subject, String object, long source) {
        return new ExtractedTriple(subject, "knows", object, Integer.MIN_VALUE,
            TemporalFilter.OPEN, new Provenance(SourceType.DOCUMENT, source, 90, 1));
    }

    /** Stages alice and bob, and serves an edge store built from them. */
    private static int stageAndServe(GraphStagingStore staging, Path graph) throws IOException {
        int alice = staging.entityIdOf("alice");
        staging.append(fact("alice", "bob", 555));
        staging.appendEntityText(alice, ERASED, new Provenance(SourceType.DOCUMENT, 555, 90, 1));
        staging.flush();
        EdgeStoreWriter writer = new EdgeStoreWriter();
        staging.forEach((subject, relation, object, from, to, provenance) ->
            writer.add(subject, relation, object, from, to, provenance));
        writer.build(graph, staging.entityCount());
        return alice;
    }

    /** A directory beside the graph holding a copy of the erased text, as a crash leaves one. */
    private static Path leftover(Path graph, String suffix) throws IOException {
        Path directory = Files.createDirectories(
            graph.resolveSibling(graph.getFileName() + suffix));
        Files.writeString(Files.createDirectories(directory.resolve("documents"))
            .resolve("_0.cfs"), ERASED);
        return directory;
    }

    @Test
    @DisplayName("a compaction that rewrites the graph removes a crashed build's scratch before "
        + "the request is discharged")
    void aRewriteRemovesTheBuildScratch(@TempDir Path dir) throws IOException {
        Path graph = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            int alice = stageAndServe(staging, graph);
            Path building = leftover(graph, GraphBuilder.BUILD_SUFFIX);
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
            coordinator.submit(new ErasureScope.Entity(alice));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(graph);

            assertThat(outcome.ran()).isTrue();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(building).doesNotExist();
        }
    }

    @Test
    @DisplayName("a compaction with nothing to rewrite still removes every leftover copy before "
        + "it discharges")
    void aPassWithNoRewriteRemovesLeftovers(@TempDir Path dir) throws IOException {
        Path graph = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            stageAndServe(staging, graph);
            Path building = leftover(graph, GraphBuilder.BUILD_SUFFIX);
            Path compacting = leftover(graph, ".compacting");
            Path previous = leftover(graph, GraphBuilder.PREVIOUS_SUFFIX);
            Path retired = leftover(graph, GraphBuilder.RETIRED_SUFFIX);
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
            // A document nothing was extracted from: the served graph has nothing to rewrite.
            coordinator.submit(new ErasureScope.Source(SourceType.DOCUMENT, 999));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(graph);

            assertThat(outcome.ran()).isFalse();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(building).doesNotExist();
            assertThat(compacting).doesNotExist();
            assertThat(previous).doesNotExist();
            assertThat(retired).doesNotExist();
            assertThat(graph).isDirectory();
        }
    }

    @Test
    @DisplayName("a pass that discharges nothing leaves the directories beside the graph alone")
    void nothingDischargedTouchesNothing(@TempDir Path dir) throws IOException {
        Path graph = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            stageAndServe(staging, graph);
            // The node reads a compaction's scratch as a sign of an interrupted erasure, and only
            // an erasure that is applying what is owed may take that sign away.
            Path compacting = leftover(graph, ".compacting");
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);

            coordinator.compactNow(graph);

            assertThat(compacting).isDirectory();
        }
    }

    @Test
    @DisplayName("a namespace with no graph still has a crashed first build's scratch removed")
    void erasingStagingAloneRemovesTheBuildScratch(@TempDir Path dir) throws IOException {
        Path graph = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            int alice = staging.entityIdOf("alice");
            staging.append(fact("alice", "bob", 555));
            staging.flush();
            Path building = leftover(graph, GraphBuilder.BUILD_SUFFIX);
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
            coordinator.submit(new ErasureScope.Entity(alice));

            GraphErasureCoordinator.CompactionOutcome outcome =
                coordinator.eraseStagedOnly(graph, false);

            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(building).doesNotExist();
            assertThat(staging.stagedCount()).isZero();
        }
    }

    @Test
    @DisplayName("a graph set aside with nothing served in its place is not discharged over")
    void aDisplacedGraphIsNotDischargedOver(@TempDir Path dir) throws IOException {
        Path graph = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("graph-staging"));
             ErasureLog log = ErasureLog.open(dir.resolve("erasure.log"))) {
            int alice = staging.entityIdOf("alice");
            staging.append(fact("alice", "bob", 555));
            staging.flush();
            Path previous = leftover(graph, GraphBuilder.PREVIOUS_SUFFIX);
            GraphErasureCoordinator coordinator =
                new GraphErasureCoordinator(log, ALWAYS, CLOCK, staging);
            coordinator.submit(new ErasureScope.Entity(alice));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> coordinator.eraseStagedOnly(graph, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("set aside");
            assertThat(coordinator.outstandingCount()).isEqualTo(1);
            assertThat(staging.stagedCount()).as("found before staging was touched").isEqualTo(1);
            assertThat(previous).as("recovery restores it; it is not deleted").isDirectory();
        }
    }
}
