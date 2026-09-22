package com.eonmux.excalibur.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every {@link MetricsRegistry} accessor reads the quantity it names.
 *
 * <p>Two failure modes are covered. First, cross-wiring: coverage and health are both
 * doubles in [0.0, 1.0] published by the same MBean, so returning one where the other was
 * asked for is invisible until someone acts on it. Every test here therefore sets the two
 * to different values. Second, dead fallbacks: when no MBean is registered the accessors
 * must fall back to the global {@link Metrics} state that production records into, not to a
 * registry-local copy that nothing ever writes.</p>
 *
 * @author ExcaliburDB
 */
class MetricsRegistryTest {

    private static final String[] MBEAN_NAMES = {
        "com.eonmux.excalibur:type=QueryLatency",
        "com.eonmux.excalibur:type=SystemHealth",
        "com.eonmux.excalibur:type=IngestionQueue",
        "com.eonmux.excalibur:type=VisibilityEpoch",
        "com.eonmux.excalibur:type=CuckooFpRate",
        "com.eonmux.excalibur:type=HnswQueueDepth",
        "com.eonmux.excalibur:type=IngestionThroughput"
    };

    private MBeanServer mbeanServer;
    private MetricsRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        unregisterAll();
        registry = new MetricsRegistry();
    }

    @AfterEach
    void tearDown() throws Exception {
        unregisterAll();
    }

    private void unregisterAll() throws Exception {
        for (String name : MBEAN_NAMES) {
            ObjectName objectName = new ObjectName(name);
            if (mbeanServer.isRegistered(objectName)) {
                mbeanServer.unregisterMBean(objectName);
            }
        }
    }

    @Test
    @DisplayName("Coverage and health are distinct values, through the MBeans")
    void coverageAndHealthAreNotInterchangeableViaMBean() throws Exception {
        Metrics.registerAll(registry);

        Metrics.setEstimatedCoverage(0.25);
        Metrics.setHealthScore(0.75);

        assertEquals(0.25, registry.getCoverageFromMBean(), 1e-9,
            "getCoverageFromMBean must report coverage");
        assertEquals(0.75, registry.getHealthScoreFromMBean(), 1e-9,
            "getHealthScoreFromMBean must report health");
        assertNotEquals(registry.getCoverageFromMBean(), registry.getHealthScoreFromMBean(),
            "the two accessors must not resolve to the same underlying value");
    }

    @Test
    @DisplayName("Coverage and health are distinct values, through the fallbacks")
    void coverageAndHealthAreNotInterchangeableViaFallback() {
        // No MBeans registered: every accessor takes its fallback path.
        Metrics.setEstimatedCoverage(0.4);
        Metrics.setHealthScore(0.9);

        assertEquals(0.4, registry.getCoverageFromMBean(), 1e-9,
            "the coverage fallback must read the coverage metric");
        assertEquals(0.9, registry.getHealthScoreFromMBean(), 1e-9,
            "the health fallback must read the health metric");
    }

    @Test
    @DisplayName("Latency fallbacks read the histogram production records into")
    void latencyFallbacksReadTheProductionHistogram() {
        Metrics.QueryLatency().reset();
        for (int i = 0; i < 20; i++) {
            Metrics.recordQueryLatency(120);
        }

        assertEquals(20, registry.getLatencyCount(),
            "the count fallback must see recorded latencies");
        assertTrue(registry.getLatencyPercentile(50) > 0,
            "the percentile fallback must see recorded latencies");
        assertEquals(Metrics.QueryLatency().getPercentile(50), registry.getLatencyPercentile(50),
            "the fallback and the production histogram must agree");
        assertSame(Metrics.QueryLatency(), registry.getQueryLatency(),
            "getQueryLatency must hand out the production histogram");
    }

    @Test
    @DisplayName("Epoch and queue depth fallbacks read the recorded values")
    void epochAndQueueDepthFallbacksReadRecordedValues() {
        Metrics.recordEpoch(77L);
        Metrics.recordHnswDepth(4321L);

        assertEquals(77L, registry.getEpochFromMBean(),
            "the epoch fallback must read the recorded epoch");
        assertEquals(4321L, registry.getQueueDepthFromMBean(),
            "the queue depth fallback must read the recorded depth");
    }

    @Test
    @DisplayName("The registry keeps no metric state of its own")
    void registryHasNoShadowState() {
        Metrics.setEstimatedCoverage(0.6);
        assertEquals(0.6, registry.getEstimatedCoverage(), 1e-9,
            "the registry must expose the global coverage, not a private copy");

        registry.setEstimatedCoverage(0.2);
        assertEquals(0.2, Metrics.getEstimatedCoverage(), 1e-9,
            "writing through the registry must update the global coverage");

        registry.setCurrentEpoch(99L);
        assertEquals(99L, Metrics.getCurrentEpoch(),
            "writing through the registry must update the global epoch");
        assertEquals(99L, registry.getCurrentEpoch(),
            "reading back through the registry must see the same epoch");
    }

    @Test
    @DisplayName("clear() empties the recorded measurements without rewinding the epoch")
    void clearEmptiesMeasurements() {
        Metrics.recordQueryLatency(500);
        Metrics.setEstimatedCoverage(0.8);
        Metrics.recordEpoch(1234L);

        registry.clear();

        assertEquals(0, Metrics.QueryLatency().getTotalCount(), "latencies are cleared");
        assertEquals(0.0, Metrics.getEstimatedCoverage(), 0.0, "coverage is cleared");
        assertEquals(1234L, Metrics.getCurrentEpoch(),
            "the visibility epoch is monotone state and must survive a metrics clear");
    }
}
