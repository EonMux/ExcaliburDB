package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.tenancy.NamespaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InMemorySourceRegistry#replace}: a registered source's configuration swapped in one
 * step, keeping everything a removal and a registration would have thrown away.
 */
@DisplayName("Replacing a source's configuration")
class InMemorySourceRegistryReplaceTest {

    private static final String ID = "docs";

    @TempDir
    Path dataDir;

    private InMemorySourceRegistry registry;
    private SourceCatalog catalog;
    private IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses;
    private SegmentReindexScheduler scheduler;
    private IngestionOrchestrator orchestrator;
    private ReindexCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new InMemorySourceRegistry();
        catalog = SourceCatalog.open(dataDir);
        statuses = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        scheduler = mock(SegmentReindexScheduler.class);
        orchestrator = mock(IngestionOrchestrator.class);
        coordinator = new ReindexCoordinator();
        registry.setSourceCatalog(catalog);
        registry.setStatusRegistry(statuses);
        registry.setReindexScheduler(scheduler, 60_000);
        registry.setIngestionOrchestrator(orchestrator);
        registry.setReindexCoordinator(coordinator);
    }

    @Nested
    @DisplayName("what it keeps and what it changes")
    class Keeps {

        @Test
        @DisplayName("the live source, the catalog record and nothing else change")
        void swapsTheSourceAndTheRecord() {
            FakeSource original = new FakeSource(ID);
            registry.register(original, descriptor("/a", NamespaceId.SHARED));
            statuses.getStatus(ID).update("completed", 1.0, 7, 0);
            FakeSource replacement = new FakeSource(ID);

            registry.replace(replacement, descriptor("/b", NamespaceId.SHARED));

            assertThat(registry.get(ID)).isSameAs(replacement);
            assertThat(original.closed).as("nothing can reach the old source any more").isTrue();
            assertThat(replacement.closed).isFalse();
            assertThat(catalog.all()).singleElement()
                .satisfies(d -> assertThat(d.config()).containsEntry("root_path", "/b"));
            assertThat(SourceCatalog.open(dataDir).all())
                .as("a restart restores the replacement")
                .singleElement()
                .satisfies(d -> assertThat(d.config()).containsEntry("root_path", "/b"));
            assertThat(statuses.getStatus(ID).state()).isEqualTo("completed");
            assertThat(statuses.getStatus(ID).entriesIngested()).isEqualTo(7);
            verify(orchestrator, times(1)).startIngestion(any());
            verify(scheduler, times(1)).registerSource(anyString(), anyLong());
            verify(scheduler, never()).unregisterSource(anyString());
        }

        @Test
        @DisplayName("the namespace stays the one the source was registered in")
        void keepsTheNamespace() {
            NamespaceId acme = NamespaceId.of("acme");
            registry.register(new FakeSource(ID), descriptor("/a", acme));

            registry.replace(new FakeSource(ID), descriptor("/b", acme));

            assertThat(registry.namespaceOf(ID)).contains(acme);
        }

        @Test
        @DisplayName("the reindex claim is released, so the next reindex can run")
        void releasesTheReindexClaim() {
            registry.register(new FakeSource(ID), descriptor("/a", NamespaceId.SHARED));

            registry.replace(new FakeSource(ID), descriptor("/b", NamespaceId.SHARED));

            assertThat(coordinator.isRunning(ID)).isFalse();
            try (ReindexCoordinator.Lease lease = coordinator.tryAcquire(ID)) {
                assertThat(lease).isNotNull();
            }
        }

        @Test
        @DisplayName("without a catalog the live source is still swapped")
        void worksWithoutACatalog() {
            InMemorySourceRegistry bare = new InMemorySourceRegistry();
            bare.register(new FakeSource(ID), descriptor("/a", NamespaceId.SHARED));
            FakeSource replacement = new FakeSource(ID);

            bare.replace(replacement, descriptor("/b", NamespaceId.SHARED));

            assertThat(bare.get(ID)).isSameAs(replacement);
        }
    }

    @Nested
    @DisplayName("what it refuses, leaving everything as it was")
    class Refuses {

        private FakeSource original;

        @BeforeEach
        void register() {
            original = new FakeSource(ID);
            registry.register(original, descriptor("/a", NamespaceId.SHARED));
        }

        @Test
        @DisplayName("an id nothing is registered under")
        void unknown() {
            assertThatThrownBy(() -> registry.replace(new FakeSource("other"),
                descriptor("other", "/b", NamespaceId.SHARED)))
                .isInstanceOfSatisfying(SourceRegistrationException.class, e ->
                    assertThat(e.reason()).isEqualTo(SourceRegistrationException.Reason.UNKNOWN));
            assertThat(registry.isRegistered("other")).isFalse();
            assertThat(catalog.all()).extracting(SourceDescriptor::sourceId).containsExactly(ID);
        }

        @Test
        @DisplayName("a different namespace")
        void anotherNamespace() {
            assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                descriptor("/b", NamespaceId.of("acme"))))
                .isInstanceOfSatisfying(SourceRegistrationException.class, e -> {
                    assertThat(e.reason())
                        .isEqualTo(SourceRegistrationException.Reason.NAMESPACE_MISMATCH);
                    assertThat(e.reason().statusCode()).isEqualTo(409);
                });
            assertUnchanged();
        }

        @Test
        @DisplayName("while a crawl of the source is running")
        void aRunningCrawl() {
            when(orchestrator.isIngestionActive(ID)).thenReturn(true);

            assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                descriptor("/b", NamespaceId.SHARED)))
                .isInstanceOfSatisfying(SourceRegistrationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SourceRegistrationException.Reason.IN_FLIGHT);
                    assertThat(e.getMessage()).contains("crawl");
                });
            assertUnchanged();
            assertThat(coordinator.isRunning(ID)).as("the claim taken is given back").isFalse();
        }

        @Test
        @DisplayName("while a reindex of the source is running")
        void aRunningReindex() {
            try (ReindexCoordinator.Lease lease = coordinator.tryAcquire(ID)) {
                assertThat(lease).isNotNull();

                assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                    descriptor("/b", NamespaceId.SHARED)))
                    .isInstanceOfSatisfying(SourceRegistrationException.class, e -> {
                        assertThat(e.reason())
                            .isEqualTo(SourceRegistrationException.Reason.IN_FLIGHT);
                        assertThat(e.getMessage()).contains("reindex");
                    });
                assertThat(coordinator.isRunning(ID)).as("the running claim is untouched").isTrue();
            }
            assertUnchanged();
        }

        @Test
        @DisplayName("once the node is shutting down")
        void shuttingDown() {
            coordinator.stopAccepting();

            assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                descriptor("/b", NamespaceId.SHARED)))
                .isInstanceOfSatisfying(SourceRegistrationException.class, e ->
                    assertThat(e.reason().statusCode()).isEqualTo(503));
            assertUnchanged();
        }

        @Test
        @DisplayName("a catalog that cannot be written, keeping the source registered")
        void anUnwritableCatalog() throws IOException {
            SourceCatalog failing = mock(SourceCatalog.class);
            doThrow(new IOException("disk full")).when(failing).record(any());
            registry.setSourceCatalog(failing);

            assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                descriptor("/b", NamespaceId.SHARED)))
                .isInstanceOf(SourceCatalogException.class);
            assertThat(registry.get(ID)).isSameAs(original);
            assertThat(original.closed).isFalse();
            assertThat(coordinator.isRunning(ID)).isFalse();
        }

        @Test
        @DisplayName("a descriptor that names another source")
        void aMismatchedDescriptor() {
            assertThatThrownBy(() -> registry.replace(new FakeSource(ID),
                descriptor("other", "/b", NamespaceId.SHARED)))
                .isInstanceOf(IllegalArgumentException.class);
            assertUnchanged();
        }

        private void assertUnchanged() {
            assertThat(registry.get(ID)).isSameAs(original);
            assertThat(original.closed).isFalse();
            assertThat(catalog.all()).singleElement()
                .satisfies(d -> assertThat(d.config()).containsEntry("root_path", "/a"));
        }
    }

    @Nested
    @DisplayName("against concurrent changes")
    class Concurrency {

        @Test
        @DisplayName("no reindex can start while a replacement is being recorded")
        void holdsTheReindexClaimForTheWholeSwap() throws Exception {
            registry.register(new FakeSource(ID), descriptor("/a", NamespaceId.SHARED));
            SourceCatalog blocking = mock(SourceCatalog.class);
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                writing.countDown();
                release.await(10, TimeUnit.SECONDS);
                return null;
            }).when(blocking).record(any());
            registry.setSourceCatalog(blocking);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> replacing = pool.submit(() ->
                    registry.replace(new FakeSource(ID), descriptor("/b", NamespaceId.SHARED)));
                assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(coordinator.tryAcquire(ID))
                    .as("a reindex asked for mid-swap is refused, not run on the old source")
                    .isNull();

                release.countDown();
                replacing.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(coordinator.tryAcquire(ID)).isNotNull();
        }

        @Test
        @DisplayName("racing replacements leave the catalog describing the live source")
        void racingReplacementsAgree() throws Exception {
            registry.register(new FakeSource(ID), descriptor("/0", NamespaceId.SHARED));
            int writers = 8;
            List<FakeSource> built = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                built.add(new FakeSource(ID, "/" + (i + 1)));
            }

            race(built.stream().<Runnable>map(source -> () -> registry.replace(source,
                descriptor(source.root, NamespaceId.SHARED))).toList());

            FakeSource live = (FakeSource) registry.get(ID);
            assertThat(SourceCatalog.open(dataDir).all()).singleElement()
                .satisfies(d -> assertThat(d.config()).containsEntry("root_path", live.root));
            assertThat(built.stream().filter(source -> !source.closed))
                .as("every replacement but the last one standing was closed")
                .containsExactly(live);
        }

        @Test
        @DisplayName("a replacement racing a removal never brings the record back")
        void aRemovalWins() throws Exception {
            for (int round = 0; round < 50; round++) {
                registry.register(new FakeSource(ID), descriptor("/a", NamespaceId.SHARED));
                FakeSource replacement = new FakeSource(ID, "/b");

                race(List.of(
                    () -> {
                        try {
                            registry.replace(replacement, descriptor("/b", NamespaceId.SHARED));
                        } catch (SourceRegistrationException e) {
                            assertThat(e.reason())
                                .isEqualTo(SourceRegistrationException.Reason.UNKNOWN);
                        }
                    },
                    () -> registry.remove(ID)));

                assertThat(registry.isRegistered(ID)).isFalse();
                assertThat(SourceCatalog.open(dataDir).all())
                    .as("round %d: the catalog agrees with the registry", round)
                    .isEmpty();
            }
        }

        private void race(List<Runnable> tasks) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (Runnable task : tasks) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        task.run();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(20, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static SourceDescriptor descriptor(String root, NamespaceId namespace) {
        return descriptor(ID, root, namespace);
    }

    private static SourceDescriptor descriptor(String id, String root, NamespaceId namespace) {
        return new SourceDescriptor("filesystem", id, Map.of("root_path", root), namespace);
    }

    /** A crawler that finds nothing and remembers whether it was closed. */
    private static final class FakeSource implements Source {

        private final String id;
        private final String root;
        private volatile boolean closed;

        FakeSource(String id) {
            this(id, "/");
        }

        FakeSource(String id, String root) {
            this.id = id;
            this.root = root;
        }

        @Override
        public String getSourceId() {
            return id;
        }

        @Override
        public String getSourceType() {
            return "filesystem";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.empty();
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
