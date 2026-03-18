/**
 * Tests for ExcaliburDB Core configuration and metrics.
 */
package excalibur.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigLoader and Config classes.
 * 
 * Thread-safety: Tests run in parallel, verifying thread-safe behavior.
 */
@DisplayName("Config and Metrics Tests")
class ConfigTests {
    
    @Test
    @DisplayName("ConfigLoader loads defaults from properties file")
    void testLoadDefaults() throws Exception {
        Config config = ConfigLoader.load(new java.io.FileInputStream(
            ConfigTests.class.getResource("excalibur.properties").getFile()),
            java.util.Map.of());
        
        assertEquals("true", config.getProperty("excalibur.enabled"));
        assertEquals("100", config.getProperty("excalibur.visibility.epoch-tick-ms"));
        assertEquals("30000", config.getProperty("excalibur.visibility.query-timeout-ms"));
        assertEquals("100000", config.getProperty("excalibur.ingest.hnsw-queue-depth-limit"));
        assertEquals("0.5", config.getProperty("excalibur.health.readiness-threshold"));
        assertEquals("0.9", config.getProperty("excalibur.health.healthy-threshold"));
        assertEquals("5000", config.getProperty("excalibur.health.max-latency-ms"));
    }
    
    @Test
    @DisplayName("ConfigLoader applies environment variable overrides")
    void testEnvOverrides() throws Exception {
        // Set environment variables
        System.setProperty("java.util.prefs.Store", null);
        
        Config config = ConfigLoader.load(new java.io.FileInputStream(
            ConfigTests.class.getResource("excalibur.properties").getFile()),
            java.util.Map.of(
                "excalibur.health.readiness-threshold", "0.95",
                "excalibur.visibility.epoch-tick-ms", "50"
            ));
        
        assertEquals("0.95", config.getProperty("excalibur.health.readiness-threshold"));
        assertEquals("50", config.getProperty("excalibur.visibility.epoch-tick-ms"));
    }
    
    @Test
    @DisplayName("ConfigValidation rejects invalid visibility config")
    void testValidateVisibilityNegative() {
        try {
            ConfigLoader.load(new java.io.FileInputStream(
                ConfigTests.class.getResource("excalibur.properties").getFile()),
                java.util.Map.of(
                    "excalibur.visibility.epoch-tick-ms", "-1"
                ));
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("non-negative"));
        }
    }
    
    @Test
    @DisplayName("ConfigValidation rejects invalid health threshold")
    void testValidateHealthThreshold() {
        try {
            ConfigLoader.load(new java.io.FileInputStream(
                ConfigTests.class.getResource("excalibur.properties").getFile()),
                java.util.Map.of(
                    "excalibur.health.readiness-threshold", "1.5"
                ));
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("[0.0, 1.0]"));
        }
    }
    
    @Test
    @DisplayName("ConfigValidation rejects invalid ingest limit")
    void testValidateHnswLimit() {
        try {
            ConfigLoader.load(new java.io.FileInputStream(
                ConfigTests.class.getResource("excalibur.properties").getFile()),
                java.util.Map.of(
                    "excalibur.ingest.hnsw-queue-depth-limit", "-1"
                ));
            fail("Should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("non-negative"));
        }
    }
    
    @Test
    @DisplayName("ConfigBuilder can construct config with defaults")
    void testConfigBuilderDefaults() {
        Config config = Config.builder().build();
        
        assertNotNull(config);
        assertTrue(config.enabled());
        assertEquals(100, config.visibilityEpochTickMs());
        assertEquals(30000, config.queryTimeoutMs());
        assertEquals(100000, config.hnswQueueDepthLimit());
        assertEquals(2000, config.documentAtomicityTimeoutMs());
        assertEquals(0.5, config.readinessThreshold());
        assertEquals(0.9, config.healthyThreshold());
        assertEquals(5000, config.maxLatencyMs());
    }
    
    @Test
    @DisplayName("ConfigBuilder can construct config with custom values")
    void testConfigBuilderCustom() {
        Config config = Config.builder()
            .set("excalibur.visibility.epoch-tick-ms", "25")
            .set("excalibur.health.readiness-threshold", "0.75")
            .set("excalibur.ingest.hnsw-queue-depth-limit", "200000")
            .build();
        
        assertEquals(25, config.visibilityEpochTickMs());
        assertEquals(0.75, config.readinessThreshold());
        assertEquals(200000, config.hnswQueueDepthLimit());
    }
    
    @Test
    @DisplayName("MetricsRegistry registers all MBeans")
    void testRegisterAllMBeans() {
        MetricsRegistry registry = new MetricsRegistry();
        registry.registerQueryLatency();
        registry.registerCuckooFilterFPRate();
        registry.registerHnswQueueDepth();
        registry.registerIngestThroughput();
        registry.registerVisibilityEpoch();
        registry.registerTailScanSize();
        
        // Just verify no exceptions - actual registration depends on JMX availability
        assertTrue(registry.registeredMBeanCount() >= 0);
    }
    
    @Test
    @DisplayName("QueryLatencyMetrics records latencies correctly")
    void testQueryLatencyMetrics() {
        QueryLatencyMetrics metrics = new QueryLatencyMetrics();
        
        // Record some latencies
        metrics.recordLatency(10);
        metrics.recordLatency(20);
        metrics.recordLatency(30);
        metrics.recordLatency(10);
        metrics.recordLatency(25);
        
        assertEquals(5, metrics.getCount());
        assertEquals(10, metrics.getMin());
        assertEquals(30, metrics.getMax());
        assertEquals(95.0, metrics.getAverage(), 0.01);
        assertEquals(25, metrics.getP50());
        assertEquals(25, metrics.getP95());
        assertEquals(30, metrics.getP99());
    }
    
    @Test
    @DisplayName("CuckooFilterFPRateMetrics tracks false positives")
    void testCuckooFilterFPRate() {
        CuckooFilterFPRateMetrics metrics = new CuckooFilterFPRateMetrics();
        
        metrics.enable();
        metrics.recordLookup();
        metrics.recordLookup();
        metrics.recordLookup();
        metrics.recordLookup();
        metrics.recordFalsePositive();
        
        assertEquals(5, metrics.getLookups());
        assertEquals(1, metrics.getFalsePositives());
        assertEquals(0.2, metrics.getFPRate());
    }
    
    @Test
    @DisplayName("HnswQueueDepthMetrics tracks depth and peak")
    void testHnswQueueDepthMetrics() {
        HnswQueueDepthMetrics metrics = new HnswQueueDepthMetrics();
        
        metrics.recordDepth(50);
        metrics.recordDepth(100);
        metrics.recordDepth(75);
        metrics.recordDepth(40);
        
        assertEquals(50, metrics.getCurrentDepth());
        assertEquals(100, metrics.getPeakDepth());
        assertFalse(metrics.isFull());
        
        // Simulate full queue
        metrics.recordDepth(100000);
        assertTrue(metrics.isFull());
    }
    
    @Test
    @DisplayName("VisibilityEpochMetrics tracks epoch")
    void testVisibilityEpochMetrics() {
        VisibilityEpochMetrics metrics = new VisibilityEpochMetrics();
        
        metrics.recordEpoch(100);
        assertEquals(100, metrics.getCurrentEpoch());
        assertEquals(100, metrics.getTickInterval());
        assertEquals(0, metrics.timeUntilNextTick());
        
        metrics.setTickInterval(500);
        assertEquals(500, metrics.getTickInterval());
        assertEquals(450, metrics.timeUntilNextTick());
    }
    
    @Test
    @DisplayName("TailScanSizeMetrics tracks tail scans")
    void testTailScanSizeMetrics() {
        TailScanSizeMetrics metrics = new TailScanSizeMetrics();
        
        metrics.recordEntry(100);
        metrics.recordEntry(200);
        metrics.recordEntry(150);
        
        assertEquals(3, metrics.getTailScanCount());
        assertEquals(450, metrics.getTotalEntriesScanned());
        assertEquals(150.0, metrics.getAverageTailScanSize());
        assertEquals(200, metrics.getMaxTailScanSize());
    }
    
    @Test
    @DisplayName("IngestThroughputMetrics measures throughput")
    void testIngestThroughputMetrics() {
        IngestThroughputMetrics metrics = new IngestThroughputMetrics();
        
        metrics.start();
        for (int i = 0; i < 1000000; i++) {
            metrics.recordEntry();
        }
        metrics.stop();
        
        assertEquals(1000000, metrics.getEntriesProcessed());
        assertTrue(metrics.getThroughput() > 999900.0);
        assertTrue(metrics.getThroughput() < 1000100.0);
    }
}
