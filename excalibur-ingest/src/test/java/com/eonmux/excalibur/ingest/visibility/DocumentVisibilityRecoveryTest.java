package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a restart does to a document that was half written when the node died.
 *
 * <p>A document is marked in flight before its first chunk is written and published only once
 * every chunk is durable, so a live query never sees half of one. A crash in between leaves those
 * chunks in storage, reachable by any scan: if the mark does not outlive the process, the restarted
 * node has never heard of the document, passes it through, and serves exactly the partial document
 * the mark exists to hide.</p>
 *
 * <p>These tests stage that crash by abandoning a tracker mid-document and opening a new one over
 * the same data directory, which is what a restart is from this class's point of view.</p>
 */
class DocumentVisibilityRecoveryTest {

    private static final String KEY = "docs:half-written.md";

    @Test
    @DisplayName("a document interrupted mid-ingest is still hidden after a restart")
    void anInterruptedDocumentStaysHiddenAcrossARestart(@TempDir Path dataDir) {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion(KEY, 2);
        before.addPendingChunk(KEY, KEY + ":chunk:0");
        // The crash: the chunk is in storage, the document was never published, nothing else ran.

        DocumentVisibilityTracker after = new DocumentVisibilityTracker(registry(dataDir));

        assertThat(after.isDocumentTracked(KEY))
            .as("the restarted node has to know this document was in flight, or it shows it")
            .isTrue();
        assertThat(after.isDocumentFullyVisible(KEY, Long.MAX_VALUE))
            .as("a document whose ingest never finished is not a document to serve")
            .isFalse();
        assertThat(after.isChunkVisible(KEY + ":chunk:0", Long.MAX_VALUE))
            .as("and neither is the one chunk of it that reached storage")
            .isFalse();
    }

    @Test
    @DisplayName("a document that finished ingesting is not hidden by a later restart")
    void aPublishedDocumentIsNotHeldBackAfterARestart(@TempDir Path dataDir) {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion(KEY, 1);
        before.addPendingChunk(KEY, KEY + ":chunk:0");
        before.registerDocument(KEY, List.of(KEY + ":chunk:0"), before.advanceEpoch());

        DocumentVisibilityTracker after = new DocumentVisibilityTracker(registry(dataDir));

        assertThat(after.isDocumentTracked(KEY))
            .as("a published document is unknown to a new tracker, and unknown is passed through")
            .isFalse();
    }

    @Test
    @DisplayName("a rolled-back document is not hidden by a later restart")
    void anAbortedDocumentIsForgottenAcrossARestart(@TempDir Path dataDir) {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion(KEY, 1);
        before.addPendingChunk(KEY, KEY + ":chunk:0");
        before.abortIngestion(KEY);

        assertThat(new DocumentVisibilityTracker(registry(dataDir)).isDocumentTracked(KEY))
            .as("rollback removed the chunks, so there is nothing left to hide")
            .isFalse();
    }

    @Test
    @DisplayName("an interrupted document is released by finishing its ingest later")
    void completingAnInterruptedDocumentReleasesIt(@TempDir Path dataDir) {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion(KEY, 1);

        DocumentVisibilityTracker after = new DocumentVisibilityTracker(registry(dataDir));
        after.registerDocument(KEY, List.of(KEY + ":chunk:0"), after.advanceEpoch());

        assertThat(after.isDocumentFullyVisible(KEY, after.currentEpoch()))
            .as("the re-crawl that rewrote the document is what makes it whole again")
            .isTrue();
        assertThat(new DocumentVisibilityTracker(registry(dataDir)).isDocumentTracked(KEY))
            .as("and the mark does not come back at the next restart")
            .isFalse();
    }

    @Test
    @DisplayName("a document whose ingest never finished stays hidden over repeated restarts")
    void theMarkSurvivesMoreThanOneRestart(@TempDir Path dataDir) {
        DocumentVisibilityTracker first = new DocumentVisibilityTracker(registry(dataDir));
        first.beginIngestion(KEY, 3);

        new DocumentVisibilityTracker(registry(dataDir));
        DocumentVisibilityTracker third = new DocumentVisibilityTracker(registry(dataDir));

        assertThat(third.isDocumentTracked(KEY)).isTrue();
        assertThat(third.isDocumentFullyVisible(KEY, Long.MAX_VALUE)).isFalse();
    }

    @Test
    @DisplayName("a node that keeps no registry on disk keeps no in-flight marks either")
    void anInMemoryRegistryTracksNothingAcrossARestart() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        tracker.beginIngestion(KEY, 1);

        assertThat(new DocumentVisibilityTracker(new DocumentRegistry()).isDocumentTracked(KEY))
            .as("there is no data directory to write the mark to, and none is invented")
            .isFalse();
    }

    /**
     * Opens the registry the way the node does, which is what names the data directory.
     */
    private static DocumentRegistry registry(Path dataDir) {
        return new DocumentRegistry(dataDir.resolve("document-registry"));
    }

    @Test
    @DisplayName("an erased document leaves no in-flight mark behind")
    void removingADocumentClearsItsMark(@TempDir Path dataDir) throws IOException {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion(KEY, 1);
        before.removeDocument(KEY);

        assertThat(new DocumentVisibilityTracker(registry(dataDir)).isDocumentTracked(KEY))
            .isFalse();
    }

    @Test
    @DisplayName("registering from metadata releases the mark the ingest began with")
    void registeringFromMetadataReleasesTheMark(@TempDir Path dataDir) {
        DocumentVisibilityTracker before = new DocumentVisibilityTracker(registry(dataDir));
        before.beginIngestion("docs:paper.md", 1);
        before.registerFromMetadata(DocumentMetadata.builder()
            .sourceId("docs")
            .documentId("paper.md")
            .lastModifiedMillis(1L)
            .contentHash(2L)
            .contentLength(3L)
            .indexTimeMillis(4L)
            .chunkIds(List.of("docs:paper.md:chunk:0"))
            .build(), 1);

        assertThat(new DocumentVisibilityTracker(registry(dataDir)).isDocumentTracked("docs:paper.md"))
            .isFalse();
    }
}
