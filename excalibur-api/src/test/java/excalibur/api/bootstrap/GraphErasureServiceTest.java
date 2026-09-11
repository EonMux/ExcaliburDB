package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.erasure.CompactionPolicy;
import io.excalibur.graph.erasure.ErasureLog;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.erasure.GraphErasureCoordinator;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.retrieval.pipeline.LexicalEntityLinker;

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
            GraphRagPipeline before = holder.pipeline().orElseThrow();

            GraphErasureService.ErasureOutcome outcome = service.compactIfDue();

            assertThat(outcome.ran()).isFalse();
            assertThat(holder.pipeline().orElseThrow())
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
            GraphRagPipeline before = holder.pipeline().orElseThrow();

            service.compactNow();

            assertThat(holder.pipeline().orElseThrow()).isNotSameAs(before);
        }
    }

    @Nested
    @DisplayName("When an obligation is outstanding")
    class Owed {

        @Test
        @DisplayName("the served graph is closed around the attempt, because one may rewrite it")
        void closesAroundTheAttempt(@TempDir Path root) throws IOException {
            // Past its deadline, so the policy says run; and naming an entity the store does not
            // hold, so the attempt finds nothing to rewrite and the reopened stack is the one
            // this test started with. What is being checked is that the close happened at all.
            GraphErasureService service = serviceOver(root, Duration.ZERO);
            GraphRagPipeline before = holder.pipeline().orElseThrow();
            service.submit(new ErasureScope.Entity(UNKNOWN_ENTITY));

            service.compactIfDue();

            assertThat(holder.pipeline().orElseThrow()).isNotSameAs(before);
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
            holder, config);
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
