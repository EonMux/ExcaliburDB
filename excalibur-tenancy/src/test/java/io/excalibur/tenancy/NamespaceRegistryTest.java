package io.excalibur.tenancy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceRegistryTest {

    /** A resource that records whether it was closed, standing in for a mapped index. */
    static final class FakeIndex implements AutoCloseable {
        final NamespaceId id;
        final Path directory;
        // Volatile because the lease tests close on one thread and read on another; without it a
        // use-after-close could go unseen and the test would pass for the wrong reason.
        volatile boolean closed;

        FakeIndex(NamespaceId id, Path directory) {
            this.id = id;
            this.directory = directory;
        }

        /** Fails once closed, as a closed arena does, so a read of a dead resource is visible. */
        int read() {
            if (closed) {
                throw new IllegalStateException("index for " + id + " is closed");
            }
            return 1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static NamespaceRegistry<FakeIndex> registry(Path root, int maxOpen) {
        return registry(root, maxOpen, TieringPolicy.sharedIsHot(), MappingBudget.of(1000, 1.0));
    }

    private static NamespaceRegistry<FakeIndex> registry(Path root, int maxOpen,
                                                         TieringPolicy tiering,
                                                         MappingBudget budget) {
        return new NamespaceRegistry<>(root, FakeIndex::new,
            index -> new long[] { 10L, 3L }, budget, tiering, maxOpen);
    }

    @Nested
    @DisplayName("namespace identifiers")
    class Identifiers {

        @ParameterizedTest
        @ValueSource(strings = {
            "../other-tenant", "..", "/etc/passwd", "a/b", "a\\b", "Tenant", "tenant!",
            "tenant space", ".hidden", "-leading", "" })
        @DisplayName("anything that could escape its directory is refused")
        void rejectsUnsafeIds(String value) {
            // The id becomes a directory name. Every one of these either escapes the root or
            // relies on filesystem behaviour that varies by platform.
            assertThatThrownBy(() -> NamespaceId.of(value))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = { "tenant", "t", "0", "tenant-1", "tenant_1", "a1b2c3" })
        @DisplayName("ordinary identifiers are accepted")
        void acceptsSafeIds(String value) {
            assertThat(NamespaceId.of(value).value()).isEqualTo(value);
        }

        @Test
        @DisplayName("an over-long identifier is refused")
        void rejectsOverLong() {
            assertThatThrownBy(() -> NamespaceId.of("a".repeat(NamespaceId.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
        }

        @Test
        @DisplayName("a tenant cannot claim the shared namespace")
        void sharedIsReserved() {
            assertThatThrownBy(() -> NamespaceId.of("shared"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
            assertThat(NamespaceId.SHARED.isShared()).isTrue();
        }

        @Test
        @DisplayName("a namespace directory stays inside its root")
        void directoryStaysInsideRoot(@TempDir Path root) {
            Path directory = NamespaceId.of("tenant-1").directoryUnder(root);

            assertThat(directory.toString()).startsWith(root.toString());
            assertThat(directory).isNotEqualTo(root);
            assertThat(directory.getFileName().toString()).isEqualTo("tenant-1");
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a namespace is opened once and cached")
        void opensOnce(@TempDir Path root) {
            AtomicInteger loads = new AtomicInteger();
            try (NamespaceRegistry<FakeIndex> registry = new NamespaceRegistry<>(root,
                    (id, dir) -> {
                        loads.incrementAndGet();
                        return new FakeIndex(id, dir);
                    },
                    NamespaceRegistry.ResourceCost.unknown(),
                    MappingBudget.of(1000, 1.0), 4)) {

                NamespaceId tenant = NamespaceId.of("tenant-1");
                FakeIndex first = registry.get(tenant);
                FakeIndex second = registry.get(tenant);

                assertThat(second).isSameAs(first);
                assertThat(loads.get()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the loader is given a directory already inside the root")
        void loaderGetsScopedDirectory(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                FakeIndex index = registry.get(NamespaceId.of("tenant-1"));

                assertThat(index.directory.toString()).startsWith(root.toString());
                assertThat(index.directory.getFileName().toString()).isEqualTo("tenant-1");
            }
        }

        @Test
        @DisplayName("closing the registry closes every open namespace")
        void closeClosesEverything(@TempDir Path root) {
            Set<FakeIndex> opened = new HashSet<>();
            NamespaceRegistry<FakeIndex> registry = new NamespaceRegistry<>(root,
                (id, dir) -> {
                    FakeIndex index = new FakeIndex(id, dir);
                    opened.add(index);
                    return index;
                },
                NamespaceRegistry.ResourceCost.unknown(),
                MappingBudget.of(1000, 1.0), 8);

            registry.get(NamespaceId.of("a"));
            registry.get(NamespaceId.of("b"));
            registry.close();

            assertThat(opened).hasSize(2).allMatch(index -> index.closed);
            assertThat(registry.openCount()).isZero();
        }

        @Test
        @DisplayName("using a closed registry fails rather than reopening")
        void useAfterClose(@TempDir Path root) {
            NamespaceRegistry<FakeIndex> registry = registry(root, 4);
            registry.close();

            assertThatThrownBy(() -> registry.get(NamespaceId.of("tenant-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("evicting a namespace that is not open is not an error")
        void evictUnopened(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                assertThat(registry.evict(NamespaceId.of("never-opened"))).isFalse();
            }
        }

        @Test
        @DisplayName("a namespace whose close throws is still removed and its budget released")
        void closeFailureStillReleases(@TempDir Path root) {
            // Leaving the mappings on the books would leak budget until the process restarted.
            try (NamespaceRegistry<AutoCloseable> registry = new NamespaceRegistry<>(root,
                    (id, dir) -> () -> {
                        throw new IllegalStateException("close failed");
                    },
                    resource -> new long[] { 5L, 2L },
                    MappingBudget.of(1000, 1.0), 4)) {

                NamespaceId tenant = NamespaceId.of("tenant-1");
                registry.get(tenant);
                assertThat(registry.budget().mappings()).isEqualTo(5);

                assertThat(registry.evict(tenant)).isTrue();
                assertThat(registry.openCount()).isZero();
                assertThat(registry.budget().mappings()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("eviction")
    class Eviction {

        @Test
        @DisplayName("the least recently used namespace is evicted at the count limit")
        void evictsLeastRecentlyUsed(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 2, TieringPolicy.uniform(), MappingBudget.of(1000, 1.0))) {

                FakeIndex a = registry.get(NamespaceId.of("a"));
                registry.get(NamespaceId.of("b"));
                // Touching "a" makes "b" the least recently used.
                registry.get(NamespaceId.of("a"));
                registry.get(NamespaceId.of("c"));

                assertThat(a.closed).isFalse();
                assertThat(registry.openNamespaces())
                    .extracting(NamespaceId::value)
                    .containsExactlyInAnyOrder("a", "c");
                assertThat(registry.evictionCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("crossing the mapping high-water mark evicts even under the count limit")
        void evictsOnMappingPressure(@TempDir Path root) {
            // Few large namespaces exhaust the mapping budget long before the count limit; the
            // count alone would not notice.
            MappingBudget budget = MappingBudget.of(25, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 100, TieringPolicy.uniform(), budget)) {

                registry.get(NamespaceId.of("a"));
                registry.get(NamespaceId.of("b"));
                registry.get(NamespaceId.of("c"));

                assertThat(registry.openCount())
                    .as("10 mappings each against a 25 high-water mark")
                    .isLessThan(3);
                assertThat(budget.mappings()).isLessThanOrEqualTo(25);
            }
        }

        @Test
        @DisplayName("a pinned namespace is never evicted, however stale")
        void hotIsNeverEvicted(@TempDir Path root) {
            // The shared index is read by nearly every query and costs seconds to reopen. Recency
            // cannot see that; the tier can.
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 2, TieringPolicy.sharedIsHot(), MappingBudget.of(1000, 1.0))) {

                FakeIndex shared = registry.get(NamespaceId.SHARED);
                registry.get(NamespaceId.of("a"));
                registry.get(NamespaceId.of("b"));
                registry.get(NamespaceId.of("c"));

                assertThat(shared.closed).isFalse();
                assertThat(registry.openNamespaces()).contains(NamespaceId.SHARED);
            }
        }

        @Test
        @DisplayName("a cold namespace is evicted before a warm one that is older")
        void coldGoesFirst(@TempDir Path root) {
            NamespaceId cold = NamespaceId.of("bulk-import");
            TieringPolicy tiering = id ->
                id.equals(cold) ? NamespaceTier.COLD : NamespaceTier.WARM;

            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 2, tiering, MappingBudget.of(1000, 1.0))) {

                FakeIndex warm = registry.get(NamespaceId.of("a"));
                FakeIndex coldIndex = registry.get(cold);
                registry.get(NamespaceId.of("c"));

                assertThat(coldIndex.closed)
                    .as("cold is evicted despite being more recently used than the warm one")
                    .isTrue();
                assertThat(warm.closed).isFalse();
            }
        }

        @Test
        @DisplayName("with everything pinned the registry opens anyway rather than refusing")
        void allPinnedStillOpens(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 1, id -> NamespaceTier.HOT, MappingBudget.of(1000, 1.0))) {

                registry.get(NamespaceId.of("a"));
                FakeIndex second = registry.get(NamespaceId.of("b"));

                assertThat(second).isNotNull();
                assertThat(registry.openCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("eviction releases the budget it took")
        void evictionReleasesBudget(@TempDir Path root) {
            MappingBudget budget = MappingBudget.of(1000, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 4, TieringPolicy.uniform(), budget)) {

                registry.get(NamespaceId.of("a"));
                assertThat(budget.mappings()).isEqualTo(10);
                assertThat(budget.descriptors()).isEqualTo(3);

                registry.evict(NamespaceId.of("a"));
                assertThat(budget.mappings()).isZero();
                assertThat(budget.descriptors()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("mapping budget")
    class Budget {

        @Test
        @DisplayName("the high-water mark sits below the hard limit")
        void highWaterBelowLimit() {
            MappingBudget budget = MappingBudget.of(1000, 0.7);

            assertThat(budget.highWaterMark()).isEqualTo(700);
            assertThat(budget.maxMappings()).isEqualTo(1000);
        }

        @Test
        @DisplayName("crossing the high-water mark is reported before the limit is reached")
        void reportsPressureEarly() {
            // Discovering the limit by hitting it means an OutOfMemoryError naming map failure
            // while heap and RAM are both fine.
            MappingBudget budget = MappingBudget.of(100, 0.7);
            budget.acquire(80, 10);

            assertThat(budget.isOverHighWater()).isTrue();
            assertThat(budget.mappings()).isLessThan(budget.maxMappings());
            assertThat(budget.utilisation()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("a prospective open is tested before it happens")
        void testsBeforeOpening() {
            MappingBudget budget = MappingBudget.of(100, 0.7);
            budget.acquire(60, 5);

            assertThat(budget.wouldExceedHighWater(5)).isFalse();
            assertThat(budget.wouldExceedHighWater(20)).isTrue();
        }

        @Test
        @DisplayName("over-releasing clamps at zero rather than inventing headroom")
        void releaseClampsAtZero() {
            MappingBudget budget = MappingBudget.of(100, 0.7);
            budget.acquire(10, 2);
            budget.release(50, 50);

            assertThat(budget.mappings()).isZero();
            assertThat(budget.descriptors()).isZero();
            assertThat(budget.isOverHighWater()).isFalse();
        }

        @Test
        @DisplayName("detection reports whether the platform limit was actually read")
        void detectionIsHonestAboutItsSource() {
            MappingBudget budget = MappingBudget.detect();

            assertThat(budget.maxMappings()).isPositive();
            assertThat(budget.highWaterMark()).isLessThan(budget.maxMappings());
            // Either it read /proc/sys/vm/max_map_count or it fell back; both are fine, but the
            // budget must say which, so a deployment can tell a real limit from a guess.
            assertThat(budget.limitWasRead() || budget.maxMappings() == MappingBudget.DEFAULT_MAX_MAPPINGS)
                .isTrue();
        }

        @Test
        @DisplayName("an invalid high-water fraction is refused")
        void fractionValidated() {
            assertThatThrownBy(() -> MappingBudget.of(100, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MappingBudget.of(100, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MappingBudget.of(0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("tiering policies")
    class Tiering {

        @Test
        @DisplayName("sharedIsHot pins only the shared namespace")
        void sharedIsHot() {
            TieringPolicy policy = TieringPolicy.sharedIsHot();

            assertThat(policy.tierOf(NamespaceId.SHARED)).isEqualTo(NamespaceTier.HOT);
            assertThat(policy.tierOf(NamespaceId.of("tenant-1"))).isEqualTo(NamespaceTier.WARM);
        }

        @Test
        @DisplayName("declared keeps the shared namespace hot alongside the named ones")
        void declaredIncludesShared() {
            TieringPolicy policy = TieringPolicy.declared(Set.of(NamespaceId.of("vip")), Set.of());

            assertThat(policy.tierOf(NamespaceId.of("vip"))).isEqualTo(NamespaceTier.HOT);
            assertThat(policy.tierOf(NamespaceId.SHARED)).isEqualTo(NamespaceTier.HOT);
            assertThat(policy.tierOf(NamespaceId.of("other"))).isEqualTo(NamespaceTier.WARM);
        }

        @Test
        @DisplayName("declared is how a namespace becomes cold")
        void declaredMakesNamespacesCold() {
            // Without this factory nothing a deployment can configure ever returns COLD, and the
            // registry's evict-cold-first branch is unreachable outside its own tests.
            TieringPolicy policy = TieringPolicy.declared(
                Set.of(NamespaceId.of("vip")),
                Set.of(NamespaceId.of("bulk-import"), NamespaceId.of("archive")));

            assertThat(policy.tierOf(NamespaceId.of("bulk-import"))).isEqualTo(NamespaceTier.COLD);
            assertThat(policy.tierOf(NamespaceId.of("archive"))).isEqualTo(NamespaceTier.COLD);
            assertThat(policy.tierOf(NamespaceId.of("vip"))).isEqualTo(NamespaceTier.HOT);
            assertThat(policy.tierOf(NamespaceId.of("other"))).isEqualTo(NamespaceTier.WARM);
        }

        @Test
        @DisplayName("declared refuses a namespace named both hot and cold")
        void declaredRefusesContradiction() {
            // Picking a winner would make the effective tier depend on which set was consulted
            // first, which is not something an operator can read off their configuration.
            NamespaceId both = NamespaceId.of("confused");

            assertThatThrownBy(() -> TieringPolicy.declared(Set.of(both), Set.of(both)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confused");
        }

        @Test
        @DisplayName("declared with nothing named is the default policy")
        void declaredEmptyMatchesTheDefault() {
            TieringPolicy policy = TieringPolicy.declared(Set.of(), Set.of());

            assertThat(policy.tierOf(NamespaceId.SHARED)).isEqualTo(NamespaceTier.HOT);
            assertThat(policy.tierOf(NamespaceId.of("tenant-1"))).isEqualTo(NamespaceTier.WARM);
        }

        @Test
        @DisplayName("a cold namespace named by declared is evicted before an older warm one")
        void aDeclaredColdNamespaceGoesFirst(@TempDir Path root) {
            // The end of the chain: a tier a deployment can actually configure reaches
            // chooseVictim, rather than only a lambda written inside this test class.
            NamespaceId cold = NamespaceId.of("bulk-import");

            try (NamespaceRegistry<FakeIndex> registry = registry(
                    root, 2, TieringPolicy.declared(Set.of(), Set.of(cold)),
                    MappingBudget.of(1000, 1.0))) {

                FakeIndex warm = registry.get(NamespaceId.of("a"));
                FakeIndex coldIndex = registry.get(cold);
                registry.get(NamespaceId.of("c"));

                assertThat(coldIndex.closed).isTrue();
                assertThat(warm.closed)
                    .as("the warm namespace is older and survives anyway")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("uniform pins nothing, shared included")
        void uniformPinsNothing() {
            assertThat(TieringPolicy.uniform().tierOf(NamespaceId.SHARED))
                .isEqualTo(NamespaceTier.WARM);
        }
    }

    @Nested
    @DisplayName("leases")
    class Leases {

        private static final NamespaceId TENANT = NamespaceId.of("tenant-1");

        @Test
        @DisplayName("a borrowed namespace leaves the registry but its resource stays open")
        void evictionOfABorrowedNamespaceIsDeferred(@TempDir Path root) {
            MappingBudget budget = MappingBudget.of(1000, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 4, TieringPolicy.uniform(), budget)) {

                try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT)) {
                    assertThat(registry.evictOrDefer(TENANT))
                        .isEqualTo(NamespaceRegistry.Eviction.DEFERRED);

                    assertThat(lease.resource().closed).isFalse();
                    assertThat(lease.resource().read()).isEqualTo(1);
                    assertThat(registry.openNamespaces()).doesNotContain(TENANT);
                    assertThat(registry.pendingCloseCount()).isEqualTo(1);
                    assertThat(budget.mappings())
                        .as("the mappings are still held, so they stay on the books")
                        .isEqualTo(10);
                }
            }
        }

        @Test
        @DisplayName("the deferred close happens when the last lease is released")
        void deferredCloseHappensOnRelease(@TempDir Path root) {
            MappingBudget budget = MappingBudget.of(1000, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 4, TieringPolicy.uniform(), budget)) {

                NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT);
                FakeIndex index = lease.resource();
                registry.evict(TENANT);
                assertThat(index.closed)
                    .as("the close is owed, not done")
                    .isFalse();

                lease.close();

                assertThat(index.closed).isTrue();
                assertThat(budget.mappings()).isZero();
                assertThat(budget.descriptors()).isZero();
                assertThat(registry.pendingCloseCount()).isZero();
            }
        }

        @Test
        @DisplayName("releasing a lease on a namespace nobody evicted leaves it open")
        void releaseWithoutEvictionKeepsTheNamespace(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                FakeIndex index;
                try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT)) {
                    index = lease.resource();
                }

                assertThat(index.closed)
                    .as("a lease is a claim on the resource, not ownership of it")
                    .isFalse();
                assertThat(registry.openNamespaces()).contains(TENANT);
                assertThat(registry.getIfOpen(TENANT)).contains(index);
            }
        }

        @Test
        @DisplayName("an unborrowed namespace is closed on the spot")
        void unborrowedEvictionIsImmediate(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                FakeIndex index = registry.get(TENANT);

                assertThat(registry.evictOrDefer(TENANT))
                    .isEqualTo(NamespaceRegistry.Eviction.CLOSED);
                assertThat(index.closed).isTrue();
                assertThat(registry.evictOrDefer(TENANT))
                    .isEqualTo(NamespaceRegistry.Eviction.NOT_OPEN);
            }
        }

        @Test
        @DisplayName("nested leases on one namespace all have to be released before it closes")
        void nestedLeasesAllHold(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                NamespaceRegistry.Lease<FakeIndex> outer = registry.borrow(TENANT);
                NamespaceRegistry.Lease<FakeIndex> inner = registry.borrow(TENANT);
                FakeIndex index = outer.resource();

                assertThat(inner.resource())
                    .as("both leases borrow the one open resource")
                    .isSameAs(index);
                registry.evict(TENANT);

                inner.close();
                assertThat(index.closed)
                    .as("the outer lease is still reading, so the close waits for it")
                    .isFalse();
                assertThat(index.read()).isEqualTo(1);

                outer.close();
                assertThat(index.closed).isTrue();
            }
        }

        @Test
        @DisplayName("evict reports true for a deferred close, not only for a completed one")
        void evictReportsTrueWhenItDefers(@TempDir Path root) {
            NamespaceRegistry<FakeIndex> registry = registry(root, 4);
            try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT)) {
                // The documented meaning is "it was open", not "its resource was closed". Callers
                // return this to a client: an endpoint that reported nothing had been dropped
                // because the drop was deferred would be telling the client the opposite of what
                // happened.
                assertThat(registry.evict(TENANT))
                    .as("the namespace left the registry, whether or not its close waited")
                    .isTrue();
                assertThat(lease.resource().closed).isFalse();
            }

            assertThat(registry.evict(TENANT))
                .as("and a namespace that was never open reports false")
                .isFalse();
            registry.close();
        }

        @Test
        @DisplayName("releasing a lease twice does not give away another lease's borrow")
        void doubleReleaseIsHarmless(@TempDir Path root) {
            // A second decrement would take the count to zero while a reader still holds the
            // resource, closing it underneath them - the failure leases exist to rule out.
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                NamespaceRegistry.Lease<FakeIndex> first = registry.borrow(TENANT);
                NamespaceRegistry.Lease<FakeIndex> second = registry.borrow(TENANT);
                FakeIndex index = first.resource();
                registry.evict(TENANT);

                first.close();
                first.close();
                first.close();

                assertThat(index.closed).isFalse();
                assertThat(registry.pendingCloseCount()).isEqualTo(1);

                second.close();
                assertThat(index.closed).isTrue();
                assertThat(registry.pendingCloseCount()).isZero();
            }
        }

        @Test
        @DisplayName("a released lease refuses to hand its resource out again")
        void releasedLeaseWillNotBeUsed(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT);
                lease.close();

                // The resource may already be closed, so returning it would be the use-after-close
                // this whole mechanism is for.
                assertThatThrownBy(lease::resource)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("released");
            }
        }

        @Test
        @DisplayName("a deferred namespace reopens fresh rather than handing back the old resource")
        void reopeningWhileDeferredLoadsAgain(@TempDir Path root) {
            MappingBudget budget = MappingBudget.of(1000, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 4, TieringPolicy.uniform(), budget)) {

                NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT);
                FakeIndex old = lease.resource();
                registry.evict(TENANT);

                FakeIndex reopened = registry.get(TENANT);

                assertThat(reopened).isNotSameAs(old);
                assertThat(old.closed).isFalse();
                assertThat(budget.mappings())
                    .as("both are mapped while the lease lasts, so both are budgeted for")
                    .isEqualTo(20);

                lease.close();
                assertThat(old.closed).isTrue();
                assertThat(reopened.closed).isFalse();
                assertThat(budget.mappings()).isEqualTo(10);
            }
        }

        @Test
        @DisplayName("eviction under mapping pressure passes over a borrowed namespace")
        void pressureSkipsABorrowedNamespace(@TempDir Path root) {
            // 10 mappings each against a high-water mark of 25: opening the third namespace has to
            // make room, and the borrowed one must not be what it takes.
            MappingBudget budget = MappingBudget.of(25, 1.0);
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 100, TieringPolicy.uniform(), budget)) {

                NamespaceId held = NamespaceId.of("a");
                try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(held)) {
                    registry.get(NamespaceId.of("b"));
                    registry.get(NamespaceId.of("c"));

                    assertThat(lease.resource().closed).isFalse();
                    assertThat(registry.openNamespaces())
                        .extracting(NamespaceId::value)
                        .as("the least recently used unborrowed namespace went instead")
                        .containsExactlyInAnyOrder("a", "c");
                    assertThat(registry.pendingCloseCount())
                        .as("skipped, not deferred: a deferred close frees no mappings, so the "
                            + "eviction loop would empty the registry and still be over budget")
                        .isZero();
                }
            }
        }

        @Test
        @DisplayName("with everything borrowed the registry opens anyway rather than closing one")
        void allBorrowedStillOpens(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry =
                     registry(root, 1, TieringPolicy.uniform(), MappingBudget.of(1000, 1.0))) {

                NamespaceId held = NamespaceId.of("a");
                try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(held)) {
                    FakeIndex second = registry.get(NamespaceId.of("b"));

                    assertThat(second).isNotNull();
                    assertThat(lease.resource().closed)
                        .as("over the count limit is survivable; a use-after-close is not")
                        .isFalse();
                    assertThat(registry.openCount()).isEqualTo(2);
                }
            }
        }

        @Test
        @DisplayName("closing the registry defers a borrowed namespace and closes the rest")
        void closeDefersBorrowedNamespaces(@TempDir Path root) {
            MappingBudget budget = MappingBudget.of(1000, 1.0);
            NamespaceRegistry<FakeIndex> registry =
                registry(root, 4, TieringPolicy.uniform(), budget);
            NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(NamespaceId.of("a"));
            FakeIndex borrowed = lease.resource();
            FakeIndex idle = registry.get(NamespaceId.of("b"));

            registry.close();

            assertThat(idle.closed).isTrue();
            assertThat(borrowed.closed)
                .as("shutdown must not be the one moment that unmaps under a reader")
                .isFalse();
            assertThat(registry.pendingCloseCount()).isEqualTo(1);
            assertThatThrownBy(() -> registry.borrow(NamespaceId.of("a")))
                .as("the registry is closed regardless; it does not wait for the lease")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");

            lease.close();
            assertThat(borrowed.closed).isTrue();
            assertThat(budget.mappings()).isZero();
            assertThat(registry.pendingCloseCount()).isZero();
        }

        @Test
        @DisplayName("borrowing a namespace that is not there is refused before it is loaded")
        void borrowValidatesItsArgument(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                assertThatThrownBy(() -> registry.borrow(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("namespace id");
            }
        }

        /** One query's worth of work: borrow, read, release. */
        private void readUnderLease(NamespaceRegistry<FakeIndex> registry,
                                    CountDownLatch borrowed) {
            try (NamespaceRegistry.Lease<FakeIndex> lease = registry.borrow(TENANT)) {
                borrowed.countDown();
                for (int read = 0; read < 20; read++) {
                    lease.resource().read();
                }
            }
        }

        @Test
        @DisplayName("a borrowed resource stays readable while another thread evicts it")
        void borrowSurvivesConcurrentEviction(@TempDir Path root) throws InterruptedException {
            // The bug this rules out: get() hands back a reference, eviction on another thread
            // closes it mid-read, and a query that did nothing wrong dies on a closed arena.
            int rounds = 2000;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch borrowed = new CountDownLatch(1);
            AtomicBoolean reading = new AtomicBoolean(true);

            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                Thread reader = new Thread(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            readUnderLease(registry, borrowed);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        reading.set(false);
                    }
                }, "lease-reader");

                // Evicts for as long as the reader reads rather than for a fixed count, so the
                // two really do overlap instead of the shorter thread finishing first and leaving
                // the test to pass without ever having raced.
                Thread evictor = new Thread(() -> {
                    try {
                        borrowed.await();
                        while (reading.get()) {
                            registry.evict(TENANT);
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, "lease-evictor");

                // Daemons, so a thread that somehow hangs fails the assertion below rather than
                // keeping the test JVM alive.
                reader.setDaemon(true);
                evictor.setDaemon(true);
                reader.start();
                evictor.start();
                reader.join(60_000);
                evictor.join(60_000);

                assertThat(reader.isAlive() || evictor.isAlive())
                    .as("a lease must not be able to deadlock against eviction")
                    .isFalse();
                assertThat(failure.get()).isNull();
                assertThat(registry.pendingCloseCount())
                    .as("every deferred close ran when its lease was released")
                    .isZero();
            }
        }
    }

    @Nested
    @DisplayName("borrowing only what exists")
    class BorrowIfPresent {

        private static final NamespaceId TENANT = NamespaceId.of("acme");

        @Test
        @DisplayName("a namespace nobody has created is not created by asking to read it")
        void anUnknownNamespaceIsNotBroughtIntoBeing(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                assertThat(registry.borrowIfPresent(TENANT))
                    .as("loading a namespace is what creates it on disk, so a read that loads "
                        + "leaves a directory, a registry slot and a share of the budget behind "
                        + "for every name it is asked about")
                    .isEmpty();
                assertThat(registry.openCount()).isZero();
            }
        }

        @Test
        @DisplayName("a namespace with a directory under the root is opened and borrowed")
        void anExistingNamespaceIsBorrowed(@TempDir Path root) throws IOException {
            Files.createDirectories(TENANT.directoryUnder(root));

            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                Optional<NamespaceRegistry.Lease<FakeIndex>> lease =
                    registry.borrowIfPresent(TENANT);

                assertThat(lease).isPresent();
                try (NamespaceRegistry.Lease<FakeIndex> held = lease.orElseThrow()) {
                    assertThat(held.resource().id).isEqualTo(TENANT);
                }
            }
        }

        @Test
        @DisplayName("a namespace already open is borrowed without consulting the disk")
        void anOpenNamespaceIsBorrowed(@TempDir Path root) {
            // This loader writes nothing, so a namespace opened through get() has no directory.
            // Disk is the fallback question, asked only when the registry does not already know.
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                registry.get(TENANT);

                try (NamespaceRegistry.Lease<FakeIndex> lease =
                         registry.borrowIfPresent(TENANT).orElseThrow()) {
                    assertThat(lease.resource().closed).isFalse();
                }
            }
        }

        @Test
        @DisplayName("the lease it hands out holds the namespace open, as any other lease does")
        void theLeaseHoldsTheNamespaceOpen(@TempDir Path root) throws IOException {
            Files.createDirectories(TENANT.directoryUnder(root));

            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                NamespaceRegistry.Lease<FakeIndex> lease =
                    registry.borrowIfPresent(TENANT).orElseThrow();
                FakeIndex index = lease.resource();

                registry.evict(TENANT);
                assertThat(index.closed)
                    .as("a deferred close, exactly as borrow() would give")
                    .isFalse();

                lease.close();
                assertThat(index.closed).isTrue();
            }
        }

        @Test
        @DisplayName("a null namespace is refused rather than treated as absent")
        void aNullNamespaceIsRefused(@TempDir Path root) {
            try (NamespaceRegistry<FakeIndex> registry = registry(root, 4)) {
                assertThatThrownBy(() -> registry.borrowIfPresent(null))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @DisplayName("open namespaces are listed least recently used first")
    void listingIsLruOrdered(@TempDir Path root) {
        try (NamespaceRegistry<FakeIndex> registry =
                 registry(root, 8, TieringPolicy.uniform(), MappingBudget.of(1000, 1.0))) {

            registry.get(NamespaceId.of("a"));
            registry.get(NamespaceId.of("b"));
            registry.get(NamespaceId.of("a"));

            List<NamespaceId> namespaces = registry.openNamespaces();
            assertThat(namespaces).extracting(NamespaceId::value).containsExactly("b", "a");
        }
    }
}
