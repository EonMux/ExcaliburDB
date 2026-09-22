package com.eonmux.excalibur.metrics;

import javax.management.MBeanServer;

/**
 * JMX MBean server provider interface.
 *
 * <h2>Thread-Safety</h2>
 * <p>Implementations should be thread-safe for concurrent attribute access.</p>
 *
 * @author ExcaliburDB
 */
public interface MBeanServerProvider {

    /**
     * Gets the MBean server.
     *
     * @return MBean server instance
     */
    MBeanServer mbeanServer();

    /**
     * Adds a notification listener.
     *
     * @param info Notification info
     * @param observer Notification observer
     * @param putPending Whether to put pending notifications
     */
    void addNotificationListener(
        ObjectNotificationInfo info,
        MBeanNotificationObserver observer,
        boolean putPending
    );

    /**
     * Removes a notification listener.
     *
     * @param info Notification info
     * @param observer Notification observer
     */
    void removeNotificationListener(
        ObjectNotificationInfo info,
        MBeanNotificationObserver observer
    );

    /**
     * Gets an attribute value.
     *
     * @param name Attribute name
     * @return Attribute value
     * @throws AttributeException if attribute not found
     */
    Object getAttribute(String name) throws AttributeException;

    /**
     * Sets an attribute value.
     *
     * @param name Attribute name
     * @param value Attribute value
     * @throws AttributeException if attribute not writable
     */
    void setAttribute(String name, Object value) throws AttributeException;

    /**
     * Gets all available attributes.
     *
     * @return Set of attribute names
     */
    default java.util.Set<String> getAttributes() {
        return java.util.Collections.emptySet();
    }
}
