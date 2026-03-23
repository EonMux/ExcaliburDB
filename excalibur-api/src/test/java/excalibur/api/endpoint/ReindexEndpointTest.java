package excalibur.api.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReindexEndpoint and related classes.
 */
class ReindexEndpointTest {

    private ReindexEndpoint.InMemorySourceRegistry sourceRegistry;
    private ReindexEndpoint.InMemoryReindexStatusRegistry statusRegistry;

    @BeforeEach
    void setUp() {
        sourceRegistry = new ReindexEndpoint.InMemorySourceRegistry();
        statusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
    }

    @Test
    @DisplayName("ReindexStatus should have correct initial state")
    void testReindexStatusInitialState() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        assertEquals("test-source", status.sourceId());
        assertEquals("idle", status.state());
        assertEquals(0.0, status.progress());
        assertEquals(0L, status.startTimeMs());
        assertEquals(0L, status.entriesProcessed());
        assertEquals(0L, status.entriesTotal());
        assertNull(status.lastError());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should start correctly")
    void testReindexStatusStart() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        status.start();

        assertEquals("crawling", status.state());
        assertEquals(0.0, status.progress());
        assertTrue(status.startTimeMs() > 0);
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should update state correctly")
    void testReindexStatusUpdateState() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.updateState("embedding", 0.5, 500, 1000);

        assertEquals("embedding", status.state());
        assertEquals(0.5, status.progress());
        assertEquals(500L, status.entriesProcessed());
        assertEquals(1000L, status.entriesTotal());
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should complete correctly")
    void testReindexStatusComplete() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.complete();

        assertEquals("idle", status.state());
        assertEquals(1.0, status.progress());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus should fail correctly")
    void testReindexStatusFail() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");
        status.start();

        status.fail("Connection timeout");

        assertEquals("idle", status.state());
        assertEquals("Connection timeout", status.lastError());
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("ReindexStatus isInProgress should return true for active states")
    void testReindexStatusInProgress() {
        ReindexEndpoint.ReindexStatus status = new ReindexEndpoint.ReindexStatus("test-source");

        status.updateState("crawling", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("embedding", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("indexing", 0.0, 0, 0);
        assertTrue(status.isInProgress());

        status.updateState("idle", 0.0, 0, 0);
        assertFalse(status.isInProgress());
    }

    @Test
    @DisplayName("InMemorySourceRegistry should register and check sources")
    void testSourceRegistryRegisterAndCheck() {
        assertFalse(sourceRegistry.isRegistered("test-source"));

        sourceRegistry.register("test-source");
        assertTrue(sourceRegistry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should unregister sources")
    void testSourceRegistryUnregister() {
        sourceRegistry.register("test-source");
        sourceRegistry.unregister("test-source");

        assertFalse(sourceRegistry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should start reindex")
    void testStatusRegistryStartReindex() {
        statusRegistry.startReindex("test-source");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertNotNull(status);
        assertEquals("crawling", status.state());
        assertTrue(status.isInProgress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should update progress")
    void testStatusRegistryUpdateProgress() {
        statusRegistry.startReindex("test-source");
        statusRegistry.updateProgress("test-source", "embedding", 0.5, 500, 1000);

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("embedding", status.state());
        assertEquals(0.5, status.progress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should complete reindex")
    void testStatusRegistryCompleteReindex() {
        statusRegistry.startReindex("test-source");
        statusRegistry.completeReindex("test-source");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("idle", status.state());
        assertEquals(1.0, status.progress());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should fail reindex")
    void testStatusRegistryFailReindex() {
        statusRegistry.startReindex("test-source");
        statusRegistry.failReindex("test-source", "Test error");

        ReindexEndpoint.ReindexStatus status = statusRegistry.getStatus("test-source");
        assertEquals("idle", status.state());
        assertEquals("Test error", status.lastError());
    }

    @Test
    @DisplayName("InMemoryReindexStatusRegistry should get all statuses")
    void testStatusRegistryGetAllStatuses() {
        statusRegistry.startReindex("source-1");
        statusRegistry.startReindex("source-2");

        java.util.Map<String, ReindexEndpoint.ReindexStatus> all = statusRegistry.getAllStatuses();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("ReindexEndpoint should be created with default constructor")
    void testEndpointCreation() {
        ReindexEndpoint endpoint = new ReindexEndpoint();
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("ReindexEndpoint should be created with custom registries")
    void testEndpointWithCustomRegistries() {
        ReindexEndpoint endpoint = new ReindexEndpoint(sourceRegistry, statusRegistry);
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("ReindexEndpoint should reject null registries")
    void testEndpointNullRegistries() {
        assertThrows(NullPointerException.class, () ->
            new ReindexEndpoint(null, statusRegistry));
        assertThrows(NullPointerException.class, () ->
            new ReindexEndpoint(sourceRegistry, null));
    }
}
