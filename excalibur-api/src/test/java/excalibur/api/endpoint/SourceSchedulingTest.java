package excalibur.api.endpoint;

import excalibur.ingest.source.Source;
import excalibur.shard.worker.SegmentReindexScheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A registered source joins the re-crawl schedule under the name it was registered with.
 *
 * <p>Source ids are chosen by whoever posts to {@code /v1/ingest} - {@code my-docs},
 * {@code handbook}, {@code corpus}. The schedule used to be keyed by a {@code long}, so
 * registration parsed the id and skipped every source whose name was not a decimal number.
 * Nothing failed: the request was accepted, the crawl ran, the documents were indexed, and the
 * source was simply never re-crawled again for the life of the node. The only trace was one
 * debug-level line at registration.</p>
 *
 * <p>Which makes the interesting case the ordinary one. A test that registers {@code "42"}
 * passes either way.</p>
 */
@DisplayName("Source scheduling")
class SourceSchedulingTest {

    private SegmentReindexScheduler scheduler;
    private InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        scheduler = mock(SegmentReindexScheduler.class);
        registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(new IngestStatusEndpoint.InMemoryIngestionStatusRegistry());
        registry.setReindexScheduler(scheduler, 3_600_000L);
    }

    private Source source(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    @Test
    @DisplayName("a source named like a source is re-crawled")
    void aNamedSourceIsScheduled() {
        registry.register(source("project-docs"));

        verify(scheduler).registerSource("project-docs", 3_600_000L);
    }

    @Test
    @DisplayName("a source named like a number is re-crawled too")
    void aNumericIdIsScheduled() {
        registry.register(source("42"));

        verify(scheduler).registerSource("42", 3_600_000L);
    }

    @Test
    @DisplayName("removing a named source takes it out of the schedule")
    void aNamedSourceIsUnscheduled() {
        registry.register(source("project-docs"));

        assertThat(registry.remove("project-docs")).isTrue();
        verify(scheduler).unregisterSource("project-docs");
    }

    @Test
    @DisplayName("a source with no scheduler configured is still registered")
    void schedulingIsOptional() {
        InMemorySourceRegistry unscheduled = new InMemorySourceRegistry();

        unscheduled.register(source("project-docs"));

        assertThat(unscheduled.isRegistered("project-docs")).isTrue();
    }
}
