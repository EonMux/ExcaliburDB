package excalibur.api.endpoint;

import excalibur.ingest.pipeline.IngestionOrchestrator;
import excalibur.ingest.registry.SourceCatalog;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceDescriptor;
import excalibur.shard.worker.SegmentReindexScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A registration is meant to outlast the request that made it.
 *
 * <p>A source is crawled once and then re-crawled on a schedule for as long as it stays
 * registered - an hour by default. Held only in memory that ends at the next restart, and it ends
 * without a symptom: the corpus is still indexed and still queryable, {@code /v1/ingest/status}
 * simply answers with an empty list and no re-crawl ever runs again. Nothing in the node reports
 * an error, because from its point of view nobody ever registered anything.</p>
 *
 * <p>So the registry writes each registration to a catalog under the data directory, and a
 * removal takes it back out. The interesting cases are the asymmetric ones: a catalog that cannot
 * be written must not leave a caller believing its source is registered, and an entry the catalog
 * remembers but this process could not load must still be removable.</p>
 */
@DisplayName("Source durability")
class SourceDurabilityTest {

    private SegmentReindexScheduler scheduler;
    private IngestionOrchestrator orchestrator;
    private IngestStatusEndpoint.IngestionStatusRegistry statuses;
    private InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = mock(SegmentReindexScheduler.class);
        orchestrator = mock(IngestionOrchestrator.class);
        statuses = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(statuses);
        registry.setReindexScheduler(scheduler, 3_600_000L);
        registry.setIngestionOrchestrator(orchestrator);
    }

    private static Source source(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    private static SourceDescriptor descriptor(String sourceId) {
        return new SourceDescriptor("filesystem", sourceId, Map.of("root_path", "/srv/" + sourceId));
    }

    @Test
    @DisplayName("a registered source is recorded for the next process to find")
    void registrationIsRecorded(@TempDir Path dataDir) {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        registry.setSourceCatalog(catalog);

        registry.register(source("my-docs"), descriptor("my-docs"));

        assertThat(SourceCatalog.open(dataDir).all())
            .as("a registration nothing wrote down stops being re-crawled at the next restart")
            .containsExactly(descriptor("my-docs"));
    }

    @Test
    @DisplayName("a removed source is not brought back by the next start")
    void removalIsRecorded(@TempDir Path dataDir) {
        registry.setSourceCatalog(SourceCatalog.open(dataDir));
        registry.register(source("my-docs"), descriptor("my-docs"));

        assertThat(registry.remove("my-docs")).isTrue();

        assertThat(SourceCatalog.open(dataDir).all()).isEmpty();
    }

    @Test
    @DisplayName("a registration the catalog cannot record is refused")
    void anUnrecordableRegistrationIsRefused(@TempDir Path tempDir) throws IOException {
        Path notADirectory = tempDir.resolve("occupied");
        Files.writeString(notADirectory, "this is a file where the data directory should be");
        registry.setSourceCatalog(SourceCatalog.open(notADirectory));

        assertThatThrownBy(() -> registry.register(source("my-docs"), descriptor("my-docs")))
            .isInstanceOf(SourceCatalogException.class);

        assertThat(registry.isRegistered("my-docs"))
            .as("a caller told its source is registered must not lose it to the next restart")
            .isFalse();
        verify(scheduler, never()).registerSource(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(orchestrator, never()).startIngestion(any());
        assertThat(statuses.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("a source only the catalog remembers is still removable")
    void aQuarantinedRegistrationIsRemovable(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(descriptor("unloadable"));
        registry.setSourceCatalog(catalog);

        assertThat(registry.remove("unloadable"))
            .as("a record no live source matches is still a registration someone made")
            .isTrue();
        assertThat(SourceCatalog.open(dataDir).all()).isEmpty();
    }

    @Test
    @DisplayName("a restored source is scheduled without being re-ingested")
    void restorationSchedulesWithoutCrawling(@TempDir Path dataDir) {
        registry.setSourceCatalog(SourceCatalog.open(dataDir));

        registry.restore(source("my-docs"));

        assertThat(registry.isRegistered("my-docs")).isTrue();
        verify(scheduler).registerSource("my-docs", 3_600_000L);
        assertThat(statuses.getStatus("my-docs")).isNotNull();
        verify(orchestrator, never()).startIngestion(any());
    }

    @Test
    @DisplayName("restoring does not rewrite the catalog it came from")
    void restorationDoesNotRewriteTheCatalog(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(descriptor("my-docs"));
        registry.setSourceCatalog(catalog);

        registry.restore(source("my-docs"));

        assertThat(SourceCatalog.open(dataDir).all()).containsExactly(descriptor("my-docs"));
    }

    @Test
    @DisplayName("a registry with no catalog registers exactly as before")
    void durabilityIsOptional() {
        registry.register(source("my-docs"), descriptor("my-docs"));

        assertThat(registry.isRegistered("my-docs")).isTrue();
        verify(scheduler).registerSource("my-docs", 3_600_000L);
        verify(orchestrator).startIngestion(any());
    }
}
