package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A namespace a caller created has to be findable and removable.
 *
 * <p>Naming a namespace in the {@code X-Excalibur-Namespace} header creates it: the tenant opens
 * and writes its staging directory. Nothing then enumerated what existed -
 * {@code openNamespaces()} lists what happens to be resident, which is a cache's contents and not
 * an inventory - and nothing removed one. A tenant offboarded from the product left its graph, its
 * staged facts and its erasure log on disk for the life of the deployment, and an operator could
 * not so much as discover them.</p>
 *
 * <p>The two halves are one mechanism: you cannot offer to delete what you cannot list, and an
 * inventory that cannot be acted on is a report nobody can use.</p>
 */
@DisplayName("Graph namespace lifecycle")
class GraphNamespaceLifecycleTest {

    private static GraphTenants tenants(Path root, int maxOpen) throws IOException {
        return new GraphTenants(root,
            new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"))),
            MappingBudget.detect(), maxOpen);
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /** Stages one fact into a namespace, which is what creating one amounts to. */
    private static void stage(GraphTenants tenants, NamespaceId id) throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(id)) {
            lease.resource().staging().append(ExtractedTriple.alwaysValid(
                "Ada", "knows", "Babbage", from(1)));
            lease.resource().staging().flush();
        }
    }

    @Nested
    @DisplayName("enumerating what exists")
    class Enumerating {

        @Test
        @DisplayName("a namespace evicted from memory is still on the inventory")
        void anEvictedNamespaceIsStillListed(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                stage(tenants, NamespaceId.of("acme"));
                assertThat(tenants.invalidate(NamespaceId.of("acme"))).isTrue();

                assertThat(tenants.openNamespaces())
                    .as("the open list is a cache's contents, which is what made this a gap")
                    .doesNotContain(NamespaceId.of("acme"));
                assertThat(tenants.namespacesOnDisk())
                    .containsExactly(NamespaceId.of("acme"), NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("the shared namespace is on the inventory like any other")
        void theSharedNamespaceIsListed(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                assertThat(tenants.namespacesOnDisk()).containsExactly(NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("a directory that is not a namespace is passed over rather than guessed at")
        void astrayDirectoryIsNotANamespace(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                Files.createDirectories(root.resolve("Not A Namespace"));
                Files.createFile(root.resolve("stray-file"));

                assertThat(tenants.namespacesOnDisk()).containsExactly(NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("a summary says what an operator needs to decide whether to drop one")
        void aSummaryCarriesWhatAnOperatorNeeds(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                stage(tenants, acme);
                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                    lease.resource().erasureService().submit(new ErasureScope.Entity(0));
                }
                assertThat(tenants.invalidate(acme)).isTrue();

                GraphTenants.NamespaceSummary summary = tenants.describeNamespaces().stream()
                    .filter(each -> each.id().equals(acme))
                    .findFirst()
                    .orElseThrow();

                assertThat(summary.open()).isFalse();
                assertThat(summary.built())
                    .as("nothing was built, only staged")
                    .isFalse();
                assertThat(summary.stagedFacts()).hasValue(1L);
                assertThat(summary.outstandingErasures()).hasValue(1);
                assertThat(summary.error()).isNull();
            }
        }

        @Test
        @DisplayName("summarising a closed namespace does not open its graph")
        void summarisingDoesNotOpenTheGraph(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                stage(tenants, NamespaceId.of("acme"));
                assertThat(tenants.invalidate(NamespaceId.of("acme"))).isTrue();

                assertThat(tenants.describeNamespaces()).hasSize(2);

                assertThat(tenants.openNamespaces())
                    .as("an inventory that reopened every tenant would be the most expensive "
                        + "call on the node, and would evict the working set to build a report")
                    .containsExactly(NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("an unreadable erasure log is reported, never counted as nothing owed")
        void anUnreadableLogIsReported(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                stage(tenants, acme);
                assertThat(tenants.invalidate(acme)).isTrue();
                Files.write(root.resolve("acme")
                        .resolve("graph" + GraphTenant.STAGING_SUFFIX)
                        .resolve(GraphTenant.ERASURE_LOG),
                    "this is not an erasure log at all".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));

                GraphTenants.NamespaceSummary summary = tenants.describeNamespaces().stream()
                    .filter(each -> each.id().equals(acme))
                    .findFirst()
                    .orElseThrow();

                assertThat(summary.outstandingErasures())
                    .as("unknown is not zero: an operator reading zero would offboard the tenant")
                    .isEmpty();
                assertThat(summary.error()).contains("erasure log");
            }
        }
    }

    @Nested
    @DisplayName("dropping one")
    class Dropping {

        @Test
        @DisplayName("dropping a namespace takes its graph, its staging and its log off disk")
        void droppingRemovesEverything(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                stage(tenants, acme);
                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                    assertThat(lease.resource().buildService().build().published()).isTrue();
                }
                assertThat(root.resolve("acme").resolve("graph")).isDirectory();
                assertThat(root.resolve("acme").resolve("graph" + GraphTenant.STAGING_SUFFIX))
                    .isDirectory();

                Optional<GraphTenants.Dropped> dropped = tenants.drop(acme);

                assertThat(dropped).isPresent();
                assertThat(dropped.orElseThrow().id()).isEqualTo(acme);
                assertThat(dropped.orElseThrow().wasOpen()).isTrue();
                assertThat(dropped.orElseThrow().hadGraph()).isTrue();
                assertThat(dropped.orElseThrow().stagedFacts()).isEqualTo(1L);
                assertThat(dropped.orElseThrow().filesDeleted()).isPositive();
                assertThat(dropped.orElseThrow().bytesDeleted()).isPositive();

                assertThat(root.resolve("acme")).doesNotExist();
                assertThat(tenants.namespacesOnDisk()).containsExactly(NamespaceId.SHARED);
                assertThat(tenants.openNamespaces()).containsExactly(NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("an obligation the drop discarded is reported rather than passed over")
        void discardedObligationsAreReported(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                    lease.resource().erasureService().submit(new ErasureScope.Entity(3));
                }

                GraphTenants.Dropped dropped = tenants.drop(acme).orElseThrow();

                assertThat(dropped.discardedErasures())
                    .as("deleting the data an obligation named satisfies it, but an operator "
                        + "still has to be told the log went with it")
                    .hasValue(1);
            }
        }

        @Test
        @DisplayName("a namespace that does not exist is not reported as dropped")
        void droppingSomethingAbsentReportsNothing(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                assertThat(tenants.drop(NamespaceId.of("never-existed"))).isEmpty();
            }
        }

        @Test
        @DisplayName("the shared namespace cannot be dropped")
        void theSharedNamespaceCannotBeDropped(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                assertThatThrownBy(() -> tenants.drop(NamespaceId.SHARED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shared");

                assertThat(root.resolve("shared"))
                    .as("it is the namespace every unnamespaced request lands in, and this "
                        + "registry holds a lease on it for the life of the process")
                    .isDirectory();
            }
        }

        @Test
        @DisplayName("a dropped namespace comes back empty rather than carrying its old data")
        void aDroppedNamespaceComesBackEmpty(@TempDir Path root) throws IOException {
            try (GraphTenants tenants = tenants(root, 4)) {
                NamespaceId acme = NamespaceId.of("acme");
                stage(tenants, acme);
                assertThat(tenants.drop(acme)).isPresent();

                try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                    assertThat(lease.resource().staging().stagedCount())
                        .as("the registry must not be holding the closed tenant of a namespace "
                            + "whose files are gone")
                        .isZero();
                }
            }
        }
    }

    @Nested
    @DisplayName("refusing to leave the registry root")
    class Containment {

        @Test
        @DisplayName("a namespace id cannot name a path outside the root")
        void aTraversalIsRefusedAtTheBoundary() {
            for (String hostile : new String[] {
                "..", "../shared", "../../etc", "a/b", "/absolute", "a\\b", ".", "sha red"}) {
                assertThatThrownBy(() -> NamespaceId.of(hostile))
                    .as("'%s' becomes a directory name", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a namespace outside the root is never enumerated or dropped")
        void nothingOutsideTheRootIsReachable(@TempDir Path parent) throws IOException {
            Path root = parent.resolve("graphs");
            Path sibling = parent.resolve("not-a-tenant");
            Files.createDirectories(sibling);
            Files.writeString(sibling.resolve("keepme.txt"), "untouched");

            try (GraphTenants tenants = tenants(root, 4)) {
                assertThat(tenants.namespacesOnDisk()).containsExactly(NamespaceId.SHARED);

                assertThatThrownBy(() -> tenants.drop(NamespaceId.of("..")))
                    .isInstanceOf(IllegalArgumentException.class);

                assertThat(sibling.resolve("keepme.txt")).exists();
                assertThat(parent).isDirectory();
            }
        }
    }
}
