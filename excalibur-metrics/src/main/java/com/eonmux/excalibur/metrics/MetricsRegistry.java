package com.eonmux.excalibur.metrics;

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
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=VisibilityEpoch");
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
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=SystemHealth");
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
     * The tail percentile, written the way this codebase has always written it.
     *
     * <p>{@code 999} means 99.9, matching {@link Metrics#getP999()} and the {@code p99.9}
     * attribute the bean publishes. It is the one percentile of interest that is not a whole
     * number, and an {@code int} parameter has no other way to say it.</p>
     */
    private static final int TAIL_PERCENTILE_SPELLING = 999;

    /** The tail percentile {@link #TAIL_PERCENTILE_SPELLING} stands for. */
    private static final double TAIL_PERCENTILE = 99.9;

    /**
     * Gets a query latency percentile, from the MBean when one is registered and from the
     * histogram otherwise.
     *
     * <p>Pass a whole percentile in 0-100, or {@code 999} for p99.9 - the spelling
     * {@link Metrics#getP999()} uses, and the only way an {@code int} can name the one
     * published percentile that is not whole.</p>
     *
     * <p>Anything else is refused rather than answered. It used to fall through to the
     * histogram, which reports the edge of its range for a target past every bucket, so
     * {@code getLatencyPercentile(999)} returned {@value FixedBucketHistogram#RANGE_MS} - ten
     * seconds presented as a real measurement of the tail. A caller cannot tell a fabricated
     * latency from a measured one, and only an error gets investigated.</p>
     *
     * @param percentile A whole percentile in 0-100, or 999 meaning 99.9
     * @return Latency in ms at that percentile
     * @throws IllegalArgumentException if the argument names no percentile
     */
    public long getLatencyPercentile(int percentile) {
        double requested = toPercentile(percentile);
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=QueryLatency");
        if (mbean != null) {
            try {
                Object value = mbean.getAttribute(attributeNameFor(requested));
                if (value != null && value instanceof Long) {
                    return ((Long) value).longValue();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return Metrics.QueryLatency().getPercentile(requested);
    }

    /**
     * Reads the argument as the percentile it names.
     *
     * @param percentile A whole percentile in 0-100, or 999 meaning 99.9
     * @return The percentile as a number in [0.0, 100.0]
     * @throws IllegalArgumentException if the argument names no percentile
     */
    private static double toPercentile(int percentile) {
        if (percentile == TAIL_PERCENTILE_SPELLING) {
            return TAIL_PERCENTILE;
        }
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException(
                "percentile must be 0-100, or " + TAIL_PERCENTILE_SPELLING + " for p"
                    + TAIL_PERCENTILE + ", got: " + percentile);
        }
        return percentile;
    }

    /**
     * The attribute the QueryLatency bean would publish a percentile under.
     *
     * <p>Spelled the way the bean spells it - {@code p50}, {@code p99.9} - so that the lookup
     * finds the published value instead of silently missing and falling back. A percentile the
     * bean does not publish simply misses, which is what the fallback is for.</p>
     *
     * @param percentile The percentile
     * @return The JMX attribute name
     */
    private static String attributeNameFor(double percentile) {
        if (percentile == Math.rint(percentile)) {
            return "p" + (long) percentile;
        }
        return "p" + java.math.BigDecimal.valueOf(percentile).stripTrailingZeros().toPlainString();
    }

    /**
     * Gets query latency count from MBean if available.
     *
     * @return Entry count, or histogram count if MBean not available
     */
    public long getLatencyCount() {
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=QueryLatency");
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
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=IngestionQueue");
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
        DynamicMBean mbean = getMBean("com.eonmux.excalibur:type=SystemHealth");
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
     * <p>This discards the Cuckoo filter false-positive samples along with the latencies:
     * both live in the one histogram this node measures into, and {@code reset()} clears it
     * whole. Sampling repopulates the FP rates on the next tick.</p>
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
