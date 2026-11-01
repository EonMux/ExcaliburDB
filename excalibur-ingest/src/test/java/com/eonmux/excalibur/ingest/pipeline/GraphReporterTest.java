package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.graph.GraphIngestSink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What ingestion hands the graph about a published document, and in what order.
 *
 * <p>The text is what facts are extracted from; the date is what they are ordered by when a later
 * document gives a single-valued relation a different value. A reporter that dropped the date
 * would leave every fact ordered by the day it happened to be crawled. And a replacement withdraws
 * what was staged from the document before staging its own facts, so a late report of an older
 * version would otherwise replace the newer version's facts with stale ones.</p>
 */
@DisplayName("GraphReporter")
class GraphReporterTest {

    private record Reported(String kind, String documentKey, String text,
                            long lastModifiedMillis) {
    }

    /** A sink that records every call, and can be told to fail or to block. */
    private static class RecordingSink implements GraphIngestSink {
        final List<Reported> reported = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> sources = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;
        volatile IOException syncFailure;

        @Override
        public void onDocumentPublished(String sourceId, String documentKey, String text,
                                        long lastModifiedMillis) {
            sources.add(sourceId);
            record("publish", documentKey, text, lastModifiedMillis);
        }

        @Override
        public void onDocumentReplaced(String sourceId, String documentKey, String text,
                                       long lastModifiedMillis) {
            sources.add(sourceId);
            record("replace", documentKey, text, lastModifiedMillis);
        }

        @Override
        public void onDocumentRemoved(String sourceId, String documentKey) {
            sources.add(sourceId);
            record("remove", documentKey, null, 0L);
        }

        void record(String kind, String documentKey, String text, long lastModifiedMillis) {
            if (failure != null) {
                throw failure;
            }
            calls.add(kind + " " + documentKey);
            reported.add(new Reported(kind, documentKey, text, lastModifiedMillis));
        }

        @Override
        public void sync() throws IOException {
            calls.add("sync");
            if (syncFailure != null) {
                throw syncFailure;
            }
        }
    }

    /** A version as ingestion hands it to the reporter: the text it chunked, and its date. */
    private record Version(String text, long lastModifiedMillis) {
    }

    private static Version document(String text, long lastModifiedMillis) {
        return new Version(text, lastModifiedMillis);
    }

    private static Version document(long lastModifiedMillis) {
        return document("Ada runs Acme.", lastModifiedMillis);
    }

    /** Reports a version, or a removal when it is null, as ingestion does. */
    private static void report(GraphReporter reporter, GraphReporter.Publication publication,
                               Version version) {
        if (version == null) {
            reporter.report(publication, null, 0L);
        } else {
            reporter.report(publication, version.text(), version.lastModifiedMillis());
        }
    }

    private static void publishAndReport(GraphReporter reporter, String documentKey,
                                         boolean replaced, Version doc) {
        report(reporter, reporter.published("docs", documentKey, replaced), doc);
    }

    @Test
    @DisplayName("passes the document's own date along with its text")
    void passesTheDocumentDate() {
        RecordingSink sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        publishAndReport(reporter, "docs:ada.md", false, document(1_620_000_000_000L));
        publishAndReport(reporter, "docs:undated.md", false, document(0L));

        assertThat(sink.reported).containsExactly(
            new Reported("publish", "docs:ada.md", "Ada runs Acme.", 1_620_000_000_000L),
            new Reported("publish", "docs:undated.md", "Ada runs Acme.", 0L));
    }

    @Test
    @DisplayName("names the source that ingested the document, which its key cannot")
    void namesTheSource() {
        RecordingSink sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        // Both keys begin "team:wiki:", and only the report says which source each came from.
        report(reporter, reporter.published("team", "team:wiki:home.md", false), document(1L));
        report(reporter, reporter.published("team:wiki", "team:wiki:faq.md", true), document(2L));
        report(reporter, reporter.removed("team", "team:wiki:home.md"), null);

        assertThat(sink.sources).containsExactly("team", "team:wiki", "team");
        assertThat(sink.calls).containsExactly("publish team:wiki:home.md",
            "replace team:wiki:faq.md", "remove team:wiki:home.md");
    }

    @Test
    @DisplayName("reports a version that replaced another as a replacement")
    void reportsAReplacement() {
        RecordingSink sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        publishAndReport(reporter, "docs:ada.md", false, document("Ada runs Acme.", 1L));
        publishAndReport(reporter, "docs:ada.md", true, document("Ada left Acme.", 2L));

        assertThat(sink.reported).containsExactly(
            new Reported("publish", "docs:ada.md", "Ada runs Acme.", 1L),
            new Reported("replace", "docs:ada.md", "Ada left Acme.", 2L));
    }

    @Test
    @DisplayName("does not report a version a later one superseded before its report ran")
    void skipsASupersededVersion() {
        RecordingSink sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        GraphReporter.Publication older = reporter.published("docs", "docs:ada.md", true);
        GraphReporter.Publication newer = reporter.published("docs", "docs:ada.md", true);
        report(reporter, newer, document("Ada left Acme.", 2L));
        report(reporter, older, document("Ada runs Acme.", 1L));

        assertThat(sink.reported)
            .as("the older version's report would withdraw the newer version's facts")
            .containsExactly(new Reported("replace", "docs:ada.md", "Ada left Acme.", 2L));

        GraphReporter.Publication again = reporter.published("docs", "docs:ada.md", true);
        report(reporter, again, document("Ada is back.", 3L));
        assertThat(sink.reported).last()
            .as("a document whose reports have all run is reported again on its next version")
            .isEqualTo(new Reported("replace", "docs:ada.md", "Ada is back.", 3L));
        assertThat(again.documentKey()).isEqualTo("docs:ada.md");
        assertThat(again.replacedEarlierVersion()).isTrue();
    }

    @Test
    @DisplayName("reports a removal, ordered with the document's publications")
    void reportsARemovalInOrder() {
        RecordingSink sink = new RecordingSink();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        publishAndReport(reporter, "docs:ada.md", false, document("Ada runs Acme.", 1L));
        GraphReporter.Publication removal = reporter.removed("docs", "docs:ada.md");
        assertThat(removal.removal()).isTrue();
        assertThat(removal.replacedEarlierVersion()).isFalse();
        report(reporter, removal, null);
        assertThat(sink.calls).containsExactly("publish docs:ada.md", "remove docs:ada.md");

        GraphReporter.Publication late = reporter.published("docs", "docs:ada.md", true);
        GraphReporter.Publication removedAgain = reporter.removed("docs", "docs:ada.md");
        report(reporter, removedAgain, null);
        report(reporter, late, document("Ada left Acme.", 2L));
        assertThat(sink.calls)
            .as("a version removed before its report ran must not stage facts about it")
            .containsExactly("publish docs:ada.md", "remove docs:ada.md", "remove docs:ada.md");

        GraphReporter.Publication stale = reporter.removed("docs", "docs:ada.md");
        publishAndReport(reporter, "docs:ada.md", false, document("Ada is back.", 3L));
        report(reporter, stale, null);
        assertThat(sink.calls).last()
            .as("a removal a later version superseded must not withdraw that version's facts")
            .isEqualTo("publish docs:ada.md");
    }

    @Test
    @DisplayName("refuses to report a published version without its document")
    void refusesAPublicationWithoutItsDocument() {
        GraphReporter reporter = new GraphReporter();
        GraphReporter.Publication publication = reporter.published("docs", "docs:ada.md", false);

        assertThatThrownBy(() -> report(reporter, publication, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("docs:ada.md");
    }

    @Test
    @DisplayName("counts a removal the graph refused, and never lets the failure out")
    void countsARefusedRemoval() {
        GraphReporter reporter = new GraphReporter();
        RecordingSink sink = new RecordingSink();
        sink.failure = new IllegalStateException("graph staging store is closed");
        reporter.setSink(sink);

        assertThatCode(() -> report(reporter, reporter.removed("docs", "docs:ada.md"), null))
            .doesNotThrowAnyException();
        assertThat(reporter.failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("runs one document's reports one at a time, and other documents' alongside")
    void serialisesOneDocumentsReports() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink() {
            @Override
            void record(String kind, String documentKey, String text, long lastModifiedMillis) {
                if ("slow".equals(text)) {
                    firstEntered.countDown();
                    try {
                        assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                super.record(kind, documentKey, text, lastModifiedMillis);
            }
        };
        GraphReporter reporter = new GraphReporter();
        reporter.setSink(sink);

        GraphReporter.Publication first = reporter.published("docs", "docs:ada.md", false);
        Thread slow = Thread.ofVirtual().start(() -> report(reporter, first, document("slow", 1L)));
        assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();

        publishAndReport(reporter, "docs:babbage.md", false, document("other", 1L));
        assertThat(sink.calls)
            .as("another document's report does not wait for this one's")
            .containsExactly("publish docs:babbage.md");

        GraphReporter.Publication second = reporter.published("docs", "docs:ada.md", true);
        Thread waiting = Thread.ofVirtual().start(
            () -> report(reporter, second, document("second", 2L)));
        waiting.join(200);
        assertThat(waiting.isAlive())
            .as("the replacement waits for the report it would otherwise race")
            .isTrue();

        releaseFirst.countDown();
        slow.join(TimeUnit.SECONDS.toMillis(30));
        waiting.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(sink.calls).containsExactly(
            "publish docs:babbage.md", "publish docs:ada.md", "replace docs:ada.md");
    }

    @Test
    @DisplayName("reports nothing without a sink, and never lets a sink's failure out")
    void toleratesNoSinkAndAFailingSink() {
        GraphReporter reporter = new GraphReporter();
        assertThatCode(() -> publishAndReport(reporter, "docs:ada.md", false, document(1L)))
            .doesNotThrowAnyException();

        RecordingSink sink = new RecordingSink();
        sink.failure = new IllegalStateException("staging refused");
        reporter.setSink(sink);
        assertThatCode(() -> publishAndReport(reporter, "docs:ada.md", true, document(1L)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("counts every document the graph refused, since nothing else records them")
    void countsRefusals() {
        GraphReporter reporter = new GraphReporter();
        RecordingSink sink = new RecordingSink();
        sink.failure = new IllegalStateException("graph staging store is closed");
        reporter.setSink(sink);

        publishAndReport(reporter, "docs:ada.md", false, document(1L));
        publishAndReport(reporter, "docs:babbage.md", true, document(1L));

        assertThat(reporter.failures()).isEqualTo(2);
    }

    @Test
    @DisplayName("forces what the sink staged, and a sink that cannot be forced does not throw")
    void syncsTheSink() {
        GraphReporter reporter = new GraphReporter();
        assertThat(reporter.sync()).as("nothing to force without a sink").isTrue();

        RecordingSink sink = new RecordingSink();
        reporter.setSink(sink);
        publishAndReport(reporter, "docs:ada.md", false, document(1L));
        assertThat(reporter.sync()).isTrue();
        assertThat(sink.calls).containsExactly("publish docs:ada.md", "sync");

        sink.syncFailure = new IOException("device gone");
        assertThat(reporter.sync()).isFalse();
    }
}
