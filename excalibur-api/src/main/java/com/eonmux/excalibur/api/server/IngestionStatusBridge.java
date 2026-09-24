package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.IngestStatusEndpoint;
import com.eonmux.excalibur.ingest.pipeline.IngestionOrchestrator;

import java.util.Objects;

/**
 * Reports an ingestion run's progress onto what {@code GET /v1/ingest/status} serves.
 *
 * <p>The orchestrator lives in {@code excalibur-ingest} and the status registry behind the
 * endpoint lives here, so neither can name the other's type. This adapter is the one place the
 * two are joined: it implements the orchestrator's registry interface and forwards every update
 * to the endpoint's, per source id.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe to the extent the wrapped registry is: this class holds no state of its own and
 * ingestion calls it from the virtual threads it runs its sources on.</p>
 *
 * @author ExcaliburDB
 */
final class IngestionStatusBridge implements IngestionOrchestrator.StatusRegistry {

    private final IngestStatusEndpoint.IngestionStatusRegistry delegate;

    /**
     * Creates a bridge onto the registry the status endpoint serves.
     *
     * @param delegate Where the endpoint reads a source's progress from
     * @throws NullPointerException if delegate is null
     */
    IngestionStatusBridge(IngestStatusEndpoint.IngestionStatusRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public IngestionOrchestrator.IngestionStatus getOrCreate(String sourceId) {
        final IngestStatusEndpoint.IngestionStatus status = delegate.getOrCreate(sourceId);
        return new IngestionOrchestrator.IngestionStatus() {
            @Override
            public void update(String state, double progress,
                               long entriesIngested, long entriesPending) {
                status.update(state, progress, entriesIngested, entriesPending);
            }

            @Override
            public void setSkipped(long entriesSkipped) {
                status.setSkipped(entriesSkipped);
            }

            @Override
            public void setDisallowed(long entriesDisallowed) {
                status.setDisallowed(entriesDisallowed);
            }

            @Override
            public void setFailed(long documentsFailed) {
                status.setFailed(documentsFailed);
            }

            @Override
            public void setError(String error) {
                status.setError(error);
            }
        };
    }
}
