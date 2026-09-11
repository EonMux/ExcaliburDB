package io.excalibur.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the contract every {@link DynamicMBean} here owes its clients.
 *
 * <p>An {@code MBeanAttributeInfo} is what a JMX client uses to decode a value; the platform
 * server does not check the declared type against what {@code getAttribute} actually returns.
 * A "long" attribute that hands back a {@code Double} therefore fails only in the client, far
 * from the code that caused it. The sweep below checks every attribute of every MBean, so a
 * new attribute inherits the check for free.</p>
 *
 * <p>The queue tests cover the second way a JMX client gets misled: two MBeans publishing
 * verdicts about the same counter against different limits.</p>
 *
 * @author ExcaliburDB
 */
class MBeanAttributeContractTest {

    /** Declared attribute type to the class {@code getAttribute} must return. */
    private static final Map<String, Class<?>> DECLARED_TYPES = Map.of(
        "long", Long.class,
        "int", Integer.class,
        "double", Double.class,
        "boolean", Boolean.class,
        "java.lang.String", String.class
    );

    private static final long DEFAULT_QUEUE_LIMIT = 100_000L;

    @AfterEach
    void restoreQueueLimit() {
        Metrics.setHnswQueueLimit(DEFAULT_QUEUE_LIMIT);
    }

    @Test
    @DisplayName("Every attribute returns a value of its declared type")
    void everyAttributeMatchesItsDeclaredType() throws Exception {
        // Give every counter a non-zero value so no attribute is read on a cold path.
        Metrics.recordQueryLatency(42L);
        Metrics.setHealthScore(0.7);
        Metrics.setEstimatedCoverage(0.3);
        Metrics.recordEpoch(9L);
        Metrics.recordCuckooFpRate(0.0004);
        Metrics.recordHnswDepth(1234L);
        Metrics.recordTailScanMetrics(10, 4);
        Metrics.recordIngestEntry();

        for (DynamicMBean mbean : allMBeans()) {
            for (MBeanAttributeInfo info : mbean.getMBeanInfo().getAttributes()) {
                Class<?> expected = DECLARED_TYPES.get(info.getType());
                assertNotNull(expected,
                    mbean.getClass().getSimpleName() + "." + info.getName()
                        + " declares unsupported type " + info.getType());

                Object value = mbean.getAttribute(info.getName());
                assertNotNull(value,
                    mbean.getClass().getSimpleName() + "." + info.getName() + " returned null");
                assertEquals(expected, value.getClass(),
                    mbean.getClass().getSimpleName() + "." + info.getName()
                        + " declares " + info.getType() + " but returned "
                        + value.getClass().getName());
            }
        }
    }

    @Test
    @DisplayName("Every declared attribute is served by getAttribute")
    void declaredAttributesAreAllReadable() throws Exception {
        for (DynamicMBean mbean : allMBeans()) {
            for (MBeanAttributeInfo info : mbean.getMBeanInfo().getAttributes()) {
                assertNotNull(mbean.getAttribute(info.getName()),
                    mbean.getClass().getSimpleName() + "." + info.getName()
                        + " is declared but not served");
            }
        }
    }

    @Test
    @DisplayName("The two queue MBeans never contradict each other")
    void queueMBeansAgree() throws Exception {
        Metrics.recordHnswDepth(95_000L);

        DynamicMBean hnsw = Metrics.getHnswQueueDepthMBean();
        DynamicMBean ingestion = Metrics.getIngestionQueueMBean();

        for (String attribute : List.of("depth", "percent", "isUnderControl", "isCritical", "isFull")) {
            assertEquals(hnsw.getAttribute(attribute), ingestion.getAttribute(attribute),
                "IngestionQueue and HnswQueueDepth disagree on " + attribute);
        }

        assertEquals(95.0, (Double) hnsw.getAttribute("percent"), 1e-9,
            "95,000 of a 100,000 limit is 95%");
        assertTrue((Boolean) ingestion.getAttribute("isCritical"),
            "a queue at 95% of its limit is critical on both MBeans");
    }

    @Test
    @DisplayName("IngestionQueue says in its own description that it is an alias")
    void ingestionQueueDeclaresItselfAnAlias() {
        // The two beans carry one number, and it is the HNSW integration backlog rather than
        // an ingestion queue. An operator graphing both sees two independent subsystems in
        // trouble where there is one, and looks for an ingestion queue that does not exist.
        javax.management.MBeanInfo info = Metrics.getIngestionQueueMBean().getMBeanInfo();

        assertTrue(info.getDescription().contains("HnswQueueDepth"),
            "the bean must name what it actually reports: " + info.getDescription());
        for (javax.management.MBeanAttributeInfo attribute : info.getAttributes()) {
            assertTrue(attribute.getDescription().contains("HNSW"),
                attribute.getName() + " does not say which queue it measures: "
                    + attribute.getDescription());
        }
    }

    @Test
    @DisplayName("Queue verdicts follow the configured limit")
    void queueVerdictsFollowTheConfiguredLimit() throws Exception {
        assertEquals(DEFAULT_QUEUE_LIMIT, Metrics.getHnswQueueLimit(),
            "default must match excalibur.shard.hnsw-integration-queue-depth-limit");

        Metrics.recordHnswDepth(95_000L);
        Metrics.setHnswQueueLimit(200_000L);

        DynamicMBean ingestion = Metrics.getIngestionQueueMBean();
        assertEquals(47.5, (Double) ingestion.getAttribute("percent"), 1e-9,
            "raising the limit must lower the reported percentage");
        assertEquals(Metrics.getHnswQueueDepthMBean().getAttribute("percent"),
            ingestion.getAttribute("percent"),
            "both MBeans must read the same limit");
    }

    @Test
    @DisplayName("A non-positive queue limit is rejected")
    void nonPositiveQueueLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Metrics.setHnswQueueLimit(0));
        assertThrows(IllegalArgumentException.class, () -> Metrics.setHnswQueueLimit(-5));
        assertEquals(DEFAULT_QUEUE_LIMIT, Metrics.getHnswQueueLimit(),
            "a rejected limit must leave the configured one alone");
    }

    private static List<DynamicMBean> allMBeans() {
        return List.of(
            Metrics.getQueryLatencyMBean(),
            Metrics.getSystemHealthMBean(),
            Metrics.getIngestionQueueMBean(),
            Metrics.getVisibilityEpochMBean(),
            Metrics.getCuckooFpRateMBean(),
            Metrics.getHnswQueueDepthMBean(),
            Metrics.getIngestionThroughputMBean()
        );
    }
}
