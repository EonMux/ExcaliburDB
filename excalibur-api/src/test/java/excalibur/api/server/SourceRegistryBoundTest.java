package excalibur.api.server;

import excalibur.api.endpoint.InMemorySourceRegistry;
import excalibur.api.security.ApiSecurityConfig;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.shard.IndexOrchestrator;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The configured bound on registered ingestion sources has to reach the registry.
 *
 * <p>Every registered source is retained for the process lifetime, and an HTTP source pins a
 * client and its selector thread, so the bound is what separates a bounded registry from a
 * resource leak an authenticated caller can drive. It was parsed, validated and documented while
 * the server built the registry with the compiled-in default, so setting it had no effect in
 * either direction.</p>
 */
@DisplayName("Source registry bound")
class SourceRegistryBoundTest {

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;

    @BeforeEach
    void setUp() {
        SegmentCatalog catalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);
        when(indexOrchestrator.getVisibilityTracker()).thenReturn(new VisibilityTracker(catalog));
    }

    @Test
    @DisplayName("the registry is built with the configured bound")
    void registryHonoursTheConfiguredBound() {
        ExcaliburDBServer server =
            new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(ApiSecurityConfig.fromProperties(
            Map.of(ApiSecurityConfig.KEY_MAX_SOURCES, "16")));

        assertThat(((InMemorySourceRegistry) server.getIngestSourceRegistry()).getMaxSources())
            .isEqualTo(16);
    }

    @Test
    @DisplayName("the default applies when nothing is configured")
    void theDefaultAppliesOtherwise() {
        ExcaliburDBServer server =
            new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);

        assertThat(((InMemorySourceRegistry) server.getIngestSourceRegistry()).getMaxSources())
            .isEqualTo(server.getSecurity().maxRegisteredSources());
    }
}
