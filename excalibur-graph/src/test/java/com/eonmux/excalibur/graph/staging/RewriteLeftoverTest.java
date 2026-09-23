package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A staging log is rewritten beside itself and renamed over, so a crash can leave the rewrite
 * behind: the log's surviving records, which an erasure applied later may cover. Nothing can be
 * rewriting a log before the store that owns it has opened, so the open removes them.
 */
@DisplayName("Staging rewrite leftovers")
class RewriteLeftoverTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 7, 0.9, 1);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        GraphStagingStore.TRIPLE_LOG + ".erasing",
        GraphStagingStore.TRIPLE_LOG + ".rewriting",
        GraphStagingStore.ENTITY_TEXT_LOG + ".rewriting",
        GraphStagingStore.ENTITY_LABELS + ".rewriting",
        GraphStagingStore.RELATION_LABELS + ".rewriting"
    })
    @DisplayName("a rewrite a crash left beside a log is removed when the store opens")
    void removedOnOpen(String leftover, @TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            staging.append(ExtractedTriple.alwaysValid("Alice", "knows", "Bob", DOCUMENT));
        }
        Path file = dir.resolve(leftover);
        Files.writeString(file, "Alice lives at 14 Elm Street");

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            assertThat(file).doesNotExist();
            assertThat(staging.stagedCount()).as("the log itself is untouched").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a file the store never writes is left alone")
    void otherFilesAreLeftAlone(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Path notes = dir.resolve("notes.rewriting");
        Files.writeString(notes, "kept");

        try (GraphStagingStore ignored = GraphStagingStore.open(dir)) {
            assertThat(notes).exists();
        }
    }
}
