package io.excalibur.metrics;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metrics registry for managing MBean registration and attribute access.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe for concurrent MBean registration and attribute access.</p>
 *
 * <h2>State Ownership</h2>
 * <p>This class owns no metric values of its own. Every accessor reads the MBean when one is
 * registered and otherwise falls back to the same global {@link Metrics} state that production
 * code records into. A registry-local copy would diverge silently, because nothing outside
 * {@code Metrics} ever writes one.</p>
 *
 * @author ExcaliburDB
 */
public final class MetricsRegistry {

    // Registered MBeans by name (stores DynamicMBean instances)
    private final Map<String, DynamicMBean> registeredMBeans = new ConcurrentHashMap<>();

    /**
     * Creates a new MetricsRegistry instance.
     */
    public MetricsRegistry() {
    }

    /**
     * Registers an MBean with the given name.
     *
     * @param mbean MBean instance to register (must implement DynamicMBean)
     * @param name JMX name for the MBean
     * @throws Exception if registration fails
     */
    public void registerMBean(DynamicMBean mbean, String name) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName(name);
        server.registerMBean(mbean, objectName);
        registeredMBeans.put(name, mbean);
    }

    /**
     * Updates the current epoch.
     *
     * @param epoch New epoch number
     */
    public void setCurrentEpoch(long epoch) {
        Metrics.recordEpoch(epoch);
    }

    /**
     * Gets the current epoch.
     *
     * @return Current epoch number
     */
    public long getCurrentEpoch() {
        return Metrics.getCurrentEpoch();
    }

    /**
     * Updates the estimated coverage.
     *
     * @param coverage Coverage value between 0.0 and 1.0
     */
    public void setEstimatedCoverage(double coverage) {
        Metrics.setEstimatedCoverage(coverage);
    }

    /**
     * Gets the estimated coverage.
     *
     * @return Estimated coverage between 0.0 and 1.0
     */
    public double getEstimatedCoverage() {
        return Metrics.getEstimatedCoverage();
    }

    /**
     * Gets the query latency histogram that production query paths record into.
     *
     * @return Query latency histogram
     */
    public FixedBucketHistogram getQueryLatency() {
        return Metrics.QueryLatency();
    }

    /**
     * Gets registered MBean by name.
     *
     * @param name MBean name
     * @return MBean instance, or null if not found
     */
    public DynamicMBean getMBean(String name) {
        return registeredMBeans.get(name);
    }

    /**
     * Gets all registered MBean names.
     *
     * @return List of registered MBean names
     */
    public java.util.List<String> getRegisteredMBeanNames() {
        return new java.util.ArrayList<>(registeredMBeans.keySet());
    }

    /**
     * Gets all registered MBean names.
     */
    public java.util.Set<String> getAllMBeanNames() {
        return registeredMBeans.keySet();
    }

    /**
     * Gets the current epoch from MBean if available.
     *
     * @return Current epoch, or the metrics value if MBean not available
     */
    public long getEpochFromMBean() {
        DynamicMBean mbean = getMBean("io.excalibur:type=VisibilityEpoch");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute("epoch");
                if (value != null && value instanceof Long) {
                    return ((Long) value).longValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.getCurrentEpoch();
    }

    /**
     * Gets the estimated corpus coverage from MBean if available.
     *
     * @return Coverage, or the metrics value if MBean not available
     */
    public double getCoverageFromMBean() {
        DynamicMBean mbean = getMBean("io.excalibur:type=SystemHealth");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute("coverage");
                if (value != null && value instanceof Double) {
                    return ((Double) value).doubleValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.getEstimatedCoverage();
    }

    /**
     * Gets query latency percentile from MBean if available.
     *
     * @param percentile Percentile (50, 90, 95, 99)
     * @return Latency in ms, or histogram value if MBean not available
     */
    public long getLatencyPercentile(int percentile) {
        DynamicMBean mbean = getMBean("io.excalibur:type=QueryLatency");
        if (mbean != null) {
            try {
                String attrName = "p" + percentile;
                Object value = mbean.getAttribute(attrName);
                if (value != null && value instanceof Long) {
                    return ((Long) value).longValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.QueryLatency().getPercentile(percentile);
    }

    /**
     * Gets query latency count from MBean if available.
     *
     * @return Entry count, or histogram count if MBean not available
     */
    public long getLatencyCount() {
        DynamicMBean mbean = getMBean("io.excalibur:type=QueryLatency");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute("count");
                if (value != null && value instanceof Long) {
                    return ((Long) value).longValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.QueryLatency().getTotalCount();
    }

    /**
     * Gets queue depth from MBean if available.
     *
     * @return Queue depth, or metrics value if MBean not available
     */
    public long getQueueDepthFromMBean() {
        DynamicMBean mbean = getMBean("io.excalibur:type=IngestionQueue");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute("depth");
                if (value != null && value instanceof Long) {
                    return ((Long) value).longValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.getQueueDepth();
    }

    /**
     * Gets health score from MBean if available.
     *
     * @return Health score, or metrics value if MBean not available
     */
    public double getHealthScoreFromMBean() {
        DynamicMBean mbean = getMBean("io.excalibur:type=SystemHealth");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute("health");
                if (value != null && value instanceof Double) {
                    return ((Double) value).doubleValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.getHealthScore();
    }

    /**
     * Gets all available attributes from all MBeans.
     *
     * @return Map of MBean name to attributes
     */
    public Map<String, Object> getAllMBeanAttributes() {
        Map<String, Object> result = new HashMap<>();

        for (Map.Entry<String, DynamicMBean> entry : registeredMBeans.entrySet()) {
            try {
                DynamicMBean mbean = entry.getValue();
                MBeanInfo info = mbean.getMBeanInfo();
                for (MBeanAttributeInfo attrInfo : info.getAttributes()) {
                    try {
                        Object value = mbean.getAttribute(attrInfo.getName());
                        if (value != null) {
                            result.put(entry.getKey() + "." + attrInfo.getName(), value);
                        }
                    } catch (Exception e) {
                        // Skip attributes that fail
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        return result;
    }

    /**
     * Clears the recorded metric values.
     *
     * <p>The visibility epoch is deliberately left alone: it is monotone state that drives
     * which entries are visible to queries, so winding it back would corrupt reads rather
     * than clear a measurement.</p>
     */
    public void clear() {
        Metrics.QueryLatency().reset();
        Metrics.setEstimatedCoverage(0.0);
    }
}
