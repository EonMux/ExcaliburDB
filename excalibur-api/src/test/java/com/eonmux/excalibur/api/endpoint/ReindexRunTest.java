package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for what a background reindex tells {@code GET /v1/reindex/status}.
 *
 * <p>The readback is the whole observable result of a reindex: the HTTP response went out at
 * 202, before any of this ran. Two properties are pinned here because both were wrong at some
 * point and neither is visible from the outside. A run that indexed most of its documents and
 * failed one is a completed run, not a failed one - reporting it as failed tells an operator to
 * roll something back that succeeded. And the readback carries no failure text, because
 * {@code GET /v1/reindex/status} with no source names every source to every holder of a read
 * token, and these messages are built from filesystem paths and endpoint URLs.</p>
 *
 * <p>Every reindex call here is stubbed with a progress listener alongside the source. The
 * listener is how the run publishes the counts {@code /v1/reindex/status} serves, so it is part
 * of the call rather than something set on the shared reindexer: one reindexer serves both the
 * HTTP route and the scheduler, and a listener set on it would be whichever run set it last.</p>
 */
@DisplayName("ReindexRun")
class ReindexRunTest {

    private static final String SOURCE_ID = "docs";

    private DocumentReindexer reindexer;
    private SourceResolver sourceResolver;
    private Source source;
    private ReindexEndpoint.InMemoryReindexStatusRegistry statusRegistry;

    @BeforeEach
    void setUp() throws Exception {
        reindexer = mock(DocumentReindexer.class);
        sourceResolver = mock(SourceResolver.class);
        source = mock(Source.class);
        statusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        statusRegistry.startReindex(SOURCE_ID);
        when(sourceResolver.resolve(SOURCE_ID)).thenReturn(source);
    }

    /**
     * Builds a runner for a node with nowhere to put a replacement segment.
     *
     * @return A runner that will take the in-place branch
     */
    private ReindexRun standardRun() {
        return new ReindexRun(reindexer, sourceResolver, null, null, statusRegistry, null);
    }

    /**
     * Builds a runner for a node that can roll its segments.
     *
     * @param volumeManager The volume manager, already told what it will hand out
     * @return A runner that will take the rolling branch
     */
    private ReindexRun rollingRun(VolumeManager volumeManager) {
        when(reindexer.hasRollingReplacement()).thenReturn(true);
        return new ReindexRun(reindexer, sourceResolver, volumeManager,
            mock(SegmentCatalog.class), statusRegistry, null);
    }

    /**
     * Builds a result for a run with the given outcome.
     *
     * @param indexed How many documents produced data
     * @param errors  Messages for the documents that did not
     * @return The result a reindexer would have returned
     */
    private static DocumentReindexer.ReindexResult result(int indexed, String... errors) {
        DocumentReindexer.ReindexResult.Builder builder =
            new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                .documentsIndexed(indexed)
                .chunksCreated(indexed * 3);
        for (String error : errors) {
            builder.addError("document", "doc-" + error, error);
        }
        return builder.build();
    }

    /**
     * Reads back the status a caller would see.
     *
     * @return The status recorded for the source under test
     */
    private ReindexEndpoint.ReindexStatus status() {
        return statusRegistry.getStatus(SOURCE_ID);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses the collaborators it cannot run without")
        void refusesMissingCollaborators() {
            assertThatThrownBy(() -> new ReindexRun(null, sourceResolver, null, null,
                statusRegistry, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReindexRun(reindexer, null, null, null,
                statusRegistry, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReindexRun(reindexer, sourceResolver, null, null,
                null, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("before any documents are read")
    class BeforeReading {

        @Test
        @DisplayName("a source that no longer resolves fails the run by name")
        void unresolvableSourceFails() throws Exception {
            when(sourceResolver.resolve(SOURCE_ID)).thenReturn(null);

            standardRun().execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError()).isEqualTo("Source not found: " + SOURCE_ID);
            verify(reindexer, never()).reindexAll(any(), any(), any());
        }

        @Test
        @DisplayName("a source that cannot be read fails without quoting the reason")
        void unreadableSourceFailsWithoutDetail() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any()))
                .thenThrow(new SourceException("https://internal.example/feed refused the token"));

            standardRun().execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError())
                .isEqualTo("Reindex failed (SourceException); see the node's log for the reason");
            assertThat(status().lastError()).doesNotContain("internal.example");
        }

        @Test
        @DisplayName("an Error ends the run as failed on its way up, rather than mid-reindex")
        void anErrorFailsTheRunAndIsRethrown() throws Exception {
            // Rethrown, because nothing here can recover from one. But a readback left reading
            // "crawling" has a caller polling it for a run that no longer exists.
            when(reindexer.reindexAll(eq(source), any(), any()))
                .thenThrow(new StackOverflowError("parser recursed"));

            assertThatThrownBy(() -> standardRun().execute(SOURCE_ID))
                .isInstanceOf(StackOverflowError.class);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError())
                .isEqualTo("Reindex failed (StackOverflowError); see the node's log for the "
                    + "reason");
        }
    }

    @Nested
    @DisplayName("reindexing in place")
    class Standard {

        @Test
        @DisplayName("a clean run completes")
        void cleanRunCompletes() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(12));

            standardRun().execute(SOURCE_ID);

            assertThat(status().state()).isEqualTo("idle");
            assertThat(status().progress()).isEqualTo(1.0);
            assertThat(status().lastError()).isNull();
        }

        @Test
        @DisplayName("one unreadable document does not fail a run that indexed the rest")
        void partialRunCompletes() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any()))
                .thenReturn(result(400, "one bad document"));

            standardRun().execute(SOURCE_ID);

            assertThat(status().state())
                .as("a run that reindexed 400 documents is not a failed reindex")
                .isEqualTo("idle");
        }

        @Test
        @DisplayName("a run that indexed nothing fails, counting the errors but not naming them")
        void emptyRunFails() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any()))
                .thenReturn(result(0, "/var/lib/excalibur/seg-4 is unwritable", "and again"));

            standardRun().execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError())
                .isEqualTo("Reindex failed completely with 2 errors"
                    + "; see the node's log for the reason");
            assertThat(status().lastError()).doesNotContain("/var/lib");
        }

        @Test
        @DisplayName("is taken when the node can roll segments but the reindexer cannot")
        void fallsBackWhenTheReindexerHasNoRollingSupport() throws Exception {
            when(reindexer.hasRollingReplacement()).thenReturn(false);
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(3));
            VolumeManager volumeManager = mock(VolumeManager.class);

            new ReindexRun(reindexer, sourceResolver, volumeManager, mock(SegmentCatalog.class),
                statusRegistry, null).execute(SOURCE_ID);

            verify(reindexer).reindexAll(eq(source), any(), any());
            verify(reindexer, never()).reindexWithRollingReplacement(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("rolling replacement")
    class Rolling {

        private final Path volume = Path.of("/var/lib/excalibur/vol-1");

        /**
         * Wraps a basic result as a rolling one, as a completed replacement would.
         *
         * @param basic What the underlying reindex produced
         * @return The rolling result, naming a new segment and one retired segment
         */
        private DocumentReindexer.RollingReindexResult rolling(
                DocumentReindexer.ReindexResult basic) {
            return new DocumentReindexer.RollingReindexResult(basic, 7L, List.of(3L));
        }

        @Test
        @DisplayName("a node with no volume fails rather than publishing over the live segments")
        void noVolumeFails() throws Exception {
            VolumeManager volumeManager = mock(VolumeManager.class);
            when(volumeManager.getVolumeWithMostSpace()).thenReturn(null);

            rollingRun(volumeManager).execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError()).isEqualTo("No volume available for reindex");
            verify(reindexer, never()).reindexWithRollingReplacement(any(), any(), any(), any());
            verify(reindexer, never()).reindexAll(any(), any(), any());
        }

        @Test
        @DisplayName("a clean replacement completes on the volume with the most space")
        void cleanReplacementCompletes() throws Exception {
            VolumeManager volumeManager = mock(VolumeManager.class);
            when(volumeManager.getVolumeWithMostSpace()).thenReturn(volume);
            when(reindexer.reindexWithRollingReplacement(eq(source), eq(volume), any(), any()))
                .thenReturn(rolling(result(9)));

            rollingRun(volumeManager).execute(SOURCE_ID);

            assertThat(status().state()).isEqualTo("idle");
            assertThat(status().progress()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("applies the same partial-success rule the in-place branch does")
        void partialReplacementCompletes() throws Exception {
            VolumeManager volumeManager = mock(VolumeManager.class);
            when(volumeManager.getVolumeWithMostSpace()).thenReturn(volume);
            when(reindexer.reindexWithRollingReplacement(eq(source), eq(volume), any(), any()))
                .thenReturn(rolling(result(400, "one bad document")));

            rollingRun(volumeManager).execute(SOURCE_ID);

            assertThat(status().state())
                .as("the two branches must not disagree about the same corpus")
                .isEqualTo("idle");
        }

        @Test
        @DisplayName("a replacement that indexed nothing fails without naming the failures")
        void emptyReplacementFails() throws Exception {
            VolumeManager volumeManager = mock(VolumeManager.class);
            when(volumeManager.getVolumeWithMostSpace()).thenReturn(volume);
            when(reindexer.reindexWithRollingReplacement(eq(source), eq(volume), any(), any()))
                .thenReturn(rolling(result(0, "https://internal.example/feed timed out")));

            rollingRun(volumeManager).execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().lastError())
                .isEqualTo("Reindex failed completely with 1 errors"
                    + "; see the node's log for the reason");
            assertThat(status().lastError()).doesNotContain("internal.example");
        }
    }
}
