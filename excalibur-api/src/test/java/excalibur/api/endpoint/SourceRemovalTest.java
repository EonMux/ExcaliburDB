package excalibur.api.endpoint;

import excalibur.ingest.source.Source;
import excalibur.shard.worker.SegmentReindexScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking a source out of the registry has to undo what putting it in started.
 *
 * <p>Registering a source starts three things besides the map entry: an ingestion run, a status
 * entry, and a place in the reindex schedule. Removal used to do none of them, which nothing
 * noticed because nothing removed a source - until erasing one meant taking it out of the crawl.
 * The failures are all quiet: a node that goes on crawling a source no request can name, a
 * {@code /v1/ingest/status} entry for a source that no longer exists, and a source-not-found
 * warning every scheduling interval for the life of the process.
 */
@DisplayName("Source removal")
class SourceRemovalTest {

    private SegmentReindexScheduler scheduler;
    private IngestStatusEndpoint.IngestionStatusRegistry statuses;
    private InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = mock(SegmentReindexScheduler.class);
        statuses = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(statuses);
        registry.setReindexScheduler(scheduler, 3_600_000L);
    }

    private Source source(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    @Test
    @DisplayName("a removed source's reindex status is cleared with it")
    void clearsTheReindexStatus() {
        // The status outlives the source otherwise: /v1/reindex/status keeps answering for a
        // source that is gone, and the map grows for the life of the process. The ingestion
        // status is already cleared here; the reindex status describes the same source.
        ReindexEndpoint.InMemoryReindexStatusRegistry reindexes =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        registry.setReindexStatusRegistry(reindexes);
        registry.register(source("42"));
        reindexes.startReindex("42");

        registry.remove("42");

        assertThat(reindexes.getStatus("42")).isNull();
        assertThat(reindexes.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("a removed source is no longer registered")
    void removesTheEntry() {
        registry.register(source("42"));

        assertThat(registry.remove("42")).isTrue();
        assertThat(registry.isRegistered("42")).isFalse();
    }

    @Test
    @DisplayName("a removed source stops being re-crawled")
    void unregistersFromTheScheduler() {
        registry.register(source("42"));
        verify(scheduler).registerSource("42", 3_600_000L);

        registry.remove("42");

        verify(scheduler).unregisterSource("42");
    }

    @Test
    @DisplayName("a removed source stops being reported by /v1/ingest/status")
    void clearsTheStatusEntry() {
        registry.register(source("42"));
        assertThat(statuses.getStatus("42")).isNotNull();

        registry.remove("42");

        assertThat(statuses.getStatus("42"))
            .as("a status entry nothing can clear outlives the source forever")
            .isNull();
        assertThat(statuses.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("a source named like a source is removed like any other")
    void namedIdsAreRemovable() {
        registry.register(source("project-docs"));

        assertThat(registry.remove("project-docs")).isTrue();
        assertThat(registry.isRegistered("project-docs")).isFalse();
        verify(scheduler).unregisterSource("project-docs");
    }

    @Test
    @DisplayName("removing a source that was never registered changes nothing")
    void removingAnAbsentSource() {
        assertThat(registry.remove("nothing")).isFalse();
        verify(scheduler, never()).unregisterSource(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("the source itself is closed, since the registry owns it")
    void closesTheSource() throws Exception {
        Source source = source("42");
        registry.register(source);

        registry.remove("42");

        verify(source).close();
    }
}
