package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.ingest.graph.LlmRelationExtractor;
import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.ingest.graph.StagingGraphIngestSink;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a namespace's served graph is behind its staging, and the build that catches it up.
 *
 * <p>What the automatic build decides from. A graph read as current when it is not goes on
 * answering with a replaced document's facts; one read as behind when it is not is rebuilt -
 * a whole graph's embeddings - on every pass for nothing.</p>
 */
@DisplayName("Graph freshness")
class GraphTenantFreshnessTest {

    private static final String QUESTION = "Who did Ada Lovelace work with?";
    private static final NamespaceId ACME = NamespaceId.of("acme");

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    /**
     * Stands in for the chat model: Ada worked with whoever the document names, attributed to
     * the document as the real extractor attributes it, so a withdrawal of it takes the fact.
     */
    private static RelationExtractor collaborator() {
        return (documentKey, text) -> List.of(ExtractedTriple.alwaysValid("Ada Lovelace",
            "collaborated_with", text.contains("Menabrea") ? "Luigi Menabrea" : "Charles Babbage",
            Provenance.of(SourceType.DOCUMENT, LlmRelationExtractor.sourceIdOf(documentKey),
                0.9, 1)));
    }

    private static GraphTenant open(Path directory) throws IOException {
        return GraphTenant.open(NamespaceId.SHARED, directory, config(),
            GraphModelClients.forConfig(config()));
    }

    /** What the served graph puts in front of the model for the question, or empty. */
    private static Optional<String> servedContext(GraphTenant tenant) {
        return tenant.holder().acquire().map(reading -> {
            try (reading) {
                return reading.pipeline().answer(QUESTION).contextText();
            }
        });
    }

    private static final java.util.function.BooleanSupplier NEVER = () -> false;

    @Test
    @DisplayName("a namespace with nothing staged is current")
    void nothingStaged(@TempDir Path root) throws IOException {
        try (GraphTenant tenant = open(root.resolve("graph"))) {
            assertThat(tenant.freshness()).isEqualTo(GraphFreshness.CURRENT);
            assertThat(tenant.buildIfBehind(NEVER)).isEmpty();
        }
        assertThat(GraphTenant.freshnessOnDisk(root.resolve("graph")))
            .isEqualTo(GraphFreshness.CURRENT);
    }

    @Test
    @DisplayName("a replaced document is published by the build that catches the graph up")
    void aReplacementIsPublished(@TempDir Path root) throws IOException {
        try (GraphTenant tenant = open(root.resolve("graph"))) {
            StagingGraphIngestSink sink =
                new StagingGraphIngestSink(tenant.staging(), collaborator());
            sink.onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");

            GraphFreshness staged = tenant.freshness();
            assertThat(staged.behind()).isTrue();
            assertThat(staged.pendingSince()).isPresent();
            assertThat(staged.lastChangedAt()).isPresent();

            assertThat(tenant.buildIfBehind(NEVER)).get()
                .extracting(GraphBuildService.BuildOutcome::published).isEqualTo(true);
            assertThat(tenant.freshness().behind()).isFalse();
            assertThat(servedContext(tenant).orElseThrow()).contains("Charles Babbage");

            sink.onDocumentReplaced("docs", "docs:ada", "Ada worked with Menabrea.", 0);
            assertThat(tenant.freshness().behind()).isTrue();
            assertThat(servedContext(tenant).orElseThrow())
                .as("the served graph still answers from the last build until the next")
                .contains("Charles Babbage");

            assertThat(tenant.buildIfBehind(NEVER)).isPresent();

            assertThat(tenant.freshness().behind()).isFalse();
            assertThat(servedContext(tenant).orElseThrow())
                .contains("Luigi Menabrea")
                .doesNotContain("Charles Babbage");
        }
    }

    @Test
    @DisplayName("a removed document's facts stop being served once every fact is gone")
    void aRemovalRetiresTheGraph(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            StagingGraphIngestSink sink =
                new StagingGraphIngestSink(tenant.staging(), collaborator());
            sink.onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            tenant.buildIfBehind(NEVER);
            assertThat(tenant.holder().isPresent()).isTrue();

            sink.onDocumentRemoved("docs", "docs:ada");
            assertThat(tenant.freshness().behind()).isTrue();

            GraphBuildService.BuildOutcome outcome = tenant.buildIfBehind(NEVER).orElseThrow();

            assertThat(outcome.retired()).isTrue();
            assertThat(tenant.holder().isPresent()).isFalse();
            assertThat(tenant.freshness()).isEqualTo(GraphFreshness.CURRENT);
            assertThat(tenant.buildIfBehind(NEVER)).as("and nothing is left to build").isEmpty();
        }
        assertThat(GraphTenant.freshnessOnDisk(directory)).isEqualTo(GraphFreshness.CURRENT);
    }

    @Test
    @DisplayName("a graph an explicit build already caught up is not built again")
    void anExplicitBuildGotThereFirst(@TempDir Path root) throws IOException {
        try (GraphTenant tenant = open(root.resolve("graph"))) {
            new StagingGraphIngestSink(tenant.staging(), collaborator())
                .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            assertThat(tenant.freshness().behind()).isTrue();

            tenant.buildService().build();

            assertThat(tenant.freshness().behind()).isFalse();
            assertThat(tenant.buildIfBehind(NEVER)).isEmpty();
        }
    }

    @Test
    @DisplayName("an erasure compaction does not leave the graph behind")
    void anErasureIsNotAChangeToPublish(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            new StagingGraphIngestSink(tenant.staging(), collaborator())
                .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            tenant.buildIfBehind(NEVER);

            tenant.erasureService().submit(
                new ErasureScope.Entity(tenant.staging().entityIdOf("Charles Babbage")));
            tenant.erasureService().compactNow();

            assertThat(tenant.erasureService().outstandingCount()).isZero();
            assertThat(tenant.freshness().behind())
                .as("the compaction erased staging and the served graph alike")
                .isFalse();
            assertThat(tenant.buildIfBehind(NEVER)).isEmpty();
        }
        assertThat(GraphTenant.freshnessOnDisk(directory).behind())
            .as("nor does the graph it rewrote read as older than the staging it erased")
            .isFalse();
    }

    @Test
    @DisplayName("a build asked to stop publishes nothing and leaves the graph behind")
    void aCancelledBuildLeavesItBehind(@TempDir Path root) throws IOException {
        try (GraphTenant tenant = open(root.resolve("graph"))) {
            new StagingGraphIngestSink(tenant.staging(), collaborator())
                .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenant.buildIfBehind(() -> true))
                .isInstanceOf(com.eonmux.excalibur.graph.build.BuildCancelledException.class);

            assertThat(tenant.holder().isPresent()).isFalse();
            assertThat(tenant.freshness().behind()).isTrue();
        }
    }

    @Nested
    @DisplayName("Read from disk")
    class OnDisk {

        @Test
        @DisplayName("staged changes no build has published survive the tenant closing")
        void theRecordOutlivesTheTenant(@TempDir Path root) throws IOException {
            Path directory = root.resolve("graph");
            try (GraphTenant tenant = open(directory)) {
                new StagingGraphIngestSink(tenant.staging(), collaborator())
                    .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            }

            GraphFreshness freshness = GraphTenant.freshnessOnDisk(directory);
            assertThat(freshness.behind()).isTrue();
            assertThat(freshness.pendingSince()).isPresent();
            assertThat(freshness.withheld()).isFalse();

            try (GraphTenant tenant = open(directory)) {
                assertThat(tenant.freshness().behind()).isTrue();
                tenant.buildIfBehind(NEVER);
            }
            assertThat(GraphTenant.freshnessOnDisk(directory)).isEqualTo(GraphFreshness.CURRENT);
        }

        @Test
        @DisplayName("staged facts with no graph built from them are behind, without a record")
        void factsWithNoGraph(@TempDir Path root) throws IOException {
            Path directory = root.resolve("graph");
            try (GraphTenant tenant = open(directory)) {
                new StagingGraphIngestSink(tenant.staging(), collaborator())
                    .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            }
            Files.delete(GraphTenant.stagingDirectoryOf(directory)
                .resolve(GraphStagingStore.UNPUBLISHED_MARKER));

            assertThat(GraphTenant.freshnessOnDisk(directory).behind()).isTrue();
            try (GraphTenant tenant = open(directory)) {
                assertThat(tenant.freshness().behind()).isTrue();
            }
        }

        @Test
        @DisplayName("facts staged after the graph by a node that kept no record are noticed "
            + "on open")
        void anOlderNodesStagingIsNoticed(@TempDir Path root) throws IOException {
            Path directory = root.resolve("graph");
            try (GraphTenant tenant = open(directory)) {
                StagingGraphIngestSink sink =
                    new StagingGraphIngestSink(tenant.staging(), collaborator());
                sink.onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
                tenant.buildIfBehind(NEVER);
                sink.onDocumentPublished("docs", "docs:note", "Ada worked with Menabrea.");
            }
            Path staging = GraphTenant.stagingDirectoryOf(directory);
            Files.delete(staging.resolve(GraphStagingStore.UNPUBLISHED_MARKER));
            Instant built = Instant.now().minusSeconds(3600);
            Files.setLastModifiedTime(directory.resolve(EdgeStoreDescriptor.FILE_NAME),
                FileTime.from(built));

            assertThat(GraphTenant.freshnessOnDisk(directory).behind())
                .as("the triple log was written after the graph's descriptor")
                .isTrue();
            try (GraphTenant tenant = open(directory)) {
                assertThat(tenant.freshness().behind()).isTrue();
                assertThat(tenant.staging().unpublishedChanges().pending())
                    .as("recorded, so the answer does not rest on file times from here on")
                    .isTrue();
                assertThat(tenant.buildIfBehind(NEVER)).isPresent();
                assertThat(servedContext(tenant).orElseThrow()).contains("Luigi Menabrea");
            }
        }

        @Test
        @DisplayName("a graph built from what is staged is not taken for an older one")
        void aCurrentGraphIsNotStale(@TempDir Path root) throws IOException {
            Path directory = root.resolve("graph");
            try (GraphTenant tenant = open(directory)) {
                new StagingGraphIngestSink(tenant.staging(), collaborator())
                    .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
                tenant.buildIfBehind(NEVER);
            }
            Path staging = GraphTenant.stagingDirectoryOf(directory);
            Instant built = Instant.now().minusSeconds(3600);
            for (String log : List.of(GraphStagingStore.TRIPLE_LOG,
                    GraphStagingStore.ENTITY_TEXT_LOG)) {
                Path file = staging.resolve(log);
                if (Files.exists(file)) {
                    Files.setLastModifiedTime(file, FileTime.from(built.minusSeconds(60)));
                }
            }
            Files.setLastModifiedTime(directory.resolve(EdgeStoreDescriptor.FILE_NAME),
                FileTime.from(built));

            assertThat(GraphTenant.freshnessOnDisk(directory)).isEqualTo(GraphFreshness.CURRENT);
            try (GraphTenant tenant = open(directory)) {
                assertThat(tenant.freshness()).isEqualTo(GraphFreshness.CURRENT);
            }
        }

        @Test
        @DisplayName("a graph withheld for an interrupted erasure says so")
        void withheld(@TempDir Path root) throws IOException {
            Path directory = root.resolve("graph");
            try (GraphTenant tenant = open(directory)) {
                new StagingGraphIngestSink(tenant.staging(), collaborator())
                    .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
            }
            Files.createFile(GraphTenant.stagingDirectoryOf(directory)
                .resolve(GraphTenant.ERASURE_INTERRUPTED_MARKER));

            GraphFreshness freshness = GraphTenant.freshnessOnDisk(directory);
            assertThat(freshness.behind()).isTrue();
            assertThat(freshness.withheld()).isTrue();
        }
    }

    @Nested
    @DisplayName("Across namespaces")
    class AcrossNamespaces {

        private GraphTenants tenants(Path root) throws IOException {
            return new GraphTenants(root, config(), MappingBudget.detect(), 4);
        }

        @Test
        @DisplayName("a namespace is built only while it is behind, resident or not")
        void buildIfBehind(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root)) {
                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(ACME)) {
                    new StagingGraphIngestSink(lease.resource().staging(), collaborator())
                        .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
                }
                assertThat(tenants.freshness(ACME).behind()).as("resident").isTrue();
                assertThat(tenants.invalidate(ACME)).isTrue();
                assertThat(tenants.freshness(ACME).behind()).as("read from disk").isTrue();

                assertThat(tenants.buildIfBehind(ACME, NEVER)).isPresent();

                assertThat(tenants.freshness(ACME).behind()).isFalse();
                assertThat(tenants.buildIfBehind(ACME, NEVER)).isEmpty();
            }
        }

        @Test
        @DisplayName("a namespace that does not exist is neither built nor created")
        void unknownNamespace(@TempDir Path root) throws IOException {
            NamespaceId ghost = NamespaceId.of("ghost");
            try (GraphTenants tenants = tenants(root)) {
                assertThat(tenants.freshness(ghost)).isEqualTo(GraphFreshness.CURRENT);
                assertThat(tenants.buildIfBehind(ghost, NEVER)).isEmpty();
            }
            assertThat(root.resolve("ghost")).doesNotExist();
        }

        @Test
        @DisplayName("the namespace listing says which graphs are behind")
        void describedWithFreshness(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root)) {
                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(ACME)) {
                    new StagingGraphIngestSink(lease.resource().staging(), collaborator())
                        .onDocumentPublished("docs", "docs:ada", "Ada worked with Babbage.");
                }
                assertThat(summaryOf(tenants, ACME).freshness()).get()
                    .extracting(GraphFreshness::behind).isEqualTo(true);
                assertThat(summaryOf(tenants, NamespaceId.SHARED).freshness())
                    .contains(GraphFreshness.CURRENT);

                tenants.invalidate(ACME);
                GraphTenants.NamespaceSummary closed = summaryOf(tenants, ACME);
                assertThat(closed.open()).isFalse();
                assertThat(closed.freshness()).get()
                    .extracting(GraphFreshness::behind).isEqualTo(true);
                assertThat(closed.error()).isNull();
            }
        }

        private GraphTenants.NamespaceSummary summaryOf(GraphTenants tenants, NamespaceId id)
                throws IOException {
            return tenants.describeNamespaces().stream()
                .filter(summary -> summary.id().equals(id))
                .findFirst().orElseThrow();
        }
    }
}
