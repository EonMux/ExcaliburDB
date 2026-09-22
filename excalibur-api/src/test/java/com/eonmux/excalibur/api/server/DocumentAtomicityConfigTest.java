package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.llm.embedding.EmbeddingClient;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That {@code excalibur.ingest.document-atomicity} decides whether the atomicity filter runs.
 *
 * <p>The key is parsed, has an accessor and is documented in the README and the example file as
 * removing the all-or-nothing visibility guarantee when set to {@code false}. It reached nothing:
 * every pipeline-configuration path installed the visibility checker whenever a tracker existed,
 * so a node configured with it off still filtered. A knob a user sets and the node ignores is
 * worse than one that does not exist, because the user believes the setting took.</p>
 */
@DisplayName("excalibur.ingest.document-atomicity")
class DocumentAtomicityConfigTest {

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
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(queryPipeline.getVisibilityChecker()).thenReturn(null);
        when(queryPipeline.withVisibilityChecker(org.mockito.ArgumentMatchers.any()))
            .thenReturn(queryPipeline);
    }

    @Test
    @DisplayName("installs the atomicity filter when it is on, which is the default")
    void onByDefault() {
        ExcaliburDBServer server = serverWith(config(null));

        server.withIngestionPipeline(mock(TextChunker.class), mock(EmbeddingClient.class),
            tracker());

        assertThat(server.isDocumentAtomicityEnabled()).isTrue();
        org.mockito.Mockito.verify(queryPipeline)
            .withVisibilityChecker(org.mockito.ArgumentMatchers.notNull());
    }

    @Test
    @DisplayName("leaves the filter out when it is turned off")
    void offWhenConfigured() {
        ExcaliburDBServer server = serverWith(config("false"));

        server.withIngestionPipeline(mock(TextChunker.class), mock(EmbeddingClient.class),
            tracker());

        assertThat(server.isDocumentAtomicityEnabled()).isFalse();
        org.mockito.Mockito.verify(queryPipeline, org.mockito.Mockito.never())
            .withVisibilityChecker(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("is on for a server built without a Config at all")
    void onWithoutConfig() {
        ExcaliburDBServer server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine,
            queryPipeline);

        assertThat(server.isDocumentAtomicityEnabled()).isTrue();
    }

    private DocumentVisibilityTracker tracker() {
        return new DocumentVisibilityTracker(new DocumentRegistry(),
            indexOrchestrator.getVisibilityTracker());
    }

    private ExcaliburDBServer serverWith(Config config) {
        return new ExcaliburDBServer(0, 0, indexOrchestrator, storageEngine, null, config,
            queryPipeline);
    }

    private Config config(String atomicity) {
        Map<String, String> props = new HashMap<>();
        if (atomicity != null) {
            props.put("excalibur.ingest.document-atomicity", atomicity);
        }
        return new Config(props);
    }
}
