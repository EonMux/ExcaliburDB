package io.excalibur.graph.build;

import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphBuilder;
import io.excalibur.graph.staging.GraphStagingStore;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.vector.EntityVectorWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stopping a graph build that is already running.
 *
 * <p>A build replays every staged fact, writes two Lucene indexes over them and embeds every
 * entity. On a real corpus that is minutes, and the operator who started one at the wrong moment
 * has to be able to end it without ending the process. What must not be negotiable is the state
 * it stops in: the graph is published by a rename, so a build that stops before that rename must
 * leave the previous graph exactly as it was rather than a half-published one.</p>
 */
@DisplayName("Cancelling a graph build")
class BuildCancellationTest {

    private static ExtractedTriple fact(String subject, String relation, String object) {
        return ExtractedTriple.alwaysValid(subject, relation, object,
            Provenance.of(SourceType.DOCUMENT, 1, 0.9, 1));
    }

    @Nested
    @DisplayName("the signal")
    class Signal {

        @Test
        @DisplayName("a checkpoint outside any scope does nothing")
        void noScopeNoCancellation() {
            assertThatCode(BuildCancellation::checkpoint).doesNotThrowAnyException();
            assertThat(BuildCancellation.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("a checkpoint inside a scope that has not been asked to stop does nothing")
        void anUncancelledScopeDoesNothing() {
            try (BuildCancellation.Scope scope = BuildCancellation.scope(() -> false)) {
                assertThat(scope).isNotNull();
                assertThatCode(BuildCancellation::checkpoint).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("a checkpoint inside a scope that has been asked to stop refuses to go on")
        void aCancelledScopeStops() {
            try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                assertThatThrownBy(BuildCancellation::checkpoint)
                    .isInstanceOf(BuildCancelledException.class);
            }
        }

        @Test
        @DisplayName("the scope ends with the build, so the next one on the thread is not "
            + "cancelled by it")
        void theScopeEnds() {
            try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                assertThat(BuildCancellation.isCancelled()).isTrue();
            }

            assertThat(BuildCancellation.isCancelled())
                .as("the pool thread is reused by every later build")
                .isFalse();
            assertThatCode(BuildCancellation::checkpoint).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("only the thread running the cancelled build is stopped")
        void oneThreadOnly() throws InterruptedException {
            AtomicReference<Throwable> onOtherThread = new AtomicReference<>();
            try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                // Ingestion appends and an erasure rebuild replays the same staging store from
                // its own thread. A cancellation that reached them would abort work nobody
                // asked to stop.
                Thread other = new Thread(() -> {
                    try {
                        BuildCancellation.checkpoint();
                    } catch (Throwable t) {
                        onOtherThread.set(t);
                    }
                });
                other.start();
                other.join();
            }

            assertThat(onOtherThread.get()).isNull();
        }
    }

    @Nested
    @DisplayName("a build that is stopped")
    class StoppedBuild {

        @Test
        @DisplayName("publishes nothing and leaves the graph it would have replaced readable")
        void theServedGraphSurvives(@TempDir Path tempDir) throws IOException {
            Path target = tempDir.resolve("graph");
            try (GraphStagingStore staging =
                     GraphStagingStore.open(tempDir.resolve("staging"))) {
                staging.append(fact("Ada", "knows", "Babbage"));
                staging.append(fact("Babbage", "designed", "Engine"));
                GraphBuilder.build(staging, target);

                staging.append(fact("Ada", "authored", "Notes"));
                staging.append(fact("Notes", "about", "Engine"));

                try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                    assertThatThrownBy(() -> GraphBuilder.build(staging, target))
                        .isInstanceOf(BuildCancelledException.class);
                }
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(store.edgeCount())
                    .as("a half-published graph is worse than no build at all")
                    .isEqualTo(2);
                assertThat(store.entityCount()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("leaves nothing behind that stops the next build from publishing")
        void theNextBuildStillWorks(@TempDir Path tempDir) throws IOException {
            Path target = tempDir.resolve("graph");
            try (GraphStagingStore staging =
                     GraphStagingStore.open(tempDir.resolve("staging"))) {
                staging.append(fact("Ada", "knows", "Babbage"));
                staging.append(fact("Babbage", "designed", "Engine"));

                try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                    assertThatThrownBy(() -> GraphBuilder.build(staging, target))
                        .isInstanceOf(BuildCancelledException.class);
                }

                GraphBuilder.BuildResult result = GraphBuilder.build(staging, target);

                assertThat(result.edges()).isEqualTo(2);
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(store.edgeCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("is noticed while the entity text is being replayed")
        void theTextReplayStops(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.appendEntityText(staging.entityIdOf("Ada"), "a mathematician");

                try (BuildCancellation.Scope ignored = BuildCancellation.scope(() -> true)) {
                    assertThatThrownBy(() ->
                        staging.forEachEntityText((entityId, snippet) -> { }))
                        .as("the lexical index and the vectors are both built from this replay")
                        .isInstanceOf(BuildCancelledException.class);
                }
            }
        }

        @Test
        @DisplayName("is noticed between batches of the entities being embedded")
        void theEmbeddingStops(@TempDir Path tempDir) throws IOException {
            // The embedding phase is the long one, and its only seam is the vector being
            // written for each entity a batch came back with.
            AtomicBoolean cancelled = new AtomicBoolean();
            try (EntityVectorWriter writer =
                     new EntityVectorWriter(tempDir.resolve("vectors"), 2, 4);
                 BuildCancellation.Scope ignored = BuildCancellation.scope(cancelled::get)) {

                writer.put(new float[] { 1f, 0f });
                cancelled.set(true);

                assertThatThrownBy(() -> writer.put(new float[] { 0f, 1f }))
                    .isInstanceOf(BuildCancelledException.class);
            }
        }
    }
}
