package excalibur.api.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IngestStatusEndpoint and related classes.
 */
class IngestStatusEndpointTest {

    private IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
    }

    @Test
    @DisplayName("IngestionStatus should have correct initial state")
    void testIngestionStatusInitialState() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");

        assertEquals("test-source", status.sourceId());
        assertEquals("idle", status.state());
        assertEquals(0.0, status.progress());
        assertEquals(0L, status.entriesIngested());
        assertEquals(0L, status.entriesPending());
        assertNull(status.lastError());
    }

    @Test
    @DisplayName("IngestionStatus should update correctly")
    void testIngestionStatusUpdate() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");

        status.update("crawling", 0.5, 100, 50);

        assertEquals("crawling", status.state());
        assertEquals(0.5, status.progress());
        assertEquals(100L, status.entriesIngested());
        assertEquals(50L, status.entriesPending());
    }

    @Test
    @DisplayName("IngestionStatus should clamp progress to valid range")
    void testIngestionStatusProgressClamp() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");

        status.update("crawling", -0.5, 0, 0);
        assertEquals(0.0, status.progress());

        status.update("crawling", 1.5, 0, 0);
        assertEquals(1.0, status.progress());
    }

    @Test
    @DisplayName("IngestionStatus should clamp entries to non-negative")
    void testIngestionStatusEntriesClamp() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");

        status.update("crawling", 0.5, -10, -5);
        assertEquals(0L, status.entriesIngested());
        assertEquals(0L, status.entriesPending());
    }

    @Test
    @DisplayName("IngestionStatus should set error correctly")
    void testIngestionStatusSetError() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");

        status.setError("Connection failed");

        assertEquals("Connection failed", status.lastError());
        assertEquals("idle", status.state());
    }

    @Test
    @DisplayName("a run that has started supersedes the error from the run before it")
    void testUpdateClearsAStaleError() {
        // An operator reading last_error has no way to tell which run it belongs to. Left in
        // place, an error from a run that has since been retried successfully reads as a
        // failure of the run that just finished.
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");
        status.setError("Connection failed");

        status.update("crawling", 0.0, 0, 0);

        assertNull(status.lastError());
        assertEquals("crawling", status.state());
    }

    @Test
    @DisplayName("an error reported once the run is over is kept")
    void testIdleUpdateKeepsAnErrorFromThisRun() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");
        status.update("crawling", 0.0, 0, 0);
        status.update("idle", 1.0, 5, 0);

        status.setError("3 of 8 documents were not ingested");

        assertEquals("3 of 8 documents were not ingested", status.lastError());
        assertEquals("idle", status.state());
    }

    @Test
    @DisplayName("IngestionStatus should reset correctly")
    void testIngestionStatusReset() {
        IngestStatusEndpoint.IngestionStatus status = new IngestStatusEndpoint.IngestionStatus("test-source");
        status.update("indexing", 0.75, 1000, 500);
        status.setError("Some error");

        status.reset();

        assertEquals("idle", status.state());
        assertEquals(0.0, status.progress());
        assertEquals(0L, status.entriesIngested());
        assertEquals(0L, status.entriesPending());
        assertNull(status.lastError());
    }

    @Test
    @DisplayName("IngestionStatus should reject null sourceId")
    void testIngestionStatusNullSourceId() {
        assertThrows(NullPointerException.class, () ->
            new IngestStatusEndpoint.IngestionStatus(null));
    }

    @Test
    @DisplayName("InMemoryIngestionStatusRegistry should create and get status")
    void testRegistryGetOrCreate() {
        IngestStatusEndpoint.IngestionStatus status = registry.getOrCreate("test-source");

        assertNotNull(status);
        assertEquals("test-source", status.sourceId());
    }

    @Test
    @DisplayName("InMemoryIngestionStatusRegistry should return same instance for same ID")
    void testRegistrySameInstance() {
        IngestStatusEndpoint.IngestionStatus status1 = registry.getOrCreate("test-source");
        IngestStatusEndpoint.IngestionStatus status2 = registry.getOrCreate("test-source");

        assertSame(status1, status2);
    }

    @Test
    @DisplayName("InMemoryIngestionStatusRegistry should get status")
    void testRegistryGetStatus() {
        registry.getOrCreate("test-source");

        IngestStatusEndpoint.IngestionStatus status = registry.getStatus("test-source");
        assertNotNull(status);

        assertNull(registry.getStatus("unknown"));
    }

    @Test
    @DisplayName("InMemoryIngestionStatusRegistry should get all statuses")
    void testRegistryGetAllStatuses() {
        registry.getOrCreate("source-1");
        registry.getOrCreate("source-2");

        java.util.Map<String, IngestStatusEndpoint.IngestionStatus> all = registry.getAllStatuses();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("InMemoryIngestionStatusRegistry should remove status")
    void testRegistryRemove() {
        registry.getOrCreate("test-source");
        registry.remove("test-source");

        assertNull(registry.getStatus("test-source"));
    }

    @Test
    @DisplayName("IngestStatusEndpoint should be created with default constructor")
    void testEndpointCreation() {
        IngestStatusEndpoint endpoint = new IngestStatusEndpoint();
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestStatusEndpoint should be created with custom registry")
    void testEndpointWithCustomRegistry() {
        IngestStatusEndpoint endpoint = new IngestStatusEndpoint(registry);
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestStatusEndpoint should reject null registry")
    void testEndpointNullRegistry() {
        assertThrows(NullPointerException.class, () ->
            new IngestStatusEndpoint(null));
    }
}
