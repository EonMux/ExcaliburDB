package excalibur.api.bootstrap;

import excalibur.ingest.graph.RelationExtractor;
import excalibur.ingest.graph.StagingGraphIngestSink;
import io.excalibur.config.Config;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.tenancy.MappingBudget;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One graph per namespace, bounded by what the process may map.
 *
 * <p>Per-namespace isolation was a module nothing depended on: {@code excalibur-tenancy} compiled
 * and was tested and no other module listed it. These tests are the wiring that makes the claim
 * true - a tenant's facts, its store and its erasure obligations are its own, and the number of
 * graphs held open at once is bounded rather than growing until the process runs out of
 * mappings.</p>
 */
@DisplayName("Graph tenants")
class GraphTenantsTest {

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    private static RelationExtractor facts(String subject, String object) {
        return (documentKey, text) -> List.of(
            ExtractedTriple.alwaysValid(subject, "knows", object, from(1)));
    }

    private static GraphTenants tenants(Path root, int maxOpen) throws IOException {
        return new GraphTenants(root, config(), MappingBudget.detect(), maxOpen);
    }

    @Test
    @DisplayName("a node that never sends the header uses one shared namespace")
    void theDefaultIsASingleSharedNamespace(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            assertThat(GraphTenants.namespaceOf(null)).isEqualTo(NamespaceId.SHARED);
            assertThat(GraphTenants.namespaceOf("  ")).isEqualTo(NamespaceId.SHARED);
            assertThat(tenants.shared().id()).isEqualTo(NamespaceId.SHARED);
        }
    }

    @Test
    @DisplayName("an invalid namespace is rejected rather than coerced")
    void anInvalidNamespaceIsRejected() {
        assertThatThrownBy(() -> GraphTenants.namespaceOf("Not A Namespace"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one tenant's facts never reach another's graph")
    void tenantsDoNotShareFacts(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            NamespaceId acme = NamespaceId.of("acme");
            NamespaceId beta = NamespaceId.of("beta");

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                    .onDocumentPublished("acme:doc", "Ada knows Babbage.");
                assertThat(lease.resource().buildService().build().published()).isTrue();
            }

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(beta)) {
                // Beta staged nothing, so it has no graph - acme's facts are not visible to it.
                assertThat(lease.resource().staging().stagedCount()).isZero();
                assertThat(lease.resource().holder().isPresent()).isFalse();
            }

            // And each has its own directory on disk.
            assertThat(tempDir.resolve("acme").resolve("graph")).isDirectory();
            assertThat(tempDir.resolve("beta").resolve("graph")).doesNotExist();
        }
    }

    @Test
    @DisplayName("a namespace's graph survives being evicted and reopened")
    void aGraphSurvivesEviction(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 2)) {
            NamespaceId acme = NamespaceId.of("acme");

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                    .onDocumentPublished("acme:doc", "Ada knows Babbage.");
                assertThat(lease.resource().buildService().build().edges()).isEqualTo(1);
            }

            assertThat(tenants.invalidate(acme)).isTrue();

            // Reopened from disk on the next borrow, with the graph it built still there.
            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                assertThat(lease.resource().holder().isPresent()).isTrue();
                assertThat(lease.resource().staging().stagedCount()).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("more namespaces than the bound does not mean more open graphs")
    void openGraphsAreBounded(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 2)) {
            // The shared namespace holds one of the two slots and is never evicted, so asking for
            // several more must not simply accumulate them.
            for (int i = 0; i < 6; i++) {
                try (NamespaceRegistry.Lease<GraphTenant> lease =
                         tenants.borrow(NamespaceId.of("tenant-" + i))) {
                    assertThat(lease.resource().id().value()).isEqualTo("tenant-" + i);
                }
            }

            assertThat(tenants.openCount())
                .as("a node with many tenants must not hold every graph open at once")
                .isLessThanOrEqualTo(2);
            assertThat(tenants.evictionCount()).isPositive();
        }
    }

    @Test
    @DisplayName("a borrowed namespace is not unmapped underneath its reader")
    void aBorrowedNamespaceIsNotUnmapped(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 2)) {
            NamespaceId acme = NamespaceId.of("acme");

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                    .onDocumentPublished("acme:doc", "Ada knows Babbage.");
                lease.resource().buildService().build();

                // Evicted while it is being read. The eviction is deferred, so what this lease
                // holds is still usable - the alternative is a query walking unmapped memory.
                tenants.invalidate(acme);

                assertThat(lease.resource().holder().isPresent()).isTrue();
                assertThat(lease.resource().holder().pipeline()).isPresent();
            }
        }
    }

    @Test
    @DisplayName("the shared namespace is never evicted, so ingestion's staging stays open")
    void theSharedNamespaceIsPinned(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 2)) {
            // Ingestion holds this reference for the life of the process; if the shared tenant
            // could be evicted, every document after that would be staged into a closed file.
            var staging = tenants.shared().staging();

            for (int i = 0; i < 6; i++) {
                try (NamespaceRegistry.Lease<GraphTenant> lease =
                         tenants.borrow(NamespaceId.of("tenant-" + i))) {
                    assertThat(lease.resource()).isNotNull();
                }
            }

            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Babbage", from(1)));
            assertThat(staging.stagedCount()).isEqualTo(1);
            assertThat(tenants.openNamespaces()).contains(NamespaceId.SHARED);
        }
    }

    @Test
    @DisplayName("reading a namespace nobody has built does not bring it into being")
    void readingAnUnknownNamespaceCreatesNothing(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            assertThat(tenants.borrowIfPresent(NamespaceId.of("never-built"))).isEmpty();

            assertThat(tempDir.resolve("never-built"))
                .as("a graph tenant writes its staging directory as it opens, so a read that "
                    + "opened would leave one behind for every name it was asked about - and the "
                    + "names are the caller's to choose")
                .doesNotExist();
            assertThat(tenants.openNamespaces()).containsExactly(NamespaceId.SHARED);
        }
    }

    @Test
    @DisplayName("a namespace a build created is readable by the query that follows")
    void aBuiltNamespaceIsReadable(@TempDir Path tempDir) throws IOException {
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            tenants.borrow(acme).close();

            try (NamespaceRegistry.Lease<GraphTenant> lease =
                     tenants.borrowIfPresent(acme).orElseThrow()) {
                assertThat(lease.resource().id()).isEqualTo(acme);
            }
        }
    }

    @Test
    @DisplayName("the shared namespace is readable on a node that has never seen the header")
    void theSharedNamespaceIsAlwaysReadable(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            try (NamespaceRegistry.Lease<GraphTenant> lease =
                     tenants.borrowIfPresent(NamespaceId.SHARED).orElseThrow()) {
                assertThat(lease.resource().id()).isEqualTo(NamespaceId.SHARED);
            }
        }
    }
}
