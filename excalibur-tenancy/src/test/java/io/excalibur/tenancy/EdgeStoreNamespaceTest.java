package io.excalibur.tenancy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry against real mapped edge stores.
 *
 * <p>The generic tests prove the eviction policy; these prove the accounting is true of an actual
 * index and that eviction really releases the mappings rather than only forgetting about them.
 */
class EdgeStoreNamespaceTest {

    private static final int KNOWS = 0;

    /** Builds a small store in each namespace's own directory, as production would. */
    private static NamespaceRegistry.Loader<MappedEdgeStore> loader() {
        return (id, directory) -> {
            if (!java.nio.file.Files.exists(directory.resolve("edgestore.meta"))) {
                new EdgeStoreWriter()
                    .add(0, KNOWS, 1)
                    .add(1, KNOWS, 2)
                    .build(directory, 8);
            }
            return MappedEdgeStore.open(directory);
        };
    }

    private static NamespaceRegistry<MappedEdgeStore> registry(Path root, int maxOpen,
                                                               MappingBudget budget) {
        return new NamespaceRegistry<>(root, loader(),
            store -> new long[] { MappedEdgeStore.mappingCount(), MappedEdgeStore.descriptorCount() },
            budget, TieringPolicy.uniform(), maxOpen);
    }

    @Test
    @DisplayName("each tenant gets its own store in its own directory")
    void tenantsAreIsolated(@TempDir Path root) {
        try (NamespaceRegistry<MappedEdgeStore> registry =
                 registry(root, 8, MappingBudget.of(10_000, 1.0))) {

            MappedEdgeStore a = registry.get(NamespaceId.of("tenant-a"));
            MappedEdgeStore b = registry.get(NamespaceId.of("tenant-b"));

            assertThat(a).isNotSameAs(b);
            assertThat(a.edgeCount()).isEqualTo(2);
            assertThat(b.edgeCount()).isEqualTo(2);
            assertThat(root.resolve("tenant-a")).exists();
            assertThat(root.resolve("tenant-b")).exists();
        }
    }

    @Test
    @DisplayName("the budget tracks the mappings a store really holds")
    void accountingMatchesReality(@TempDir Path root) {
        MappingBudget budget = MappingBudget.of(10_000, 1.0);
        try (NamespaceRegistry<MappedEdgeStore> registry = registry(root, 8, budget)) {

            registry.get(NamespaceId.of("tenant-a"));
            assertThat(budget.mappings()).isEqualTo(MappedEdgeStore.mappingCount());

            registry.get(NamespaceId.of("tenant-b"));
            assertThat(budget.mappings()).isEqualTo(2L * MappedEdgeStore.mappingCount());

            assertThat(budget.descriptors())
                .as("a mapping outlives its channel, so an open store holds no descriptors")
                .isZero();
        }
    }

    @Test
    @DisplayName("eviction unmaps the store rather than only forgetting it")
    void evictionActuallyUnmaps(@TempDir Path root) {
        MappingBudget budget = MappingBudget.of(10_000, 1.0);
        try (NamespaceRegistry<MappedEdgeStore> registry = registry(root, 8, budget)) {

            NamespaceId tenant = NamespaceId.of("tenant-a");
            MappedEdgeStore store = registry.get(tenant);
            assertThat(store.neighbors(0, null)).containsExactly(1);

            registry.evict(tenant);

            assertThat(budget.mappings()).isZero();
            assertThatThrownBy(() -> store.neighbors(0, null))
                .as("the arena is closed, so a stale reference fails rather than reading freed memory")
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a borrowed store is still readable after it is evicted")
    void borrowingDefersTheUnmapping(@TempDir Path root) {
        // The mirror of evictionActuallyUnmaps: the same eviction, the same read afterwards, but
        // under a lease the arena is still open and the read answers instead of failing.
        MappingBudget budget = MappingBudget.of(10_000, 1.0);
        try (NamespaceRegistry<MappedEdgeStore> registry = registry(root, 8, budget)) {

            NamespaceId tenant = NamespaceId.of("tenant-a");
            MappedEdgeStore store;
            try (NamespaceRegistry.Lease<MappedEdgeStore> lease = registry.borrow(tenant)) {
                store = lease.resource();

                assertThat(registry.evictOrDefer(tenant))
                    .isEqualTo(NamespaceRegistry.Eviction.DEFERRED);
                assertThat(lease.resource().neighbors(0, null)).containsExactly(1);
                assertThat(budget.mappings())
                    .as("still mapped, so still on the budget")
                    .isEqualTo(MappedEdgeStore.mappingCount());
            }

            assertThat(budget.mappings()).isZero();
            assertThatThrownBy(() -> store.neighbors(0, null))
                .as("released, so the deferred close ran and the arena is shut")
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a store read under a lease never meets an eviction from another thread")
    void borrowSurvivesConcurrentEviction(@TempDir Path root) throws InterruptedException {
        // Without a lease this is the use-after-close from evictionActuallyUnmaps, arrived at by
        // timing rather than by an explicit evict of something the caller still holds.
        NamespaceId tenant = NamespaceId.of("tenant-a");
        int rounds = 200;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch borrowed = new CountDownLatch(1);
        AtomicBoolean reading = new AtomicBoolean(true);

        try (NamespaceRegistry<MappedEdgeStore> registry =
                 registry(root, 8, MappingBudget.of(10_000, 1.0))) {

            Thread reader = new Thread(() -> {
                try {
                    for (int round = 0; round < rounds; round++) {
                        try (NamespaceRegistry.Lease<MappedEdgeStore> lease =
                                 registry.borrow(tenant)) {
                            borrowed.countDown();
                            assertThat(lease.resource().neighbors(0, null)).containsExactly(1);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    reading.set(false);
                }
            }, "edge-store-reader");

            // Evicts for as long as the reader reads. Mapping a store costs far more than
            // evicting one, so a fixed count of evictions would be spent before the reader had
            // got going and the two would barely overlap.
            Thread evictor = new Thread(() -> {
                try {
                    borrowed.await();
                    while (reading.get()) {
                        registry.evict(tenant);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "edge-store-evictor");

            // Daemons, so a thread that somehow hangs fails the assertion below rather than
            // keeping the test JVM alive.
            reader.setDaemon(true);
            evictor.setDaemon(true);
            reader.start();
            evictor.start();
            reader.join(60_000);
            evictor.join(60_000);

            assertThat(reader.isAlive() || evictor.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(registry.pendingCloseCount()).isZero();
        }
    }

    @Test
    @DisplayName("mapping pressure evicts before the kernel limit is reached")
    void mappingPressureEvicts(@TempDir Path root) {
        // Two stores' worth of headroom, so opening a third has to make room first.
        MappingBudget budget = MappingBudget.of(2L * MappedEdgeStore.mappingCount(), 1.0);
        try (NamespaceRegistry<MappedEdgeStore> registry = registry(root, 100, budget)) {

            registry.get(NamespaceId.of("tenant-a"));
            registry.get(NamespaceId.of("tenant-b"));
            registry.get(NamespaceId.of("tenant-c"));

            assertThat(budget.mappings()).isLessThanOrEqualTo(budget.maxMappings());
            assertThat(registry.openCount()).isLessThan(3);
            assertThat(registry.evictionCount()).isPositive();
        }
    }

    @Test
    @DisplayName("an evicted namespace reopens with its data intact")
    void reopenAfterEviction(@TempDir Path root) {
        try (NamespaceRegistry<MappedEdgeStore> registry =
                 registry(root, 1, MappingBudget.of(10_000, 1.0))) {

            NamespaceId tenant = NamespaceId.of("tenant-a");
            registry.get(tenant);
            registry.get(NamespaceId.of("tenant-b"));

            MappedEdgeStore reopened = registry.get(tenant);
            assertThat(reopened.edgeCount()).isEqualTo(2);
            assertThat(reopened.neighbors(0, null)).containsExactly(1);
        }
    }

    @Test
    @DisplayName("closing the registry unmaps every tenant")
    void closeUnmapsEverything(@TempDir Path root) {
        MappingBudget budget = MappingBudget.of(10_000, 1.0);
        NamespaceRegistry<MappedEdgeStore> registry = registry(root, 8, budget);

        MappedEdgeStore a = registry.get(NamespaceId.of("tenant-a"));
        MappedEdgeStore b = registry.get(NamespaceId.of("tenant-b"));
        registry.close();

        assertThat(budget.mappings()).isZero();
        assertThatThrownBy(() -> a.neighbors(0, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.neighbors(0, null)).isInstanceOf(IllegalStateException.class);
    }
}
