package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.endpoint.GraphAutoBuilder;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.AssertedFact;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A build never publishes what an outstanding erasure obligation covers.
 *
 * <p>An obligation is applied by a compaction, which the erasure policy may not call due for an
 * hour, and until then staging still holds everything it covers. Builds read staging - and now
 * run by themselves - so without this a retracted fact or an erased person came back into the
 * served graph at the next build after they had been removed from it, with the obligation still
 * reported pending. These drive the build paths a node has: the explicit one, the automatic one,
 * one overtaken by an erasure, and one after a restart.</p>
 */
@DisplayName("Builds and outstanding erasures")
class GraphBuildErasureTest {

    /** A term of the erased person's name, as the segment files hold it. */
    private static final String NAME_TERM = "quazzlethorpe";

    /** A term of prose about the erased person, and of nothing else. */
    private static final String PROSE_TERM = "zymurgic";

    /** A term of prose nobody asked to erase. */
    private static final String KEPT_TERM = "thaumaturgy";

    private static final BooleanSupplier NEVER = () -> false;

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static GraphTenant open(Path directory) throws IOException {
        return GraphTenant.open(NamespaceId.SHARED, directory, config(),
            GraphModelClients.forConfig(config()));
    }

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    /** Whether any file at or under {@code root} holds {@code text} as bytes, in any ASCII case. */
    private static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                byte[] haystack = Files.readAllBytes(path);
                outer:
                for (int i = 0; i + needle.length <= haystack.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (Character.toLowerCase(haystack[i + j]) != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the graph published at {@code directory} holds an edge from one label to another. */
    private static boolean served(Path directory, GraphStagingStore staging, String from,
                                  String to) throws IOException {
        int subject = staging.findEntity(from);
        int object = staging.findEntity(to);
        if (!Files.isDirectory(directory) || subject < 0 || object < 0) {
            return false;
        }
        try (MappedEdgeStore store = MappedEdgeStore.open(directory)) {
            for (long reached : store.expand(new long[] { subject }, 1, null,
                    TemporalFilter.ALL)) {
                if (reached == object) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Asserts two facts by hand, as {@code POST /v1/graph/facts} does, and returns their ids. */
    private static List<Long> assertTwo(GraphTenant tenant) throws IOException {
        return tenant.staging().assertFacts(List.of(
            AssertedFact.always("Ada Lovelace", "worked_with", "Charles Babbage"),
            AssertedFact.always("Grace Hopper", "worked_with", "Howard Aiken")));
    }

    /** Retracts an asserted fact as {@code DELETE /v1/graph/facts} does: nothing compacted. */
    private static long retract(GraphTenant tenant, long factId) throws IOException {
        return tenant.erasureService()
            .submit(new ErasureScope.Source(SourceType.USER_ASSERTED, factId));
    }

    /** Stages a person with prose of their own, and somebody else with theirs. */
    private static void stagePerson(GraphStagingStore staging) throws IOException {
        int alice = staging.entityIdOf("Alice Quazzlethorpe");
        int bob = staging.entityIdOf("Bob Ordinary");
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", document(42)));
        staging.append(ExtractedTriple.alwaysValid(
            "Bob Ordinary", "knows", "Carol Plain", document(77)));
        staging.appendEntityText(alice,
            "Alice Quazzlethorpe was treated for zymurgic fatigue", document(42));
        staging.appendEntityText(bob, "Bob Ordinary lectures on thaumaturgy", document(77));
        staging.flush();
    }

    /** The id the erased person was staged under. */
    private static int alice(GraphTenant tenant) {
        return tenant.staging().findEntity("Alice Quazzlethorpe");
    }

    private static ErasureLog.State stateOf(GraphTenant tenant, long requestId) {
        return tenant.erasureService().status(requestId).orElseThrow().state();
    }

    @Test
    @DisplayName("an asserted fact retracted and not yet compacted is not republished by a build")
    void aRetractedFactIsNotRebuilt(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            List<Long> facts = assertTwo(tenant);
            assertThat(tenant.build(NEVER).published()).isTrue();
            assertThat(served(directory, tenant.staging(), "Ada Lovelace", "Charles Babbage"))
                .isTrue();

            long request = retract(tenant, facts.get(0));
            GraphBuildService.BuildOutcome rebuilt = tenant.build(NEVER);

            assertThat(rebuilt.published()).isTrue();
            assertThat(rebuilt.withheld()).isEqualTo(1);
            assertThat(served(directory, tenant.staging(), "Ada Lovelace", "Charles Babbage"))
                .as("the retracted fact is not back in the served graph")
                .isFalse();
            assertThat(served(directory, tenant.staging(), "Grace Hopper", "Howard Aiken"))
                .as("the one nobody retracted is")
                .isTrue();
            assertThat(stateOf(tenant, request))
                .as("staging still holds the fact, so the retraction is still owed")
                .isEqualTo(ErasureLog.State.PENDING);
            assertThat(tenant.staging().assertions()).hasSize(2);
        }
    }

    @Test
    @DisplayName("a person whose erasure is pending is in no artifact a build publishes")
    void aPendingEntityErasureIsNotRebuilt(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        long request;
        try (GraphTenant tenant = open(directory)) {
            stagePerson(tenant.staging());
            request = tenant.erasureService()
                .submit(new ErasureScope.Entity(alice(tenant)));

            GraphBuildService.BuildOutcome built = tenant.build(NEVER);

            assertThat(built.published()).isTrue();
            assertThat(built.withheld()).isEqualTo(1);
            assertThat(served(directory, tenant.staging(), "Bob Ordinary", "Carol Plain"))
                .isTrue();
            assertThat(stateOf(tenant, request)).isEqualTo(ErasureLog.State.PENDING);
            assertThat(tenant.erasureService().outstandingCount()).isEqualTo(1);
        }
        assertThat(anyFileContains(directory, NAME_TERM))
            .as("the name, in the dictionary and the label index").isFalse();
        assertThat(anyFileContains(directory, PROSE_TERM))
            .as("the prose, in the entity documents").isFalse();
        assertThat(anyFileContains(directory, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("a withholding build leaves the obligation to the compaction, which discharges it")
    void theCompactionStillDischarges(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            stagePerson(tenant.staging());
            long request = tenant.erasureService()
                .submit(new ErasureScope.Entity(alice(tenant)));
            tenant.build(NEVER);
            assertThat(anyFileContains(tenant.staging().directory(), NAME_TERM))
                .as("the build did not erase staging")
                .isTrue();

            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(outcome.requestsWithheld()).isZero();
            assertThat(stateOf(tenant, request)).isEqualTo(ErasureLog.State.DISCHARGED);
            assertThat(anyFileContains(tenant.staging().directory(), NAME_TERM)).isFalse();
            assertThat(tenant.holder().isPresent()).isTrue();
            assertThat(tenant.freshness().behind())
                .as("the published graph already lacked what the compaction erased")
                .isFalse();
        }
        assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
        assertThat(anyFileContains(directory, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("a build that withholds every staged fact retires the graph that held them")
    void withholdingEverythingRetiresTheGraph(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            List<Long> facts = assertTwo(tenant);
            tenant.build(NEVER);
            retract(tenant, facts.get(0));
            retract(tenant, facts.get(1));

            GraphBuildService.BuildOutcome outcome = tenant.build(NEVER);

            assertThat(outcome.published()).isFalse();
            assertThat(outcome.withheld()).isEqualTo(2);
            assertThat(outcome.retired())
                .as("the graph served before held only facts that are now owed to an erasure")
                .isTrue();
            assertThat(tenant.holder().isPresent()).isFalse();
        }
        assertThat(directory).doesNotExist();
    }

    @Test
    @DisplayName("an erasure recorded while a build runs stops that build publishing")
    void anErasureDuringTheBuildStopsItsPublish(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        Config config = config();
        GraphModelClients models = GraphModelClients.forConfig(config);
        try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("staging"));
             ErasureLog log = ErasureLog.open(root.resolve("staging").resolve("erasure.log"));
             GraphRagHolder holder = new GraphRagHolder(directory, null)) {
            staging.append(ExtractedTriple.alwaysValid(
                "Bob Ordinary", "knows", "Carol Plain", document(77)));
            new GraphBuildService(staging, holder, config, models, List.of(), null, 0, log::fence)
                .build();
            stagePerson(staging);
            int alice = staging.findEntity("Alice Quazzlethorpe");

            // The embedder runs between reading staging and publishing, which is where an
            // erasure accepted on a request thread lands against a long build.
            AtomicBoolean erased = new AtomicBoolean();
            GraphBuildService overtaken = new GraphBuildService(staging, holder, config, models,
                List.of(), texts -> {
                    if (erased.compareAndSet(false, true)) {
                        try {
                            log.submit(new ErasureScope.Entity(alice), Instant.now());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                    return texts.stream().map(text -> new float[] { 1, 0, 0, 0 }).toList();
                }, 4, log::fence);

            assertThatThrownBy(overtaken::build).isInstanceOf(PublishRefusedException.class);

            assertThat(erased).isTrue();
            assertThat(holder.isPresent())
                .as("the graph served before the build still is").isTrue();
            assertThat(GraphBuilder.scratchFor(directory))
                .as("the assembly that read Alice's facts is gone").doesNotExist();
            assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
            assertThat(staging.unpublishedChanges().pending())
                .as("nothing was published, so the graph is still behind its staging")
                .isTrue();

            GraphBuildService.BuildOutcome retried =
                new GraphBuildService(staging, holder, config, models, List.of(), null, 0,
                    log::fence).build();

            assertThat(retried.published()).isTrue();
            assertThat(retried.withheld()).isEqualTo(1);
            assertThat(staging.unpublishedChanges().pending()).isFalse();
        }
        assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
        assertThat(anyFileContains(directory, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("the automatic build publishes a graph without what is owed, and calls it current")
    void theAutomaticBuildWithholds(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            List<Long> facts = assertTwo(tenant);
            long request = retract(tenant, facts.get(0));
            assertThat(tenant.freshness().behind()).isTrue();

            GraphAutoBuilder auto = new GraphAutoBuilder(
                new GraphAutoBuilder.Settings(true, Duration.ofSeconds(30), Duration.ZERO,
                    Duration.ofSeconds(600), 1, Duration.ofSeconds(60), Duration.ofSeconds(60)),
                single(tenant), () -> Optional.of(GraphExtraction.ACTIVE), id -> false,
                Clock.systemUTC());
            try {
                assertThat(auto.runPass()).containsExactly(NamespaceId.SHARED);
                GraphAutoBuilder.Memory memory = awaitFinished(auto, NamespaceId.SHARED);
                assertThat(memory.lastResult()).isEqualTo(GraphAutoBuilder.RESULT_COMPLETED);
                assertThat(memory.lastOutcome().withheld()).isEqualTo(1);
            } finally {
                auto.close();
            }

            assertThat(served(directory, tenant.staging(), "Ada Lovelace", "Charles Babbage"))
                .isFalse();
            assertThat(served(directory, tenant.staging(), "Grace Hopper", "Howard Aiken"))
                .isTrue();
            assertThat(tenant.freshness().behind())
                .as("the graph holds everything staging holds that is not owed to an erasure")
                .isFalse();
            assertThat(stateOf(tenant, request)).isEqualTo(ErasureLog.State.PENDING);
        }
    }

    @Test
    @DisplayName("an obligation recorded before a restart is withheld by the first build after it")
    void anObligationSurvivesARestart(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        long request;
        try (GraphTenant tenant = open(directory)) {
            List<Long> facts = assertTwo(tenant);
            tenant.build(NEVER);
            request = retract(tenant, facts.get(0));
        }

        try (GraphTenant reopened = open(directory)) {
            GraphBuildService.BuildOutcome rebuilt = reopened.build(NEVER);

            assertThat(rebuilt.withheld()).isEqualTo(1);
            assertThat(served(directory, reopened.staging(), "Ada Lovelace", "Charles Babbage"))
                .isFalse();
            assertThat(stateOf(reopened, request)).isEqualTo(ErasureLog.State.PENDING);
        }
    }

    /** Waits for the automatic build of a namespace to finish and returns what it remembers. */
    private static GraphAutoBuilder.Memory awaitFinished(GraphAutoBuilder auto, NamespaceId id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        GraphAutoBuilder.Memory memory = auto.status(id).memory();
        while ((memory.running() || memory.lastResult() == null)
                && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
            memory = auto.status(id).memory();
        }
        assertThat(memory.running()).as("the automatic build finished").isFalse();
        return memory;
    }

    /** The automatic build's view of one open tenant. */
    private static GraphAutoBuilder.Namespaces single(GraphTenant tenant) {
        return new GraphAutoBuilder.Namespaces() {
            @Override
            public List<NamespaceId> list() {
                return List.of(tenant.id());
            }

            @Override
            public GraphFreshness freshness(NamespaceId id) {
                return tenant.freshness();
            }

            @Override
            public Optional<GraphBuildService.BuildOutcome> buildIfBehind(
                    NamespaceId id, BooleanSupplier cancelRequested) throws IOException {
                return tenant.buildIfBehind(cancelRequested);
            }
        };
    }
}
