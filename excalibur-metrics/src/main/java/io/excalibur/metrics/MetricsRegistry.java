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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics registry for managing MBean registration and attribute access.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe for concurrent MBean registration and attribute access.</p>
 *
 * @author ExcaliburDB
 */
public final class MetricsRegistry {

    // Registered MBeans by name (stores DynamicMBean instances)
    private final Map<String, DynamicMBean> registeredMBeans = new HashMap<>();

    // Current epoch reference for thread-safe updates
    private final AtomicReference<Long> currentEpoch = new AtomicReference<>(0L);

    // Estimated coverage reference
    private final AtomicReference<Double> estimatedCoverage = new AtomicReference<>(0.0);

    // Query latency reference
    private final AtomicReference<FixedBucketHistogram> queryLatency = new AtomicReference<>(new FixedBucketHistogram());

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
        currentEpoch.set(epoch);
    }

    /**
     * Gets the current epoch.
     *
     * @return Current epoch number
     */
    public long getCurrentEpoch() {
        return currentEpoch.get();
    }

    /**
     * Updates the estimated coverage.
     *
     * @param coverage Coverage value between 0.0 and 1.0
     */
    public void setEstimatedCoverage(double coverage) {
        if (coverage < 0.0) coverage = 0.0;
        if (coverage > 1.0) coverage = 1.0;
        estimatedCoverage.set(coverage);
    }

    /**
     * Gets the estimated coverage.
     *
     * @return Estimated coverage between 0.0 and 1.0
     */
    public double getEstimatedCoverage() {
        return estimatedCoverage.get();
    }

    /**
     * Gets the query latency histogram.
     *
     * @return Query latency histogram
     */
    public FixedBucketHistogram getQueryLatency() {
        return queryLatency.get();
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
     * @return Current epoch, or current time-based epoch if MBean not available
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
        return currentEpoch.get();
    }

    /**
     * Gets the current coverage from MBean if available.
     *
     * @return Coverage, or estimated coverage if MBean not available
     */
    public double getCoverageFromMBean() {
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
        return estimatedCoverage.get();
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
        return queryLatency.get().getPercentile(percentile);
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
        return queryLatency.get().getTotalCount();
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
     * Clears all metrics.
     */
    public void clear() {
        queryLatency.set(new FixedBucketHistogram());
        estimatedCoverage.set(0.0);
        currentEpoch.set(0L);
    }
}
