package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether staging holds changes the graph published from it does not.
 *
 * <p>The served graph is rebuilt from staging, so it is current exactly when nothing has changed
 * in staging since the build that published it read it. That has to survive a restart - a node
 * that forgot it would either rebuild every namespace it holds or serve a withdrawn document's
 * facts until somebody asked for a build - and it must never be cleared by a build that did not
 * see a change made while it was running.</p>
 */
@DisplayName("Unpublished staging changes")
class UnpublishedChangesTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static ExtractedTriple fact(long document, String object) {
        return ExtractedTriple.alwaysValid("Ada Lovelace", "wrote", object,
            Provenance.of(SourceType.DOCUMENT, document, 0.9, 1));
    }

    /** Removes every fact and snippet of one document. */
    private static StagedErasure document(long document) {
        return new StagedErasure() {
            @Override
            public boolean erasesFact(int subject, int relation, int object, int validFrom,
                                      int validTo, Provenance provenance) {
                return provenance.sourceId() == document;
            }

            @Override
            public boolean erasesSnippet(int entityId, Provenance provenance) {
                return provenance.sourceId() == document;
            }

            @Override
            public boolean erasesLabel(int entityId) {
                return false;
            }
        };
    }

    private static GraphStagingStore open(Path directory) throws IOException {
        return GraphStagingStore.open(directory, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a store nothing was staged into has nothing unpublished")
    void freshStoreIsCurrent(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            GraphStagingStore.UnpublishedChanges changes = staging.unpublishedChanges();
            assertThat(changes.pending()).isFalse();
            assertThat(changes.pendingSince()).isEmpty();
            assertThat(changes.lastChangedAt()).isEmpty();
            assertThat(changes.changeCount()).isZero();
        }
        assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).doesNotExist();
    }

    @Test
    @DisplayName("a staged fact is unpublished, and says so on disk")
    void appendMarks(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));

            GraphStagingStore.UnpublishedChanges changes = staging.unpublishedChanges();
            assertThat(changes.pending()).isTrue();
            assertThat(changes.pendingSince()).contains(NOW);
            assertThat(changes.lastChangedAt()).contains(NOW);
            assertThat(changes.changeCount()).isEqualTo(1);
            assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).exists();
        }
    }

    @Test
    @DisplayName("recorded entity text is a change too")
    void entityTextMarks(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            int ada = staging.entityIdOf("Ada Lovelace");
            assertThat(staging.unpublishedChanges().pending())
                .as("assigning an id alone publishes nothing new").isFalse();

            staging.appendEntityText(ada, "Ada wrote Note G",
                Provenance.of(SourceType.DOCUMENT, 1, 0.9, 1));

            assertThat(staging.unpublishedChanges().pending()).isTrue();
        }
    }

    @Test
    @DisplayName("marking published clears the record when nothing changed since")
    void publishClears(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
            long asOf = staging.changeCount();

            assertThat(staging.markPublished(asOf)).isTrue();

            assertThat(staging.unpublishedChanges().pending()).isFalse();
            assertThat(staging.unpublishedChanges().pendingSince()).isEmpty();
            assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).doesNotExist();
        }
    }

    @Test
    @DisplayName("a change made after the build read staging keeps the record")
    void changeDuringBuildSurvives(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
            long asOf = staging.changeCount();
            staging.append(fact(2, "Bernoulli table"));

            assertThat(staging.markPublished(asOf)).isFalse();

            assertThat(staging.unpublishedChanges().pending()).isTrue();
            assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).exists();
        }
    }

    @Test
    @DisplayName("the record outlives the process, and so does its clearing")
    void survivesRestart(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
        }
        try (GraphStagingStore reopened = open(directory)) {
            GraphStagingStore.UnpublishedChanges changes = reopened.unpublishedChanges();
            assertThat(changes.pending()).isTrue();
            assertThat(changes.pendingSince()).isPresent();
            assertThat(changes.lastChangedAt()).isPresent();
            assertThat(reopened.markPublished(reopened.changeCount())).isTrue();
        }
        try (GraphStagingStore reopened = open(directory)) {
            assertThat(reopened.unpublishedChanges().pending()).isFalse();
        }
    }

    @Test
    @DisplayName("a withdrawal that removes something is a change, one that removes nothing is not")
    void withdrawalMarksOnlyWhatItRemoves(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
            staging.markPublished(staging.changeCount());

            assertThat(staging.erase(document(7)).removed()).isZero();
            assertThat(staging.unpublishedChanges().pending()).isFalse();
            assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).doesNotExist();

            assertThat(staging.erase(document(1)).removed()).isPositive();
            assertThat(staging.unpublishedChanges().pending()).isTrue();
            assertThat(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER)).exists();
        }
    }

    @Test
    @DisplayName("an erasure applied to the published graph as well changes nothing it lacks")
    void erasureAlongsidePublishedDoesNotMark(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
            staging.markPublished(staging.changeCount());

            assertThat(staging.eraseAlongsidePublished(document(1)).removed()).isPositive();

            assertThat(staging.unpublishedChanges().pending()).isFalse();
        }
    }

    @Test
    @DisplayName("an erasure alongside the published graph leaves an earlier change pending")
    void erasureAlongsidePublishedKeepsEarlierChanges(@TempDir Path directory)
            throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
            staging.append(fact(2, "Bernoulli table"));

            staging.eraseAlongsidePublished(document(1));

            assertThat(staging.unpublishedChanges().pending()).isTrue();
        }
    }

    @Test
    @DisplayName("staleness noticed from outside is recorded durably")
    void markUnpublished(@TempDir Path directory) throws IOException {
        try (GraphStagingStore staging = open(directory)) {
            staging.markUnpublished();
            assertThat(staging.unpublishedChanges().pending()).isTrue();
            assertThat(staging.unpublishedChanges().pendingSince()).contains(NOW);
        }
        try (GraphStagingStore reopened = open(directory)) {
            assertThat(reopened.unpublishedChanges().pending()).isTrue();
        }
    }

    @Test
    @DisplayName("the record is read from disk without opening the store")
    void readableOnDisk(@TempDir Path directory) throws IOException {
        assertThat(GraphStagingStore.unpublishedOnDisk(directory)).isEmpty();
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
        }
        assertThat(GraphStagingStore.unpublishedOnDisk(directory)).isPresent();
        Files.delete(directory.resolve(GraphStagingStore.UNPUBLISHED_MARKER));
        assertThat(GraphStagingStore.unpublishedOnDisk(directory)).isEmpty();
    }

    @Test
    @DisplayName("the last change on disk is the newest staging log")
    void lastChangedOnDisk(@TempDir Path directory) throws IOException {
        assertThat(GraphStagingStore.lastChangedOnDisk(directory)).isEmpty();
        try (GraphStagingStore staging = open(directory)) {
            staging.append(fact(1, "Note G"));
        }
        assertThat(GraphStagingStore.lastChangedOnDisk(directory)).isPresent();
    }
}
