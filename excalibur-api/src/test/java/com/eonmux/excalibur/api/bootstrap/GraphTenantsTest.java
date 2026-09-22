package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.graph.RelationExtractor;
import com.eonmux.excalibur.ingest.graph.StagingGraphIngestSink;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;

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

    private static GraphTenants tenants(Path root, int maxOpen, Map<String, String> extra)
            throws IOException {
        Map<String, String> props = new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"));
        props.putAll(extra);
        return new GraphTenants(root, new Config(props), MappingBudget.detect(), maxOpen);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Enforcing the erasure deadline")
    class ErasureSweep {

        @Test
        @DisplayName("sweeps every open namespace, so a quiet node still meets its bound")
        void sweepsTheOpenNamespaces(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4,
                    Map.of("excalibur.graph.erasure-max-latency-seconds", "1"))) {
                try (NamespaceRegistry.Lease<GraphTenant> lease =
                         tenants.borrow(NamespaceId.of("acme"))) {
                    lease.resource().erasureService()
                        .submit(new ErasureScope.Entity(7));
                }

                // Nothing has been built, so no store can be rewritten - but the sweep must
                // still have visited the namespace that owes something rather than skipping
                // straight past it.
                assertThat(tenants.compactDueErasures()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("passes over namespaces that owe nothing")
        void skipsNamespacesThatOweNothing(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                assertThat(tenants.compactDueErasures()).isZero();
            }
        }

        @Test
        @DisplayName("does not reopen a namespace the eviction just closed")
        void doesNotReopenAnEvictedNamespace(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                tenants.borrow(acme).close();
                assertThat(tenants.invalidate(acme)).isTrue();

                // The sweep runs against a list of open namespaces taken a moment earlier, so it
                // has to decline the ones that went away in between. Borrowing the way a request
                // does would reopen this one, spending the mapping budget the eviction reclaimed.
                assertThat(tenants.borrowResident(acme)).isEmpty();

                try (NamespaceRegistry.Lease<GraphTenant> served =
                         tenants.borrowIfPresent(acme).orElseThrow()) {
                    assertThat(served.resource().id()).isEqualTo(acme);
                }
            }
        }

        @Test
        @DisplayName("checks often enough that the bound is met rather than merely approached")
        void checksWellInsideTheBound() {
            assertThat(GraphTenants.sweepIntervalMillis(3600)).isEqualTo(360_000L);
            assertThat(GraphTenants.sweepIntervalMillis(60)).isEqualTo(6_000L);
        }

        @Test
        @DisplayName("never spins, however short the configured bound")
        void neverSpins() {
            assertThat(GraphTenants.sweepIntervalMillis(1))
                .isGreaterThanOrEqualTo(GraphTenants.MIN_SWEEP_INTERVAL_MS);
            assertThat(GraphTenants.sweepIntervalMillis(0))
                .isGreaterThanOrEqualTo(GraphTenants.MIN_SWEEP_INTERVAL_MS);
        }
    }

    @Test
    @DisplayName("a node that never sends the header uses one shared namespace")
    void theDefaultIsASingleSharedNamespace(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            assertThat(GraphTenants.namespaceOf(null)).isEqualTo(NamespaceId.SHARED);
            assertThat(GraphTenants.namespaceOf("  ")).isEqualTo(NamespaceId.SHARED);
            assertThat(GraphTenants.namespaceOf(" shared ")).isEqualTo(NamespaceId.SHARED);
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
                assertThat(lease.resource().holder().isPresent()).isTrue();
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
    @DisplayName("a namespace configured cold is evicted before a warm one that is older")
    void aColdNamespaceIsEvictedFirst(@TempDir Path tempDir) throws IOException {
        // The tier a deployment declares has to reach the registry that acts on it. Without the
        // wiring nothing this node can be configured with is ever anything but hot or warm, and
        // "evicted first" is a promise the configuration cannot keep.
        try (GraphTenants tenants = tenants(tempDir, 3,
                Map.of("excalibur.graph.cold-namespaces", "bulk-import"))) {

            tenants.borrow(NamespaceId.of("steady")).close();
            tenants.borrow(NamespaceId.of("bulk-import")).close();
            tenants.borrow(NamespaceId.of("late-arrival")).close();

            assertThat(tenants.openNamespaces())
                .as("the bulk import gives way despite being the more recently used")
                .containsExactlyInAnyOrder(
                    NamespaceId.SHARED, NamespaceId.of("steady"), NamespaceId.of("late-arrival"));
        }
    }

    @Test
    @DisplayName("a namespace configured hot outlives the bound, like the shared one")
    void aHotNamespaceIsPinned(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 3,
                Map.of("excalibur.graph.hot-namespaces", "vip"))) {

            tenants.borrow(NamespaceId.of("vip")).close();
            for (int i = 0; i < 6; i++) {
                tenants.borrow(NamespaceId.of("tenant-" + i)).close();
            }

            assertThat(tenants.openNamespaces())
                .as("a pinned tenant is not evicted however long it has been idle")
                .contains(NamespaceId.of("vip"));
        }
    }

    @Test
    @DisplayName("a namespace named both hot and cold stops the node rather than picking one")
    void aContradictoryTierIsRefused(@TempDir Path tempDir) {
        assertThatThrownBy(() -> tenants(tempDir, 3, Map.of(
                "excalibur.graph.hot-namespaces", "confused",
                "excalibur.graph.cold-namespaces", "confused")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confused");
    }

    @Test
    @DisplayName("an unusable namespace name in the tiering configuration is refused")
    void anInvalidTieredNamespaceIsRefused(@TempDir Path tempDir) {
        // Silently dropping it would leave the operator with a policy that reads as configured
        // and does nothing.
        assertThatThrownBy(() -> tenants(tempDir, 3,
                Map.of("excalibur.graph.cold-namespaces", "Not A Namespace")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not A Namespace");
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

    @Test
    @DisplayName("the shared namespace cannot be dropped out from under ingestion")
    void theSharedNamespaceCannotBeInvalidated(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            // This class keeps a lease on the shared tenant for the life of the process, so an
            // eviction would not close it - it would leave the old one behind shared() and open a
            // second GraphStagingStore on the same log at the next borrow.
            assertThatThrownBy(() -> tenants.invalidate(NamespaceId.SHARED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared");
        }
    }

    @Test
    @DisplayName("building the shared namespace leaves ingestion holding the same staging store")
    void buildingSharedKeepsOneStagingStore(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            GraphTenant held = tenants.shared();
            var staging = held.staging();

            // What the build endpoint does for a request that carried no namespace header,
            // repeated - a caller recovers from a build it did not see the result of by retrying.
            for (int attempt = 0; attempt < 3; attempt++) {
                try (NamespaceRegistry.Lease<GraphTenant> lease =
                         tenants.borrow(NamespaceId.SHARED)) {
                    new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                        .onDocumentPublished("doc-" + attempt, "Ada knows Babbage.");
                    assertThat(lease.resource().buildService().build().published()).isTrue();
                    tenants.recost(NamespaceId.SHARED);
                }

                assertThat(tenants.shared())
                    .as("ingestion was handed this tenant once; a build must not replace it")
                    .isSameAs(held);
                assertThat(tenants.shared().staging()).isSameAs(staging);
            }

            // And the graph the build published is the one being served.
            assertThat(tenants.shared().holder().isPresent()).isTrue();
        }
    }

    @Test
    @DisplayName("a graph built after the tenant was opened is charged to the budget")
    void aGraphBuiltLaterIsCharged(@TempDir Path tempDir) throws IOException {
        try (GraphTenants tenants = tenants(tempDir, 4)) {
            NamespaceId acme = NamespaceId.of("acme");

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                // Opened with nothing built into it, so it maps nothing and is charged nothing.
                assertThat(lease.resource().cost()[0]).isZero();
                long before = tenants.budget().mappings();

                new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                    .onDocumentPublished("acme:doc", "Ada knows Babbage.");
                assertThat(lease.resource().buildService().build().published()).isTrue();

                long mapped = lease.resource().cost()[0];
                assertThat(mapped).isPositive();
                assertThat(tenants.recost(acme)).isTrue();
                assertThat(tenants.budget().mappings())
                    .as("a budget that never learns what a built tenant costs under-counts "
                        + "the process's mappings for as long as it runs")
                    .isEqualTo(before + mapped);

                // Idempotent: nothing changed, so nothing is charged twice.
                assertThat(tenants.recost(acme)).isFalse();
                assertThat(tenants.budget().mappings()).isEqualTo(before + mapped);
            }
        }
    }

    @Test
    @DisplayName("a served tenant is charged for its whole stack, not only its edge store")
    void costCoversTheWholeServedStack(@TempDir Path root) throws IOException {
        // The budget exists to evict before vm.max_map_count and RLIMIT_NOFILE are reached, and
        // it can only be as honest as this number. Charging the edge store alone reported
        // thirteen mappings and no descriptors for a tenant that also holds two Lucene indexes
        // and two dictionaries - so the descriptor limit was unreachable at any number of
        // tenants, and the mapping limit arrived sooner than the budget believed.
        NamespaceId acme = NamespaceId.of("acme");
        try (GraphTenants tenants = tenants(root, 4)) {
            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                assertThat(lease.resource().cost())
                    .as("a tenant that has never built holds none of a served stack")
                    .containsExactly(0L, 0L);

                new StagingGraphIngestSink(lease.resource().staging(), facts("Ada", "Babbage"))
                    .onDocumentPublished("acme:doc", "Ada knows Babbage.");
                assertThat(lease.resource().buildService().build().published()).isTrue();

                long[] cost = lease.resource().cost();

                assertThat(cost[0])
                    .as("the dictionaries and the Lucene indexes are mapped too, so the whole "
                        + "stack costs strictly more than the edge store's columns")
                    .isGreaterThan(MappedEdgeStore.mappingCount());
                assertThat(cost[1])
                    .as("the served Lucene indexes are opened read-only and memory-mapped, so "
                        + "they hold no write lock and no open file: none is charged")
                    .isZero();
            }
        }
    }
}
