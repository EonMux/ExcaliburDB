package com.eonmux.excalibur.metrics;

import javax.management.DynamicMBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers metrics MBeans with the platform MBean server and remembers what it registered.
 *
 * <p>Reading a metric is not this class's job. JMX consoles read through the platform server,
 * the HTTP endpoints read {@link Metrics} directly, and both see the same global state that
 * production records into. The registry only records which beans it put on the server, under
 * which names.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe for concurrent registration and lookup.</p>
 *
 * @author ExcaliburDB
 */
public final class MetricsRegistry {

    private final Map<String, DynamicMBean> registeredMBeans = new ConcurrentHashMap<>();

    /**
     * Creates a registry that has registered nothing yet.
     */
    public MetricsRegistry() {
    }

    /**
     * Registers an MBean with the platform MBean server under the given name.
     *
     * <p>The bean is remembered only once the server has accepted it, so a rejected
     * registration leaves no trace here.</p>
     *
     * @param mbean MBean instance to register
     * @param name JMX object name for the MBean
     * @throws Exception if the name is malformed or the server refuses the registration,
     *                   for example because the name is already taken
     */
    public void registerMBean(DynamicMBean mbean, String name) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        server.registerMBean(mbean, new ObjectName(name));
        registeredMBeans.put(name, mbean);
    }

    /**
     * Gets an MBean this registry registered.
     *
     * @param name JMX object name it was registered under
     * @return The MBean, or null if this registry registered nothing under that name
     */
    public DynamicMBean getMBean(String name) {
        return registeredMBeans.get(name);
    }

    /**
     * Gets the names of every MBean this registry registered.
     *
     * @return A snapshot of the registered object names, in no particular order
     */
    public List<String> getRegisteredMBeanNames() {
        return List.copyOf(registeredMBeans.keySet());
    }
}
