package com.eonmux.excalibur.metrics;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ReflectionException;
import java.util.Set;

/**
 * The JMX surface every metrics MBean in this module presents: a set of read-only attributes
 * and one stream of notifications about them.
 *
 * <h2>Why a base class rather than seven implementations</h2>
 * <p>{@code DynamicMBean} is eleven methods, of which only two say anything about a particular
 * metric: which attributes exist, and how to read one. The other nine are the same refusal or
 * the same delegation every time - reads are read-only, there are no operations, listeners go
 * to the broadcaster. Written out per bean they were nine chances per bean to diverge, and a
 * bean that quietly accepts a write or swallows a listener registration is a fault nothing
 * scrapes for. Stated once, a new metrics bean is the two things that are actually about the
 * metric.</p>
 *
 * <h2>Thread-Safety</h2>
 * <p>Everything here is final state or a delegation. Attribute reads go straight to the
 * accessors the table holds, which are the concurrent metric state's own; this class
 * introduces no lock and holds none while reading, so a scrape cannot block a recording
 * thread.</p>
 *
 * @author ExcaliburDB
 */
abstract class DynamicMetricMBean implements DynamicMBean, NotificationEmitter {

    private final MBeanNotificationBroadcaster broadcaster;
    private final String description;
    private final MetricAttributeTable attributes;

    /**
     * @param mbeanType        The bean's short type name, used in notification descriptions
     * @param notificationType The single notification type this bean emits
     * @param description      The bean's description, as a console shows it
     * @param attributes       The attributes this bean publishes and can read
     */
    DynamicMetricMBean(String mbeanType, String notificationType, String description,
                       MetricAttributeTable attributes) {
        // The bean itself is the notification source: the MBeanServer only substitutes the
        // registered ObjectName when the source is the MBean instance.
        this.broadcaster = new MBeanNotificationBroadcaster(this, mbeanType,
            new String[]{ notificationType });
        this.description = description;
        this.attributes = attributes;
    }

    /**
     * Gets the notification broadcaster for this MBean.
     *
     * @return The notification broadcaster
     */
    public MBeanNotificationBroadcaster getBroadcaster() {
        return broadcaster;
    }

    /**
     * Gets all available attribute names.
     *
     * @return Set of attribute names
     */
    public Set<String> getAttributes() {
        return attributes.names();
    }

    // ============================================
    // DynamicMBean interface implementation
    // ============================================

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
        return attributes.read(attribute);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always refuses: every metric here is a measurement of the node, and a measurement a
     * console can overwrite is not a measurement.</p>
     */
    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
        throw new AttributeNotFoundException("Read-only attribute: " + attribute.getName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Attributes that fail to read are omitted rather than failing the batch, so one broken
     * metric does not blank a console's whole view of the bean.</p>
     */
    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList list = new AttributeList();
        for (String attr : attributes) {
            try {
                list.add(new Attribute(attr, getAttribute(attr)));
            } catch (Exception ignored) {
                // Skip attributes that fail
            }
        }
        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList(); // All read-only
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
        throw new ReflectionException(new NoSuchMethodException("No operation: " + actionName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MBeanInfo getMBeanInfo() {
        return new MBeanInfo(
            this.getClass().getName(),
            description,
            attributes.descriptors(),
            null, // constructors
            null, // operations
            broadcaster.getNotificationInfo() // notifications
        );
    }

    // ============================================
    // NotificationEmitter interface implementation
    // ============================================

    /**
     * {@inheritDoc}
     */
    @Override
    public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException {
        broadcaster.addNotificationListener(listener, filter, handback);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener, filter, handback);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        return broadcaster.getNotificationInfo();
    }
}
