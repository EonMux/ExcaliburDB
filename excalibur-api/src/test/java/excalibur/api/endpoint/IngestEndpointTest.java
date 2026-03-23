package excalibur.api.endpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IngestEndpoint and related classes.
 */
class IngestEndpointTest {

    private IngestEndpoint.InMemorySourceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new IngestEndpoint.InMemorySourceRegistry();
    }

    @Test
    @DisplayName("InMemorySourceRegistry should register and check sources")
    void testRegistryRegisterAndCheck() {
        assertFalse(registry.isRegistered("test-source"));

        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.isRegistered("test-source"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should get source by ID")
    void testRegistryGetSource() {
        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        excalibur.ingest.source.Source retrieved = registry.get("test-source");
        assertNotNull(retrieved);
        assertEquals("test-source", retrieved.getSourceId());
    }

    @Test
    @DisplayName("InMemorySourceRegistry should return null for unknown source")
    void testRegistryGetUnknownSource() {
        assertNull(registry.get("unknown"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should remove sources")
    void testRegistryRemove() {
        excalibur.ingest.source.Source mockSource = createMockSource("test-source");
        registry.register(mockSource);

        assertTrue(registry.remove("test-source"));
        assertFalse(registry.isRegistered("test-source"));
        assertFalse(registry.remove("non-existent"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should return all source IDs")
    void testRegistryGetAllSourceIds() {
        excalibur.ingest.source.Source source1 = createMockSource("source-1");
        excalibur.ingest.source.Source source2 = createMockSource("source-2");
        registry.register(source1);
        registry.register(source2);

        assertEquals(2, registry.getAllSourceIds().size());
        assertTrue(registry.getAllSourceIds().contains("source-1"));
        assertTrue(registry.getAllSourceIds().contains("source-2"));
    }

    @Test
    @DisplayName("InMemorySourceRegistry should reject null source")
    void testRegistryNullSource() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    @DisplayName("IngestEndpoint should be created with default constructor")
    void testEndpointCreation() {
        IngestEndpoint endpoint = new IngestEndpoint();
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestEndpoint should be created with custom registry")
    void testEndpointWithCustomRegistry() {
        IngestEndpoint endpoint = new IngestEndpoint(registry);
        assertNotNull(endpoint);
    }

    @Test
    @DisplayName("IngestEndpoint should reject null registry")
    void testEndpointNullRegistry() {
        assertThrows(NullPointerException.class, () -> new IngestEndpoint(null));
    }

    // Helper to create mock Source
    private excalibur.ingest.source.Source createMockSource(String sourceId) {
        return new excalibur.ingest.source.Source() {
            @Override
            public String getSourceId() {
                return sourceId;
            }

            @Override
            public String getSourceType() {
                return "mock";
            }

            @Override
            public java.util.stream.Stream<String> crawl() {
                return java.util.stream.Stream.empty();
            }

            @Override
            public boolean hasChanged(java.time.Instant since) {
                return false;
            }

            @Override
            public excalibur.ingest.source.FetchedDocument fetch(String id) {
                return null;
            }
        };
    }
}
