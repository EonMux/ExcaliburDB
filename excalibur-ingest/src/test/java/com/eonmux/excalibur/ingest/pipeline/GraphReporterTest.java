package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.source.FetchedDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What ingestion hands the graph about a published document.
 *
 * <p>The text is what facts are extracted from; the date is what they are ordered by when a later
 * document gives a single-valued relation a different value. A reporter that dropped the date
 * would leave every fact ordered by the day it happened to be crawled.</p>
 */
@DisplayName("GraphReporter")
class GraphReporterTest {

    private record Reported(String documentKey, String text, long lastModifiedMillis) {
    }

    private static FetchedDocument document(long lastModifiedMillis) {
        return new FetchedDocument("ada.md", "Ada runs Acme.".getBytes(StandardCharsets.UTF_8),
            "text/plain", lastModifiedMillis);
    }

    @Test
    @DisplayName("passes the document's own date along with its text")
    void passesTheDocumentDate() {
        List<Reported> reported = new ArrayList<>();
        GraphReporter reporter = new GraphReporter();
        reporter.setSink((key, text, lastModified) ->
            reported.add(new Reported(key, text, lastModified)));

        reporter.report("docs:ada.md", document(1_620_000_000_000L));
        reporter.report("docs:undated.md", document(0L));

        assertThat(reported).containsExactly(
            new Reported("docs:ada.md", "Ada runs Acme.", 1_620_000_000_000L),
            new Reported("docs:undated.md", "Ada runs Acme.", 0L));
    }

    @Test
    @DisplayName("reports nothing without a sink, and never lets a sink's failure out")
    void toleratesNoSinkAndAFailingSink() {
        GraphReporter reporter = new GraphReporter();
        assertThatCode(() -> reporter.report("docs:ada.md", document(1L)))
            .doesNotThrowAnyException();

        reporter.setSink((key, text, lastModified) -> {
            throw new IllegalStateException("staging refused");
        });
        assertThatCode(() -> reporter.report("docs:ada.md", document(1L)))
            .doesNotThrowAnyException();
    }
}
