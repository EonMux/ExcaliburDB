package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.Source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code GET /v1/ingest/status} says about a source after the node restarts.
 *
 * <p>Before this, the answer was the restart itself: every source read idle with zero counts,
 * nothing skipped, and a {@code last_updated_ms} of the moment the node came up - so a source
 * crawled a minute ago and one last crawled three weeks back read the same, and
 * {@code entries_skipped} stopped describing what the root holds. Each run that ends is now
 * written down, and a restored source reports it.</p>
 */
@DisplayName("Ingest status across a restart")
class IngestStatusDurabilityTest {

    private static final long LONG_AGO = 1_700_000_000_000L;

    @TempDir
    Path dataDir;

    /** A registry as a node opens it, attached to the status file under the data directory. */
    private IngestStatusEndpoint.InMemoryIngestionStatusRegistry node() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        statuses.persistTo(SourceStatusFile.open(dataDir));
        return statuses;
    }

    /** Runs a crawl that indexes {@code ingested} entries and passes over {@code skipped}. */
    private static void crawl(IngestStatusEndpoint.IngestionStatus status, long ingested,
                              long skipped) {
        status.update("crawling", 0.0, 0, 0);
        status.update("embedding", 0.5, ingested / 2, ingested / 2);
        status.update("idle", 1.0, ingested, 0);
        status.setSkipped(skipped);
    }

    @Test
    @DisplayName("a finished run reads the same after the restart, time included")
    void aFinishedRunSurvives() throws Exception {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        IngestStatusEndpoint.IngestionStatus status = before.getOrCreate("docs");
        crawl(status, 420, 7);
        status.setError("3 of 423 documents could not be indexed");
        long finishedAt = status.lastUpdatedMs();
        Thread.sleep(5);

        IngestStatusEndpoint.IngestionStatus after = node().restore("docs");

        assertThat(after.state()).isEqualTo("idle");
        assertThat(after.progress()).isEqualTo(1.0);
        assertThat(after.entriesIngested()).isEqualTo(420);
        assertThat(after.entriesPending()).isZero();
        assertThat(after.entriesSkipped())
            .as("entries_skipped describes what the root holds, not what the restart saw")
            .isEqualTo(7);
        assertThat(after.lastError()).isEqualTo("3 of 423 documents could not be indexed");
        assertThat(after.lastUpdatedMs())
            .as("when the source was last crawled, not when the node came up")
            .isEqualTo(finishedAt);
    }

    @Test
    @DisplayName("what robots.txt kept a finished crawl out of reads the same after the restart")
    void theDisallowedCountSurvives() {
        IngestStatusEndpoint.IngestionStatus status = node().getOrCreate("site");
        crawl(status, 80, 2);
        status.setDisallowed(37);

        IngestStatusEndpoint.IngestionStatus after = node().restore("site");

        assertThat(after.entriesDisallowed()).isEqualTo(37);
        assertThat(after.entriesSkipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("how many documents a finished crawl failed on reads the same after the restart")
    void theFailedCountSurvives() {
        IngestStatusEndpoint.IngestionStatus status = node().getOrCreate("docs");
        crawl(status, 80, 2);
        status.setFailed(3);

        IngestStatusEndpoint.IngestionStatus after = node().restore("docs");

        assertThat(after.documentsFailed()).isEqualTo(3);
        assertThat(after.entriesSkipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("a run the restart interrupted does not come back claiming to be running")
    void anInterruptedRunIsNotResumedOnPaper() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        IngestStatusEndpoint.IngestionStatus status = before.getOrCreate("docs");
        crawl(status, 420, 7);
        status.update("crawling", 0.0, 0, 0);
        status.update("embedding", 0.3, 10, 90);

        IngestStatusEndpoint.IngestionStatus after = node().restore("docs");

        assertThat(after.state()).isEqualTo("idle");
        assertThat(after.entriesIngested())
            .as("the last run that finished is what is reported")
            .isEqualTo(420);
    }

    @Test
    @DisplayName("a source that registered and never finished a run starts fresh")
    void aSourceWithNoFinishedRunStartsFresh() {
        IngestStatusEndpoint.IngestionStatus after = node().restore("never-crawled");

        assertThat(after.state()).isEqualTo("idle");
        assertThat(after.entriesIngested()).isZero();
        assertThat(after.lastError()).isNull();
    }

    @Test
    @DisplayName("a history is reported only for a source that is restored")
    void onlyRestoredSourcesAreReported() {
        crawl(node().getOrCreate("docs"), 10, 0);

        IngestStatusEndpoint.InMemoryIngestionStatusRegistry after = node();

        assertThat(after.getAllStatuses())
            .as("a history is not a registration; the catalog decides what is registered")
            .isEmpty();
        assertThat(after.getStatus("docs")).isNull();
    }

    @Test
    @DisplayName("a history no restored source claims is dropped at the next write")
    void anOrphanedHistoryIsDropped() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        crawl(before.getOrCreate("gone"), 10, 0);
        crawl(before.getOrCreate("kept"), 20, 0);

        IngestStatusEndpoint.InMemoryIngestionStatusRegistry after = node();
        after.restore("kept");
        crawl(after.getOrCreate("new"), 5, 0);

        assertThat(SourceStatusFile.open(dataDir).loaded()).containsOnlyKeys("kept", "new");
    }

    @Test
    @DisplayName("a restored source nobody has crawled since keeps its history across writes")
    void anUntouchedHistoryIsKept() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        crawl(before.getOrCreate("quiet"), 30, 2);

        IngestStatusEndpoint.InMemoryIngestionStatusRegistry middle = node();
        middle.restore("quiet");
        crawl(middle.getOrCreate("busy"), 5, 0);

        IngestStatusEndpoint.IngestionStatus quiet = node().restore("quiet");
        assertThat(quiet.entriesIngested()).isEqualTo(30);
        assertThat(quiet.entriesSkipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("a removed source's history is removed with it")
    void removalForgets() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        crawl(before.getOrCreate("docs"), 10, 1);

        before.remove("docs");

        assertThat(SourceStatusFile.open(dataDir).loaded()).isEmpty();
        assertThat(node().restore("docs").entriesIngested()).isZero();
    }

    @Test
    @DisplayName("a run still reporting after its source was removed writes nothing back")
    void aLateReportAfterRemovalIsNotWritten() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry before = node();
        IngestStatusEndpoint.IngestionStatus status = before.getOrCreate("docs");
        status.update("crawling", 0.0, 0, 0);

        before.remove("docs");
        status.update("idle", 1.0, 10, 0);

        assertThat(SourceStatusFile.open(dataDir).loaded()).doesNotContainKey("docs");
    }

    @Test
    @DisplayName("a source that cannot be ingested here is remembered as such")
    void anUnconfiguredSourceIsRemembered() {
        node().getOrCreate("site").setUnconfigured("No ingestion pipeline", 12);

        IngestStatusEndpoint.IngestionStatus after = node().restore("site");

        assertThat(after.state()).isEqualTo(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED);
        assertThat(after.entriesPending()).isEqualTo(12);
        assertThat(after.lastError()).isEqualTo("No ingestion pipeline");
    }

    @Test
    @DisplayName("a history in a state this build does not report is read as a finished run")
    void anUnknownStateIsNotReportedAsRunning() throws IOException {
        SourceStatusFile.open(dataDir).write(List.of(new SourceStatusFile.Entry(
            "docs", "embedding", 0.4, 4, 6, 0, 0, 0, LONG_AGO, null)));

        IngestStatusEndpoint.IngestionStatus after = node().restore("docs");

        assertThat(after.state()).isEqualTo("idle");
        assertThat(after.lastError()).contains("did not finish");
        assertThat(after.lastUpdatedMs()).isEqualTo(LONG_AGO);
    }

    @Test
    @DisplayName("a history that cannot be written leaves the run and its readback alone")
    void anUnwritableHistoryDoesNotFailTheRun() throws IOException {
        Path blocked = dataDir.resolve("not-a-directory");
        Files.writeString(blocked, "a file where the data directory should be");
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        statuses.persistTo(SourceStatusFile.open(blocked.resolve("data")));
        IngestStatusEndpoint.IngestionStatus status = statuses.getOrCreate("docs");

        crawl(status, 10, 0);

        assertThat(status.state()).isEqualTo("idle");
        assertThat(status.entriesIngested()).isEqualTo(10);
    }

    @Test
    @DisplayName("without a status file the registry keeps its statuses in memory, as before")
    void withoutAFileNothingIsWritten() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        crawl(statuses.getOrCreate("docs"), 10, 0);

        assertThat(statuses.restore("docs").entriesIngested()).isEqualTo(10);
        assertThat(dataDir.resolve(SourceStatusFile.FILE)).doesNotExist();
    }

    @Test
    @DisplayName("a source restored from the catalog reports its last run, not a fresh zero")
    void theSourceRegistryRestoresTheHistory() {
        crawl(node().getOrCreate("docs"), 420, 7);
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses = node();
        InMemorySourceRegistry registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(statuses);
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn("docs");

        registry.restore(source);

        assertThat(statuses.getStatus("docs").entriesIngested()).isEqualTo(420);
        assertThat(statuses.getStatus("docs").entriesSkipped()).isEqualTo(7);
    }

    @Test
    @DisplayName("a fresh registration under an id with a history starts from nothing")
    void aNewRegistrationStartsFresh() {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses = node();
        crawl(statuses.getOrCreate("docs"), 420, 7);
        statuses.remove("docs");
        InMemorySourceRegistry registry = new InMemorySourceRegistry();
        registry.setStatusRegistry(statuses);
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn("docs");

        registry.register(source);

        assertThat(statuses.getStatus("docs").entriesIngested()).isZero();
        assertThat(statuses.getStatus("docs").entriesSkipped()).isZero();
    }
}
