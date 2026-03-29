package io.excalibur.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for JMX MBean visibility.
 * Verifies that all MBeans are properly registered and visible via JMX.
 *
 * <h2>Thread-Safety</h2>
 * <p>This test class is not thread-safe. Tests should be run sequentially.</p>
 *
 * @author ExcaliburDB
 */
class JmxMBeanIntegrationTest {

    private MBeanServer mbeanServer;
    private MetricsRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();
        registry = new MetricsRegistry();

        // Unregister any existing MBeans from previous test runs
        unregisterAllExcaliburMBeans();

        // Register all MBeans
        Metrics.registerAll(registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up after test
        unregisterAllExcaliburMBeans();
    }

    private void unregisterAllExcaliburMBeans() throws Exception {
        String[] mbeanNames = {
            "io.excalibur:type=QueryLatency",
            "io.excalibur:type=SystemHealth",
            "io.excalibur:type=IngestionQueue",
            "io.excalibur:type=VisibilityEpoch",
            "io.excalibur:type=CuckooFpRate",
            "io.excalibur:type=HnswQueueDepth",
            "io.excalibur:type=IngestionThroughput"
        };

        for (String name : mbeanNames) {
            ObjectName objectName = new ObjectName(name);
            if (mbeanServer.isRegistered(objectName)) {
                mbeanServer.unregisterMBean(objectName);
            }
        }
    }

    @Test
    @DisplayName("QueryLatency MBean should be registered and visible")
    void queryLatencyMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=QueryLatency");

        assertTrue(mbeanServer.isRegistered(objectName),
            "QueryLatency MBean should be registered in JMX");

        // Verify it implements DynamicMBean
        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("Query Latency MBean", info.getDescription(),
            "MBean description should match");

        // Verify attributes exist
        assertAttributeExists(info, "count");
        assertAttributeExists(info, "p50");
        assertAttributeExists(info, "p90");
        assertAttributeExists(info, "p95");
        assertAttributeExists(info, "p99");
        assertAttributeExists(info, "p99.9");
        assertAttributeExists(info, "average");
        assertAttributeExists(info, "minimum");
        assertAttributeExists(info, "maximum");
    }

    @Test
    @DisplayName("SystemHealth MBean should be registered and visible")
    void systemHealthMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=SystemHealth");

        assertTrue(mbeanServer.isRegistered(objectName),
            "SystemHealth MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("System Health MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "health");
        assertAttributeExists(info, "isHealthy");
        assertAttributeExists(info, "ready");
    }

    @Test
    @DisplayName("IngestionQueue MBean should be registered and visible")
    void ingestionQueueMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=IngestionQueue");

        assertTrue(mbeanServer.isRegistered(objectName),
            "IngestionQueue MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("Ingestion Queue MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "depth");
        assertAttributeExists(info, "percent");
        assertAttributeExists(info, "isUnderControl");
        assertAttributeExists(info, "isCritical");
        assertAttributeExists(info, "isFull");
    }

    @Test
    @DisplayName("VisibilityEpoch MBean should be registered and visible")
    void visibilityEpochMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=VisibilityEpoch");

        assertTrue(mbeanServer.isRegistered(objectName),
            "VisibilityEpoch MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("Visibility Epoch MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "epoch");
    }

    @Test
    @DisplayName("CuckooFpRate MBean should be registered and visible")
    void cuckooFpRateMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=CuckooFpRate");

        assertTrue(mbeanServer.isRegistered(objectName),
            "CuckooFpRate MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("Cuckoo Filter False Positive Rate MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "fpRate");
        assertAttributeExists(info, "fpRateEpoch");
        assertAttributeExists(info, "fpRateCount");
        assertAttributeExists(info, "avgFpRate");
        assertAttributeExists(info, "maxFpRate");
        assertAttributeExists(info, "currentEpoch");
    }

    @Test
    @DisplayName("HnswQueueDepth MBean should be registered and visible")
    void hnswQueueDepthMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=HnswQueueDepth");

        assertTrue(mbeanServer.isRegistered(objectName),
            "HnswQueueDepth MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("HNSW Queue Depth MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "depth");
        assertAttributeExists(info, "percent");
        assertAttributeExists(info, "isUnderControl");
        assertAttributeExists(info, "isCritical");
        assertAttributeExists(info, "isFull");
        assertAttributeExists(info, "averageTailScan");
        assertAttributeExists(info, "maxTailScan");
        assertAttributeExists(info, "totalTailScanned");
    }

    @Test
    @DisplayName("All six MBeans should be discoverable via JMX query")
    void allMBeansShouldBeDiscoverableViaQuery() throws Exception {
        ObjectName pattern = new ObjectName("io.excalibur:*");
        Set<ObjectName> mbeans = mbeanServer.queryNames(pattern, null);

        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=QueryLatency")),
            "QueryLatency MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=SystemHealth")),
            "SystemHealth MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=IngestionQueue")),
            "IngestionQueue MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=VisibilityEpoch")),
            "VisibilityEpoch MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=CuckooFpRate")),
            "CuckooFpRate MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=HnswQueueDepth")),
            "HnswQueueDepth MBean should be discoverable");
        assertTrue(mbeans.contains(new ObjectName("io.excalibur:type=IngestionThroughput")),
            "IngestionThroughput MBean should be discoverable");

        assertEquals(7, mbeans.size(), "Exactly 7 ExcaliburDB MBeans should be registered");
    }

    @Test
    @DisplayName("MBean attributes should be readable via JMX")
    void mBeanAttributesShouldBeReadable() throws Exception {
        ObjectName queryLatencyName = new ObjectName("io.excalibur:type=QueryLatency");

        // Record some latency values
        Metrics.recordQueryLatency(10);
        Metrics.recordQueryLatency(20);
        Metrics.recordQueryLatency(30);

        // Read count attribute
        Object count = mbeanServer.getAttribute(queryLatencyName, "count");
        assertNotNull(count, "count attribute should not be null");
        assertTrue(count instanceof Long, "count should be Long");
        assertTrue((Long) count >= 3, "count should be at least 3");

        // Read average attribute
        Object average = mbeanServer.getAttribute(queryLatencyName, "average");
        assertNotNull(average, "average attribute should not be null");
        assertTrue(average instanceof Double, "average should be Double");
    }

    @Test
    @DisplayName("MBean attributes should be read-only")
    void mBeanAttributesShouldBeReadOnly() throws Exception {
        ObjectName queryLatencyName = new ObjectName("io.excalibur:type=QueryLatency");

        // Attempt to set a read-only attribute should throw AttributeNotFoundException
        Attribute attr = new Attribute("count", 999L);
        assertThrows(AttributeNotFoundException.class, () -> {
            mbeanServer.setAttribute(queryLatencyName, attr);
        }, "Setting read-only attribute should fail with AttributeNotFoundException");
    }

    @Test
    @DisplayName("MBeans should support notification emission")
    void mBeansShouldSupportNotifications() throws Exception {
        ObjectName queryLatencyName = new ObjectName("io.excalibur:type=QueryLatency");

        MBeanInfo info = mbeanServer.getMBeanInfo(queryLatencyName);
        MBeanNotificationInfo[] notificationInfos = info.getNotifications();

        assertTrue(notificationInfos.length > 0,
            "MBean should have notification info");

        boolean hasQueryLatencyNotification = false;
        for (MBeanNotificationInfo ni : notificationInfos) {
            for (String type : ni.getNotifTypes()) {
                if (type.contains("latency")) {
                    hasQueryLatencyNotification = true;
                    break;
                }
            }
        }
        assertTrue(hasQueryLatencyNotification,
            "MBean should support query latency notifications");
    }

    @Test
    @DisplayName("Visibility epoch should be readable via JMX")
    void visibilityEpochShouldBeReadable() throws Exception {
        ObjectName epochName = new ObjectName("io.excalibur:type=VisibilityEpoch");

        // Set epoch
        Metrics.recordEpoch(42);

        // Read epoch via JMX
        Object epoch = mbeanServer.getAttribute(epochName, "epoch");
        assertNotNull(epoch, "epoch attribute should not be null");
        assertTrue(epoch instanceof Long, "epoch should be Long");
        assertEquals(42L, (Long) epoch, "epoch should match recorded value");
    }

    @Test
    @DisplayName("System health should be readable via JMX")
    void systemHealthShouldBeReadable() throws Exception {
        ObjectName healthName = new ObjectName("io.excalibur:type=SystemHealth");

        // Set health score
        Metrics.setHealthScore(0.85);

        // Read health via JMX
        Object health = mbeanServer.getAttribute(healthName, "health");
        assertNotNull(health, "health attribute should not be null");
        assertTrue(health instanceof Double, "health should be Double");
        assertEquals(0.85, (Double) health, 0.01, "health should match recorded value");

        // Read ready via JMX
        Object ready = mbeanServer.getAttribute(healthName, "ready");
        assertNotNull(ready, "ready attribute should not be null");
        assertTrue(ready instanceof Boolean, "ready should be Boolean");
        assertTrue((Boolean) ready, "ready should be true when health >= 0.5");
    }

    @Test
    @DisplayName("IngestionThroughput MBean should be registered and visible")
    void ingestionThroughputMBeanShouldBeRegistered() throws Exception {
        ObjectName objectName = new ObjectName("io.excalibur:type=IngestionThroughput");

        assertTrue(mbeanServer.isRegistered(objectName),
            "IngestionThroughput MBean should be registered in JMX");

        MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
        assertNotNull(info, "MBeanInfo should not be null");
        assertEquals("Ingestion Throughput MBean", info.getDescription(),
            "MBean description should match");

        assertAttributeExists(info, "entriesIngested");
        assertAttributeExists(info, "queriesExecuted");
        assertAttributeExists(info, "ingestionRate");
        assertAttributeExists(info, "peakIngestionRate");
    }

    @Test
    @DisplayName("Ingestion throughput should be readable via JMX")
    void ingestionThroughputShouldBeReadable() throws Exception {
        ObjectName throughputName = new ObjectName("io.excalibur:type=IngestionThroughput");

        // Record some ingestion entries
        Metrics.recordIngestEntry();
        Metrics.recordIngestEntry();
        Metrics.recordIngestEntry();

        // Read entriesIngested via JMX
        Object entriesIngested = mbeanServer.getAttribute(throughputName, "entriesIngested");
        assertNotNull(entriesIngested, "entriesIngested attribute should not be null");
        assertTrue(entriesIngested instanceof Long, "entriesIngested should be Long");
        assertTrue((Long) entriesIngested >= 3, "entriesIngested should be at least 3");

        // Read ingestionRate via JMX
        Object ingestionRate = mbeanServer.getAttribute(throughputName, "ingestionRate");
        assertNotNull(ingestionRate, "ingestionRate attribute should not be null");
        assertTrue(ingestionRate instanceof Double, "ingestionRate should be Double");

        // Read peakIngestionRate via JMX
        Object peakRate = mbeanServer.getAttribute(throughputName, "peakIngestionRate");
        assertNotNull(peakRate, "peakIngestionRate attribute should not be null");
        assertTrue(peakRate instanceof Double, "peakIngestionRate should be Double");
    }

    // Helper method to assert attribute exists
    private void assertAttributeExists(MBeanInfo info, String attributeName) {
        boolean found = false;
        for (MBeanAttributeInfo attr : info.getAttributes()) {
            if (attr.getName().equals(attributeName)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Attribute '" + attributeName + "' should exist in MBean");
    }
}
