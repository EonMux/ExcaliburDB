package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a namespace comes into being, and what bounds it.
 *
 * <p>A namespace is created by a name in a request header, so without a bound any caller able to
 * mutate can create them without end, and a typo in that header silently becomes a tenant. These
 * tests hold the two controls over that: a cap on how many tenant namespaces exist, and a switch
 * that makes creation an explicit act.</p>
 */
@DisplayName("Graph tenants: creating namespaces")
class GraphTenantsCreationTest {

    @TempDir
    Path root;

    private GraphTenants tenants;

    private GraphTenants open(Map<String, String> extra) throws IOException {
        Map<String, String> props = new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"));
        props.putAll(extra);
        tenants = new GraphTenants(root, new Config(props), MappingBudget.detect(), 4);
        return tenants;
    }

    @AfterEach
    void tearDown() {
        if (tenants != null) {
            tenants.close();
        }
    }

    @Nested
    @DisplayName("creating one explicitly")
    class Creating {

        @Test
        @DisplayName("creating a namespace makes it exist without opening it")
        void createMakesItExist() throws IOException {
            open(Map.of());
            NamespaceId acme = NamespaceId.of("acme");

            assertThat(tenants.exists(acme)).isFalse();
            assertThat(tenants.create(acme)).isTrue();

            assertThat(tenants.exists(acme)).isTrue();
            assertThat(root.resolve("acme")).isDirectory();
            assertThat(tenants.openNamespaces())
                .as("creating reserves the name; it does not spend an open slot")
                .doesNotContain(acme);
            assertThat(tenants.namespacesOnDisk()).contains(acme);
        }

        @Test
        @DisplayName("creating one that exists is not an error, and says it created nothing")
        void createIsIdempotent() throws IOException {
            open(Map.of());
            NamespaceId acme = NamespaceId.of("acme");

            assertThat(tenants.create(acme)).isTrue();
            assertThat(tenants.create(acme)).isFalse();
            assertThat(tenants.create(NamespaceId.SHARED))
                .as("the shared namespace always exists")
                .isFalse();
        }

        @Test
        @DisplayName("a namespace created explicitly is borrowed like any other")
        void anExplicitNamespaceIsUsable() throws IOException {
            open(Map.of("excalibur.graph.require-explicit-namespaces", "true"));
            NamespaceId acme = NamespaceId.of("acme");
            tenants.create(acme);

            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(acme)) {
                assertThat(lease.resource()).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("requiring explicit creation")
    class RequiringExplicitCreation {

        @Test
        @DisplayName("by default, naming a namespace creates it")
        void implicitCreationIsTheDefault() throws IOException {
            open(Map.of());
            NamespaceId acme = NamespaceId.of("acme");

            tenants.admit(acme);

            assertThat(tenants.exists(acme)).isTrue();
        }

        @Test
        @DisplayName("switched on, a namespace nobody created is refused, and nothing is written")
        void aTypoIsRefused() throws IOException {
            open(Map.of("excalibur.graph.require-explicit-namespaces", "true"));
            NamespaceId typo = NamespaceId.of("acmee");

            assertThatThrownBy(() -> tenants.admit(typo))
                .isInstanceOfSatisfying(NamespaceRefusedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NamespaceRefusedException.Reason.NOT_CREATED);
                    assertThat(e.reason().statusCode()).isEqualTo(404);
                    assertThat(e.reason().code()).isEqualTo(ErrorCode.NAMESPACE_NOT_FOUND);
                    assertThat(e.namespace()).isEqualTo(typo);
                    assertThat(e.getMessage()).contains("acmee", "PUT /v1/graph/namespaces/acmee");
                });
            assertThatThrownBy(() -> tenants.borrow(typo))
                .isInstanceOf(NamespaceRefusedException.class);
            assertThat(root.resolve("acmee"))
                .as("a refused name must leave nothing behind")
                .doesNotExist();
        }

        @Test
        @DisplayName("the shared namespace is never refused")
        void theSharedNamespaceIsAlwaysThere() throws IOException {
            open(Map.of("excalibur.graph.require-explicit-namespaces", "true"));

            tenants.admit(NamespaceId.SHARED);
            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(NamespaceId.SHARED)) {
                assertThat(lease.resource()).isSameAs(tenants.shared());
            }
        }

        @Test
        @DisplayName("a namespace that already exists on disk is admitted")
        void anExistingNamespaceIsAdmitted() throws IOException {
            Files.createDirectories(root.resolve("acme"));
            open(Map.of("excalibur.graph.require-explicit-namespaces", "true"));

            tenants.admit(NamespaceId.of("acme"));
        }
    }

    @Nested
    @DisplayName("capping how many exist")
    class Capping {

        @Test
        @DisplayName("past the cap, a new namespace is refused and an existing one is not")
        void theCapRefusesOnlyNewNamespaces() throws IOException {
            open(Map.of("excalibur.graph.max-namespaces", "2"));
            tenants.create(NamespaceId.of("a"));
            tenants.admit(NamespaceId.of("b"));

            assertThatThrownBy(() -> tenants.create(NamespaceId.of("c")))
                .isInstanceOfSatisfying(NamespaceRefusedException.class, e -> {
                    assertThat(e.reason())
                        .isEqualTo(NamespaceRefusedException.Reason.LIMIT_REACHED);
                    assertThat(e.reason().statusCode()).isEqualTo(429);
                    assertThat(e.reason().code()).isEqualTo(ErrorCode.NAMESPACE_LIMIT_REACHED);
                    assertThat(e.getMessage()).contains("2", "excalibur.graph.max-namespaces");
                });
            assertThatThrownBy(() -> tenants.borrow(NamespaceId.of("c")))
                .isInstanceOf(NamespaceRefusedException.class);
            assertThat(root.resolve("c")).doesNotExist();

            tenants.admit(NamespaceId.of("a"));
            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(NamespaceId.of("b"))) {
                assertThat(lease.resource()).isNotNull();
            }
            assertThat(tenants.create(NamespaceId.of("a"))).isFalse();
        }

        @Test
        @DisplayName("the shared namespace does not count against the cap")
        void theSharedNamespaceIsNotCounted() throws IOException {
            open(Map.of("excalibur.graph.max-namespaces", "1"));

            assertThat(root.resolve("shared")).isDirectory();
            assertThat(tenants.create(NamespaceId.of("only"))).isTrue();
            assertThat(tenants.namespaceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("dropping a namespace makes room for another")
        void droppingMakesRoom() throws IOException {
            open(Map.of("excalibur.graph.max-namespaces", "1"));
            tenants.create(NamespaceId.of("old"));
            assertThatThrownBy(() -> tenants.create(NamespaceId.of("new")))
                .isInstanceOf(NamespaceRefusedException.class);

            assertThat(tenants.drop(NamespaceId.of("old"))).isPresent();

            assertThat(tenants.create(NamespaceId.of("new"))).isTrue();
        }

        @Test
        @DisplayName("racing creations cannot overshoot the cap")
        void racingCreationsHoldTheCap() throws Exception {
            open(Map.of("excalibur.graph.max-namespaces", "5"));
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Boolean>> attempts = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    NamespaceId id = NamespaceId.of("tenant-" + i);
                    attempts.add(() -> {
                        try {
                            return tenants.create(id);
                        } catch (NamespaceRefusedException e) {
                            return false;
                        }
                    });
                }
                int created = 0;
                for (Future<Boolean> outcome : pool.invokeAll(attempts)) {
                    created += outcome.get() ? 1 : 0;
                }

                assertThat(created).isEqualTo(5);
                assertThat(tenants.namespaceCount()).isEqualTo(5);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("describing a page of them")
    class Paging {

        @Test
        @DisplayName("pages walk every namespace once, in order, and say when to stop")
        void pagesWalkTheInventory() throws IOException {
            open(Map.of());
            for (String name : List.of("d", "a", "c", "b")) {
                tenants.create(NamespaceId.of(name));
            }

            GraphTenants.NamespacePage first = tenants.describeNamespaces(null, 2);
            assertThat(first.namespaces()).extracting(s -> s.id().value())
                .containsExactly("a", "b");
            assertThat(first.total()).isEqualTo(5);
            assertThat(first.next()).contains(NamespaceId.of("b"));

            GraphTenants.NamespacePage second = tenants.describeNamespaces("b", 2);
            assertThat(second.namespaces()).extracting(s -> s.id().value())
                .containsExactly("c", "d");
            assertThat(second.next()).contains(NamespaceId.of("d"));

            GraphTenants.NamespacePage last = tenants.describeNamespaces("d", 2);
            assertThat(last.namespaces()).extracting(s -> s.id().value())
                .containsExactly("shared");
            assertThat(last.next()).isEmpty();
        }

        @Test
        @DisplayName("a page that ends exactly at the last namespace has no next cursor")
        void anExactFinalPageStops() throws IOException {
            open(Map.of());
            tenants.create(NamespaceId.of("a"));

            GraphTenants.NamespacePage page = tenants.describeNamespaces(null, 2);

            assertThat(page.namespaces()).hasSize(2);
            assertThat(page.next()).isEmpty();
        }

        @Test
        @DisplayName("a cursor naming a namespace dropped since still continues after it")
        void aVanishedCursorStillContinues() throws IOException {
            open(Map.of());
            tenants.create(NamespaceId.of("a"));
            tenants.create(NamespaceId.of("c"));

            assertThat(tenants.describeNamespaces("b", 10).namespaces())
                .extracting(s -> s.id().value())
                .containsExactly("c", "shared");
        }

        @Test
        @DisplayName("a cursor that is not a namespace name is refused")
        void aMalformedCursorIsRefused() throws IOException {
            open(Map.of());

            assertThatThrownBy(() -> tenants.describeNamespaces("../etc", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
            assertThatThrownBy(() -> tenants.describeNamespaces(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
