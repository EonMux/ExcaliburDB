package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.GraphErasureCoordinator;
import com.eonmux.excalibur.graph.erasure.ServedArtifactEraser;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An erasure a tenant accepts has to reach the bytes that tenant is serving.
 *
 * <p>Everything below this exists and did nothing, because nothing supplied the node's
 * {@code ServedArtifactEraser}s: the compaction rewrote the edge store, reported the two Lucene
 * indexes beside it as unreached, and left every request outstanding. The node was telling the
 * truth - the name and the prose really were still being served - and the obligation could never
 * be discharged. These tests are about the wiring that closes that.
 */
@DisplayName("Graph tenant erasure")
class GraphTenantErasureTest {

    /** A term of the erased person's name, as the segment files hold it. */
    private static final String NAME_TERM = "quazzlethorpe";

    /** A term of the prose copied out of the withdrawn document, and of nothing else. */
    private static final String WITHDRAWN_TERM = "zymurgic";

    /** A term of a document nobody withdrew. */
    private static final String KEPT_TERM = "thaumaturgy";

    /** The withdrawn document's provenance id. */
    private static final long WITHDRAWN = 42;

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /** Whether any file at or under {@code root} holds {@code text} as bytes. */
    private static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                byte[] haystack = Files.readAllBytes(path);
                outer:
                for (int i = 0; i + needle.length <= haystack.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (haystack[i + j] != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Stages two documents' facts and prose and builds the graph the tenant serves. */
    private static void stageAndBuild(GraphTenant tenant) throws IOException {
        GraphStagingStore staging = tenant.staging();
        int alice = staging.entityIdOf("Alice Quazzlethorpe");
        int bob = staging.entityIdOf("Bob Ordinary");
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", from(WITHDRAWN)));
        staging.appendEntityText(alice,
            "Alice Quazzlethorpe was treated for zymurgic fatigue", from(WITHDRAWN));
        staging.appendEntityText(bob, "Bob Ordinary lectures on thaumaturgy", from(77));
        staging.flush();

        assertThat(tenant.buildService().build().published()).isTrue();
    }

    @Test
    @DisplayName("erasing a person takes their name and prose out of the served directory")
    void anEntityErasureReachesTheServedIndexes(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);
            assertThat(anyFileContains(directory, NAME_TERM))
                .as("the name starts out in the served indexes")
                .isTrue();

            tenant.erasureService()
                .submit(new ErasureScope.Entity(tenant.staging().entityIdOf("Alice "
                    + "Quazzlethorpe")));
            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.requestsWithheld())
                .as("every artifact in the directory is now claimed by something")
                .isZero();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(tenant.erasureService().outstandingCount()).isZero();
        }

        assertThat(anyFileContains(directory, NAME_TERM))
            .as("the name has to stop existing, not stop being served")
            .isFalse();
        assertThat(anyFileContains(directory, WITHDRAWN_TERM))
            .as("and so does the prose the snippets were copied out of")
            .isFalse();
        assertThat(anyFileContains(directory, KEPT_TERM))
            .as("nobody else's text moves")
            .isTrue();
    }

    @Test
    @DisplayName("withdrawing a document takes the prose it contributed, and only that")
    void aSourceErasureReachesTheDerivedProse(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);

            tenant.erasureService()
                .submit(new ErasureScope.Source(SourceType.DOCUMENT, WITHDRAWN));
            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.requestsWithheld()).isZero();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
        }

        assertThat(anyFileContains(directory, WITHDRAWN_TERM))
            .as("the withdrawn document's prose is what the request covered")
            .isFalse();
        assertThat(anyFileContains(directory, NAME_TERM))
            .as("and the person it mentioned keeps their name: they did not ask to be forgotten")
            .isTrue();
        assertThat(anyFileContains(directory, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("the graph is still queryable after an erasure has rewritten it")
    void theStackReopensOverTheRewrittenDirectory(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);

            tenant.erasureService()
                .submit(new ErasureScope.Entity(tenant.staging().entityIdOf("Alice "
                    + "Quazzlethorpe")));
            tenant.erasureService().compactNow();

            assertThat(tenant.holder().isPresent())
                .as("a node serving a graph before an erasure serves one after it")
                .isTrue();
        }
    }

    /**
     * Opens a tenant that has built a graph and accepted an erasure it has not yet applied, then
     * closes it, leaving the disk as a crash in the middle of applying it would.
     */
    private static void buildAndOweErasure(Path directory) throws IOException {
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);
            tenant.erasureService()
                .submit(new ErasureScope.Entity(tenant.staging().entityIdOf("Alice "
                    + "Quazzlethorpe")));
        }
    }

    @Test
    @DisplayName("an obligation on disk naming an id nothing was given is discharged as the "
        + "tenant opens, before that id can be handed to anybody")
    void anObligationForAnUnassignedIdIsDischargedOnOpen(@TempDir Path root) throws IOException {
        // A log written before the node refused such requests. Left in place, the request
        // would erase whichever entity the interner gives id 42 to - somebody nobody asked about.
        Path directory = root.resolve("graph");
        int alice;
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            alice = tenant.staging().entityIdOf("Alice Quazzlethorpe");
        }
        Path erasures = GraphTenant.erasureLogOf(directory);
        try (ErasureLog log = ErasureLog.open(erasures)) {
            log.submit(new ErasureScope.Entity(alice), java.time.Instant.EPOCH);
            log.submit(new ErasureScope.Entity(42), java.time.Instant.EPOCH);
        }

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount())
                .as("the request for an entity that exists is still owed")
                .isEqualTo(1);
        }
        assertThat(ErasureLog.entitiesOwed(erasures))
            .as("and the discharge is on disk")
            .containsExactly((long) alice);
    }

    @Test
    @DisplayName("an obligation for an id only the published graph names survives the open")
    void anObligationThePublishedGraphHoldsSurvivesTheOpen(@TempDir Path root)
            throws IOException {
        // Staging started afresh beside a graph built from an earlier one: the graph still serves
        // the entity, so the obligation is real and must be applied, not discharged.
        Path directory = root.resolve("graph");
        int bob;
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);
            bob = tenant.staging().entityIdOf("Bob Ordinary");
        }
        Path staging = GraphTenant.stagingDirectoryOf(directory);
        try (var files = Files.list(staging)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        try (ErasureLog log = ErasureLog.open(GraphTenant.erasureLogOf(directory))) {
            log.submit(new ErasureScope.Entity(bob), java.time.Instant.EPOCH);
        }

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.staging().entityCount()).isZero();
            assertThat(tenant.erasureService().outstandingCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a graph restored after a crash between a swap's renames is erased before it "
        + "is served")
    void aRestoredGraphIsErasedBeforeItIsServed(@TempDir Path root) throws IOException {
        // A compaction publishes by renaming the served graph aside and its replacement into
        // place. Dying between the two leaves the pre-erasure graph set aside and the obligation
        // outstanding, and recovery restores that graph - the one still holding the name.
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);
        Files.move(directory, com.eonmux.excalibur.graph.staging.GraphBuilder
            .previousFor(directory));

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount())
                .as("the obligation is applied as the tenant opens")
                .isZero();
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(anyFileContains(directory, NAME_TERM))
            .as("the restored graph never reached a query with the name in it")
            .isFalse();
        assertThat(com.eonmux.excalibur.graph.staging.GraphBuilder.previousFor(directory))
            .doesNotExist();
    }

    @Test
    @DisplayName("a compaction's leftover scratch directory means its erasure is finished first")
    void anInterruptedCompactionIsFinishedBeforeServing(@TempDir Path root) throws IOException {
        // A crash before the first rename leaves the graph where it was and the replacement
        // half-assembled beside it. Staging may already be erased, so this graph is the one
        // place the erased facts are still readable, and serving it would publish them.
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);
        Files.createDirectories(com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor
            .scratchFor(directory));

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount()).isZero();
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
        assertThat(com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor.scratchFor(directory))
            .doesNotExist();
    }

    @Test
    @DisplayName("a graph an earlier attempt could not finish erasing is still withheld on reopen")
    void aFailedAttemptIsRememberedAcrossOpens(@TempDir Path root) throws IOException {
        // The first attempt consumes the signs of the crash - recovery restored the graph, and
        // a failed compaction clears its own scratch - so only the marker it wrote remains.
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);
        Path marker = GraphTenant.stagingDirectoryOf(directory)
            .resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER);
        Files.createFile(marker);

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount()).isZero();
            assertThat(tenant.holder().isPresent()).isTrue();
            assertThat(tenant.erasureService().servingWithheld()).isFalse();
        }
        assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("a marker with nothing left owed is removed and the graph served as it is")
    void aStaleMarkerIsRemoved(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            stageAndBuild(tenant);
        }
        Path marker = GraphTenant.stagingDirectoryOf(directory)
            .resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER);
        Files.createFile(marker);

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("an obligation merely waiting on the policy does not delay the tenant opening")
    void anOrdinaryOpenLeavesThePolicyInCharge(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount()).isEqualTo(1);
            assertThat(tenant.holder().isPresent()).isTrue();
        }
    }

    /** What a process dying in the middle of a compaction looks like from inside it. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("the process died here");
        }
    }

    /**
     * An eraser that stops the compaction in the gap between erasing staging and assembling the
     * rewritten graph, and notes what was on disk when it did.
     *
     * <p>It claims the served document index so the compaction asks it whether that is reached,
     * which it does after staging is erased and before the scratch directory exists - the one
     * window in which nothing a compaction leaves behind says the graph on disk is now the last
     * copy of what was erased.</p>
     */
    private static final class StopsAfterStaging implements ServedArtifactEraser {

        private final Path marker;
        private final java.util.function.Supplier<? extends RuntimeException> failure;
        private final boolean crash;
        private boolean markerOnDisk;
        private boolean reached;

        StopsAfterStaging(Path marker, boolean crash,
                          java.util.function.Supplier<? extends RuntimeException> failure) {
            this.marker = marker;
            this.crash = crash;
            this.failure = failure;
        }

        @Override
        public java.util.Set<String> names() {
            return java.util.Set.of(GraphRagFactory.DOCUMENT_INDEX);
        }

        @Override
        public boolean reaches(ErasureScope scope) {
            reached = true;
            markerOnDisk = Files.exists(marker);
            if (crash) {
                throw new SimulatedCrash();
            }
            throw failure.get();
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased) {
            throw new AssertionError("the compaction was to stop before rewriting anything");
        }
    }

    /**
     * Runs one compaction of the graph at {@code directory} through {@code eraser}, as the
     * tenant would but with the eraser in the chain, and reports how it ended.
     */
    private interface Compaction {
        void run(GraphErasureService service, GraphRagHolder holder) throws IOException;
    }

    private static void compactThrough(Path directory, ServedArtifactEraser eraser,
                                       Compaction compaction) throws IOException {
        Config config = config();
        GraphModelClients models = GraphModelClients.forConfig(config);
        Path stagingDirectory = GraphTenant.stagingDirectoryOf(directory);
        try (GraphStagingStore staging = GraphStagingStore.open(stagingDirectory);
             ErasureLog log = ErasureLog.open(GraphTenant.erasureLogOf(directory));
             GraphRagHolder holder = new GraphRagHolder(directory,
                 GraphRagFactory.openAt(config, directory, models))) {
            GraphErasureService service = new GraphErasureService(
                new GraphErasureCoordinator(log,
                    CompactionPolicy.standard(0.2, Duration.ofSeconds(3600), Integer.MAX_VALUE),
                    Clock.systemUTC(), staging, List.of(eraser)),
                holder, config, models,
                stagingDirectory.resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER));
            compaction.run(service, holder);
        }
    }

    @Test
    @DisplayName("a crash after staging is erased leaves the graph withheld and the erasure "
        + "applied on the next open")
    void aCrashAfterStagingIsErasedIsRecovered(@TempDir Path root) throws IOException {
        // Staging goes first, and it is the other copy of what is being erased. Dying before the
        // scratch directory exists leaves the graph on disk as the only copy, and with nothing
        // but the marker to say so the next open would serve it - with the erasure still owed and
        // nothing left in staging for the retry to find.
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);
        Path marker = GraphTenant.stagingDirectoryOf(directory)
            .resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER);
        StopsAfterStaging crash = new StopsAfterStaging(marker, true, null);

        compactThrough(directory, crash, (service, holder) ->
            org.assertj.core.api.Assertions.assertThatThrownBy(service::compactNow)
                .isInstanceOf(SimulatedCrash.class));

        assertThat(crash.reached).as("the compaction got past erasing staging").isTrue();
        assertThat(crash.markerOnDisk)
            .as("the marker is durable before the first byte of staging is erased")
            .isTrue();
        assertThat(EdgeStoreCompactor.scratchFor(directory))
            .as("no scratch directory is left to speak for the graph instead")
            .doesNotExist();

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount())
                .as("the obligation is applied as the tenant opens")
                .isZero();
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(anyFileContains(directory, NAME_TERM))
            .as("the graph served after the restart no longer holds the name")
            .isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("a compaction that fails after staging is erased withholds the graph rather "
        + "than serving it again")
    void aFailureAfterStagingIsErasedWithholds(@TempDir Path root) throws IOException {
        // The graph being served before the attempt is no reason to serve it after: staging no
        // longer holds the erased facts, so the graph may be the one place they still are.
        Path directory = root.resolve("graph");
        buildAndOweErasure(directory);
        Path marker = GraphTenant.stagingDirectoryOf(directory)
            .resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER);
        StopsAfterStaging failing = new StopsAfterStaging(marker, false,
            () -> new IllegalStateException("eraser failed"));

        compactThrough(directory, failing, (service, holder) -> {
            org.assertj.core.api.Assertions.assertThatThrownBy(service::compactNow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eraser failed");

            assertThat(holder.isPresent())
                .as("a graph that may be the last copy of what was erased is not served")
                .isFalse();
            assertThat(service.servingWithheld()).isTrue();
            assertThat(service.outstandingCount()).isEqualTo(1);
        });

        assertThat(failing.markerOnDisk).isTrue();
        assertThat(marker)
            .as("written down, so an eviction or a restart does not forget it")
            .exists();

        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config(),
                GraphModelClients.forConfig(config()))) {
            assertThat(tenant.erasureService().outstandingCount()).isZero();
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(anyFileContains(directory, NAME_TERM)).isFalse();
        assertThat(marker).doesNotExist();
    }
}

