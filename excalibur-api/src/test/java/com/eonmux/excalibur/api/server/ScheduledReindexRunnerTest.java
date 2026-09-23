package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexRun;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a scheduled reindex that ends in an {@link Error} leaves behind.
 *
 * <p>An Error is not the runner's to swallow: the scheduler's thread is the one to learn of it.
 * But the readback it opened must not go on saying a re-crawl is running that nothing runs, and
 * the claim it took must not outlive it, or the source can never be reindexed again.</p>
 */
@DisplayName("Scheduled reindex runner")
class ScheduledReindexRunnerTest {

    private static final String SOURCE_ID = "s-1";

    private final ReindexCoordinator coordinator = new ReindexCoordinator();

    /** A run over a reindexer that ends every run the way {@code outcome} says. */
    private static ReindexRun runEndingIn(Error outcome,
                                          ReindexEndpoint.ReindexStatusRegistry statuses)
            throws Exception {
        DocumentReindexer reindexer = mock(DocumentReindexer.class);
        Source source = mock(Source.class);
        when(reindexer.reindexAll(any(Source.class), any(), any())).thenThrow(outcome);
        return new ReindexRun(reindexer, id -> source, null, null, statuses, null);
    }

    @Test
    @DisplayName("an Error inside the run is rethrown with the run failed and the claim released")
    void anErrorInTheRun() throws Exception {
        ReindexEndpoint.ReindexStatusRegistry statuses =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        ScheduledReindexRunner runner = new ScheduledReindexRunner(coordinator,
            runEndingIn(new OutOfMemoryError("simulated"), statuses), statuses);

        assertThatThrownBy(() -> runner.executeReindex(SOURCE_ID))
            .isInstanceOf(OutOfMemoryError.class);

        assertThat(statuses.getStatus(SOURCE_ID).state())
            .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
        assertThat(coordinator.isRunning(SOURCE_ID)).isFalse();
    }

    @Test
    @DisplayName("an Error before the run takes over still closes the run it opened")
    void anErrorBeforeTheRun() throws Exception {
        // Opened, and then something throws before the run is reached - the one window in which
        // nothing but the runner can close what it opened.
        ReindexEndpoint.ReindexStatusRegistry statuses =
            new ReindexEndpoint.InMemoryReindexStatusRegistry() {
                @Override
                public void startReindex(String sourceId) {
                    super.startReindex(sourceId);
                    throw new LinkageError("simulated");
                }
            };
        ScheduledReindexRunner runner = new ScheduledReindexRunner(coordinator,
            runEndingIn(new AssertionError("the run is never reached"), statuses), statuses);

        assertThatThrownBy(() -> runner.executeReindex(SOURCE_ID))
            .isInstanceOf(LinkageError.class);

        assertThat(statuses.getStatus(SOURCE_ID).state())
            .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
        assertThat(statuses.getStatus(SOURCE_ID).lastError())
            .isEqualTo("Reindex failed (LinkageError)");
        assertThat(coordinator.isRunning(SOURCE_ID)).isFalse();
    }
}
