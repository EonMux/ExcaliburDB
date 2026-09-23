package com.eonmux.excalibur.retrieval.build;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staged facts becoming a graph the read side can open.
 *
 * <p>This is the half of the system that did not exist: the edge store, the dictionaries and the
 * two Lucene indexes were all read by {@code GraphRagFactory} and produced by nothing, so
 * {@code /v1/graph/query} could only answer from artifacts built elsewhere.</p>
 */
@DisplayName("Graph directory build")
class GraphDirectoryBuilderTest {

    private static final double RAM_BUFFER_MB = 4.0;

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    /**
     * Builds the way the node does: through the one build path, publishing durably and opening
     * the published directory before the graph it replaced is let go.
     *
     * <p>The opener here opens the edge store, where the node opens its whole stack over the same
     * directory; that it opens at all is what the publish waits for.</p>
     */
    private static GraphDirectoryBuilder.BuildResult build(GraphStagingStore staging, Path target,
                                                           SupersessionPolicy supersession)
            throws IOException {
        GraphDirectoryBuilder.Published<Long> built = GraphDirectoryBuilder.build(staging, target,
            supersession, RAM_BUFFER_MB, null, 0, published -> {
                try (MappedEdgeStore store = MappedEdgeStore.open(published)) {
                    return store.edgeCount();
                }
            });
        if (built.result().published()) {
            assertThat(built.opened())
                .as("the opener saw the graph that was published")
                .isEqualTo(built.result().edges());
        } else {
            assertThat(built.opened()).as("nothing published, nothing opened").isNull();
        }
        return built.result();
    }

    private static void stage(GraphStagingStore staging) throws IOException {
        staging.append(ExtractedTriple.alwaysValid(
            "Ada Lovelace", "collaborated_with", "Charles Babbage", from(1)));
        staging.append(ExtractedTriple.alwaysValid(
            "Charles Babbage", "designed", "Analytical Engine", from(1)));
        staging.append(ExtractedTriple.alwaysValid(
            "Ada Lovelace", "wrote", "Note G", from(2)));

        staging.appendEntityText(staging.entityIdOf("Ada Lovelace"),
            "Ada Lovelace was a mathematician who wrote the first published algorithm.");
        staging.appendEntityText(staging.entityIdOf("Charles Babbage"),
            "Charles Babbage designed the Analytical Engine.");
    }

    @Test
    @DisplayName("a built directory holds everything the read side opens")
    void aBuiltDirectoryIsComplete(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);

            GraphDirectoryBuilder.BuildResult result = build(
                staging, target, SupersessionPolicy.NONE);

            assertThat(result.published()).isTrue();
            assertThat(result.edges()).isEqualTo(3);
            assertThat(result.entities()).isEqualTo(4);
            assertThat(result.relations()).isEqualTo(3);
        }

        // Exactly what GraphRagFactory.openAt looks for.
        assertThat(target.resolve("edgestore.meta")).exists();
        assertThat(target.resolve("edges_fwd.csr")).exists();
        assertThat(target.resolve("offsets_rev.idx")).exists();
        assertThat(target.resolve("entities.idx")).exists();
        assertThat(target.resolve("entities.dat")).exists();
        assertThat(target.resolve("relations.idx")).exists();
        assertThat(target.resolve("documents")).isDirectory();
        assertThat(target.resolve("labels")).isDirectory();
    }

    @Test
    @DisplayName("the built store holds the facts that were staged")
    void theStoreHoldsTheFacts(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        int ada;
        int babbage;
        int collaboratedWith;
        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            ada = staging.entityIdOf("Ada Lovelace");
            babbage = staging.entityIdOf("Charles Babbage");
            collaboratedWith = staging.relationIdOf("collaborated_with");
            build(staging, target, SupersessionPolicy.NONE);
        }

        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            assertThat(store.edgeCount()).isEqualTo(3);
            assertThat(store.neighbors(ada, null)).contains(babbage);

            // The reverse index is mandatory, and a reverse selector is how a caller asks for it:
            // an unfiltered walk is forward-only.
            assertThat(store.neighbors(babbage, new int[] {
                com.eonmux.excalibur.graph.store.RelationSelector.reverse(collaboratedWith) }))
                .contains(ada);
        }
    }

    @Test
    @DisplayName("entity ids survive a rebuild")
    void entityIdsSurviveARebuild(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");
        Path stagingDir = tempDir.resolve("staging");

        int adaBefore;
        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            stage(staging);
            adaBefore = staging.entityIdOf("Ada Lovelace");
            build(staging, target, SupersessionPolicy.NONE);
        }

        // Reopened staging, a further fact, and a second build. The Lucene indexes store entity
        // ids; renumbering them on a rebuild would point every one of those documents at a
        // different entity, silently, because an integer is always a valid integer.
        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            assertThat(staging.entityIdOf("Ada Lovelace")).isEqualTo(adaBefore);
            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "lived_in", "London", from(3)));

            GraphDirectoryBuilder.BuildResult result = build(
                staging, target, SupersessionPolicy.NONE);

            assertThat(result.edges()).isEqualTo(4);
            assertThat(staging.entityIdOf("Ada Lovelace")).isEqualTo(adaBefore);
        }
    }

    @Test
    @DisplayName("a build from nothing leaves the served graph alone")
    void anEmptyBuildLeavesTheGraphAlone(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            build(staging, target, SupersessionPolicy.NONE);
        }
        long edgesBefore;
        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            edgesBefore = store.edgeCount();
        }

        // A second staging directory with nothing in it. Replacing a served graph with an empty
        // one would turn every question into a refusal.
        try (GraphStagingStore empty = GraphStagingStore.open(tempDir.resolve("empty"))) {
            GraphDirectoryBuilder.BuildResult result =
                build(empty, target, SupersessionPolicy.NONE);
            assertThat(result.published()).isFalse();
        }

        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            assertThat(store.edgeCount()).isEqualTo(edgesBefore);
        }
    }

    @Test
    @DisplayName("a build leaves no scratch directory behind")
    void aBuildCleansUpAfterItself(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            build(staging, target, SupersessionPolicy.NONE);
        }

        assertThat(GraphBuilder.scratchFor(target)).doesNotExist();
        assertThat(target.resolveSibling(target.getFileName() + GraphBuilder.PREVIOUS_SUFFIX))
            .doesNotExist();
    }

    @Test
    @DisplayName("staged facts survive a restart of the staging store")
    void stagedFactsSurviveARestart(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("staging");

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            stage(staging);
            assertThat(staging.stagedCount()).isEqualTo(3);
        }

        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDir)) {
            assertThat(reopened.stagedCount()).isEqualTo(3);
            assertThat(reopened.entityCount()).isEqualTo(4);
            assertThat(reopened.relationCount()).isEqualTo(3);

            // And the facts themselves replay, not merely their count.
            List<String> replayed = new java.util.ArrayList<>();
            reopened.forEach((s, r, o, from, to, provenance) -> replayed.add(s + "-" + r + "->" + o));
            assertThat(replayed).hasSize(3);
        }
    }

    @Test
    @DisplayName("a supersedable relation closes its predecessor")
    void supersessionClosesThePreviousValue(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        int ada;
        int acme;
        int beta;
        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            staging.append(new ExtractedTriple("Ada Lovelace", "employer", "Acme",
                0, com.eonmux.excalibur.graph.temporal.TemporalFilter.OPEN, from(1)));
            staging.append(new ExtractedTriple("Ada Lovelace", "employer", "Beta",
                100, com.eonmux.excalibur.graph.temporal.TemporalFilter.OPEN, from(2)));
            ada = staging.entityIdOf("Ada Lovelace");
            acme = staging.entityIdOf("Acme");
            beta = staging.entityIdOf("Beta");

            SupersessionPolicy single = GraphBuilder.singleValued(staging, List.of("employer"));
            build(staging, target, single);
        }

        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            // Both edges are stored; the earlier one is closed rather than dropped, so a query
            // about the past still answers and a query about now returns one employer.
            assertThat(store.edgeCount()).isEqualTo(2);

            long[] now = store.expand(new long[] { ada }, 1, null,
                com.eonmux.excalibur.graph.temporal.TemporalFilter.asOf(
                    java.time.LocalDate.ofEpochDay(200)));
            assertThat(now)
                .as("after the second employer began, the first is closed")
                .contains(beta)
                .doesNotContain(acme);

            long[] before = store.expand(new long[] { ada }, 1, null,
                com.eonmux.excalibur.graph.temporal.TemporalFilter.asOf(
                    java.time.LocalDate.ofEpochDay(50)));
            assertThat(before)
                .as("before it began, the first still holds")
                .contains(acme)
                .doesNotContain(beta);
        }
    }

    @Test
    @DisplayName("a published graph that will not open is rolled back, and the previous one kept")
    void anUnopenableBuildKeepsThePreviousGraph(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
            stage(staging);
            build(staging, target, SupersessionPolicy.NONE);

            staging.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "lived_in", "London", from(3)));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> GraphDirectoryBuilder.build(
                    staging, target, SupersessionPolicy.NONE, RAM_BUFFER_MB, null, 0,
                    published -> {
                        throw new IOException("will not open");
                    }))
                .isInstanceOf(IOException.class)
                .hasMessage("will not open");
        }

        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            assertThat(store.edgeCount())
                .as("the graph from before the failed build is still the one served")
                .isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a corrupt tail costs only the facts after it")
    void aCorruptTailIsTolerated(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("staging");

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            stage(staging);
        }

        // A process killed mid-append leaves a partial record. The facts before it are still the
        // ids every existing artifact refers to, so replay keeps them rather than refusing.
        Path log = stagingDir.resolve(GraphStagingStore.TRIPLE_LOG);
        byte[] bytes = Files.readAllBytes(log);
        byte[] torn = new byte[bytes.length - 7];
        System.arraycopy(bytes, 0, torn, 0, torn.length);
        Files.write(log, torn);

        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDir)) {
            List<String> replayed = new java.util.ArrayList<>();
            reopened.forEach((s, r, o, from, to, provenance) -> replayed.add(s + "->" + o));
            assertThat(replayed).hasSize(2);
        }
    }

    @Test
    @DisplayName("facts staged after a torn tail are still readable")
    void appendsAfterATornTailAreNotLost(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("staging");

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            stage(staging);
        }

        // A process killed mid-append. Reopening must trim the partial record: appending after it
        // would put every later fact behind a record replay stops at, present on disk and
        // invisible to every build from then on.
        Path log = stagingDir.resolve(GraphStagingStore.TRIPLE_LOG);
        byte[] bytes = Files.readAllBytes(log);
        byte[] torn = new byte[bytes.length - 7];
        System.arraycopy(bytes, 0, torn, 0, torn.length);
        Files.write(log, torn);

        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDir)) {
            reopened.append(ExtractedTriple.alwaysValid(
                "Ada Lovelace", "lived_in", "London", from(9)));
        }

        try (GraphStagingStore reread = GraphStagingStore.open(stagingDir)) {
            List<String> replayed = new java.util.ArrayList<>();
            reread.forEach((s, r, o, from, to, provenance) -> replayed.add(s + "->" + o));
            assertThat(replayed)
                .as("the two intact facts plus the one appended after the trim")
                .hasSize(3);
        }
    }

    @Test
    @DisplayName("a label interned after a torn tail keeps its id across a restart")
    void entityIdsSurviveATornLabelFile(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("staging");

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir)) {
            stage(staging);
        }

        // Half a label record at the end, as a process killed while interning a new label leaves.
        // Its id was never handed out - a label is forced before its id is returned - so nothing
        // staged names it, and the open may cut it off.
        Path labels = stagingDir.resolve(GraphStagingStore.ENTITY_LABELS);
        Files.write(labels, new byte[] {0, 0, 0, 40, 'P', 'a', 'r'},
            java.nio.file.StandardOpenOption.APPEND);

        int londonFirst;
        try (GraphStagingStore reopened = GraphStagingStore.open(stagingDir)) {
            londonFirst = reopened.entityIdOf("London");
        }

        // The discriminator: intern a different label first on the next open. If London's
        // assignment was appended behind an un-trimmed torn record, replay cannot see it, Turin
        // takes the id London was given, and London is handed a different one - an entity id that
        // moved, which every artifact keyed by it now resolves wrongly.
        try (GraphStagingStore reread = GraphStagingStore.open(stagingDir)) {
            int turin = reread.entityIdOf("Turin");
            assertThat(turin).isNotEqualTo(londonFirst);
            assertThat(reread.entityIdOf("London"))
                .as("the id London was given survives another restart")
                .isEqualTo(londonFirst);
        }
    }
}
