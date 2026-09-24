package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When a source is next re-crawled, across a restart of the node.
 *
 * <p>A restored source used to take its place in the schedule as though it had just been
 * crawled, so its interval started again at every boot. A node restarted more often than
 * {@code excalibur.ingest.reindex-interval-ms} then never re-crawled anything, and a first crawl a
 * restart cut short waited out a whole interval with part of the source indexed. The interval now
 * runs from when the source's last crawl ran to its end, as recorded in
 * {@code <data dir>/source-status}.</p>
 *
 * <p>Each "process" here is a fresh status registry opened on the same data directory, a fresh
 * source registry and a fresh scheduler, as a restarted node has.</p>
 */
@DisplayName("Re-crawl schedule across a restart")
class RecrawlScheduleAcrossRestartTest {

    private static final long INTERVAL_MS = Duration.ofHours(1).toMillis();

    @TempDir
    Path dataDir;

    private final List<SegmentReindexScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void stopSchedulers() {
        schedulers.forEach(scheduler -> scheduler.stop(Duration.ofSeconds(10)));
    }

    /** One process's view of the node: its status history, its registry and its schedule. */
    private final class Node {
        final IngestStatusEndpoint.InMemoryIngestionStatusRegistry statuses =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        final SegmentReindexScheduler scheduler = new SegmentReindexScheduler(sourceId -> true);
        final InMemorySourceRegistry registry = new InMemorySourceRegistry();

        Node() {
            // A pipeline that runs nothing, as the test reports each crawl itself. Without one,
            // a registration probes the source on a thread of its own, which would report on the
            // same status as the test and write the history while the test reads it.
            registry.setIngestionOrchestrator(mock(IngestionOrchestrator.class));
            statuses.persistTo(SourceStatusFile.open(dataDir));
            registry.setStatusRegistry(statuses);
            registry.setReindexScheduler(scheduler, INTERVAL_MS);
            schedulers.add(scheduler);
        }

        Instant nextRecrawl(String sourceId) {
            return scheduler.getNextReindexTime(sourceId);
        }
    }

    private static Source source(String sourceId) {
        Source source = mock(Source.class);
        when(source.getSourceId()).thenReturn(sourceId);
        return source;
    }

    /** Reports a first crawl the way the ingestion pipeline does, through to its end. */
    private static void crawlToTheEnd(IngestStatusEndpoint.IngestionStatus status) {
        status.update("crawling", 0.0, 0, 0);
        status.setSkipped(0);
        status.update("embedding", 0.5, 10, 10);
        status.setSkipped(1);
        status.update("idle", 1.0, 20, 0);
    }

    @Test
    @DisplayName("the interval runs on from the last finished crawl rather than from the boot")
    void theIntervalRunsOnFromTheLastFinishedCrawl() {
        Node before = new Node();
        before.registry.register(source("docs"));
        crawlToTheEnd(before.statuses.getStatus("docs"));
        long finishedAt = before.statuses.getStatus("docs").lastCompletedMs();

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs"))
            .isEqualTo(Instant.ofEpochMilli(finishedAt).plusMillis(INTERVAL_MS));
    }

    @Test
    @DisplayName("a source whose interval passed while the node was down is due at the first check")
    void anOverdueSourceIsDue() throws IOException {
        long threeHoursAgo = System.currentTimeMillis() - Duration.ofHours(3).toMillis();
        SourceStatusFile.open(dataDir).write(List.of(new SourceStatusFile.Entry(
            "docs", "idle", 1.0, 20, 0, 1, 0, 0, threeHoursAgo, threeHoursAgo, null)));

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs")).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("a first crawl the restart cut short is retried at the first check")
    void anInterruptedFirstCrawlIsRetriedPromptly() {
        Node before = new Node();
        before.registry.register(source("docs"));
        IngestStatusEndpoint.IngestionStatus status = before.statuses.getStatus("docs");
        status.update("crawling", 0.0, 0, 0);
        status.update("embedding", 0.4, 8, 12);

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs"))
            .as("part of the source is indexed and nothing says which part")
            .isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("a first crawl that failed is retried at the first check")
    void aFailedFirstCrawlIsRetriedPromptly() {
        Node before = new Node();
        before.registry.register(source("site"));
        IngestStatusEndpoint.IngestionStatus status = before.statuses.getStatus("site");
        status.update("crawling", 0.0, 0, 0);
        status.setError("Crawl failed: robots.txt of https://example.org answered 503");

        Node after = new Node();
        after.registry.restore(source("site"));

        assertThat(after.nextRecrawl("site")).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("a re-crawl cut short does not move the interval of the crawl before it")
    void anInterruptedRecrawlDoesNotCount() throws Exception {
        Node before = new Node();
        before.registry.register(source("docs"));
        crawlToTheEnd(before.statuses.getStatus("docs"));
        long finishedAt = before.statuses.getStatus("docs").lastCompletedMs();
        Thread.sleep(5);
        // A re-crawl stopped by the shutdown reports what it passed over as it stops, which is
        // written down with a later time; it did not run to its end.
        before.statuses.getStatus("docs").setSkipped(3);

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs"))
            .isEqualTo(Instant.ofEpochMilli(finishedAt).plusMillis(INTERVAL_MS));
    }

    @Test
    @DisplayName("a re-crawl that ran to its end moves the interval")
    void aFinishedRecrawlCounts() throws Exception {
        Node before = new Node();
        before.registry.register(source("docs"));
        crawlToTheEnd(before.statuses.getStatus("docs"));
        Thread.sleep(5);
        IngestStatusEndpoint.IngestionStatus status = before.statuses.getStatus("docs");
        status.recordCrawlCompleted();
        long recrawledAt = status.lastCompletedMs();

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs"))
            .isEqualTo(Instant.ofEpochMilli(recrawledAt).plusMillis(INTERVAL_MS));
    }

    @Test
    @DisplayName("a history from before the completed time was recorded is due once")
    void aHistoryWithoutTheCompletedTimeIsDue() throws IOException {
        long halfAnHourAgo = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
        SourceStatusFile.open(dataDir).write(List.of(new SourceStatusFile.Entry(
            "docs", "idle", 1.0, 20, 0, 1, 0, 0, halfAnHourAgo, 0L, null)));

        Node after = new Node();
        after.registry.restore(source("docs"));

        assertThat(after.nextRecrawl("docs")).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("a source registered now is first due one interval from now, as before")
    void aFreshRegistrationWaitsAnInterval() {
        Node node = new Node();
        Instant before = Instant.now();

        node.registry.register(source("docs"));

        assertThat(node.nextRecrawl("docs"))
            .isBetween(before.plusMillis(INTERVAL_MS), Instant.now().plusMillis(INTERVAL_MS));
    }
}
