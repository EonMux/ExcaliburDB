package io.excalibur.metrics;

/**
 * A metrics MBean that also accepts listeners through this module's own observer interface,
 * the one {@link MBeanServerProvider} describes, alongside the JMX one.
 *
 * <h2>Why this is separate from {@link DynamicMetricMBean}</h2>
 * <p>The observer pair predates the {@code NotificationEmitter} implementation and is how the
 * in-process listeners in this repository still register - they hold an
 * {@link ObjectNotificationInfo} and an {@link MBeanNotificationObserver}, not a JMX
 * {@code NotificationListener}. Six of the seven metrics beans carry it and
 * {@code IngestionThroughput}, added later, never did. Splitting it out keeps that difference
 * a deliberate fact about which beans have in-process listeners rather than an accident of
 * which base class was convenient, and keeps the shared JMX surface free of a compatibility
 * shim that is not part of it.</p>
 *
 * @author ExcaliburDB
 */
abstract class ObserverRegisteringMBean extends DynamicMetricMBean {

    /**
     * @param mbeanType        The bean's short type name, used in notification descriptions
     * @param notificationType The single notification type this bean emits
     * @param description      The bean's description, as a console shows it
     * @param attributes       The attributes this bean publishes and can read
     */
    ObserverRegisteringMBean(String mbeanType, String notificationType, String description,
                             MetricAttributeTable attributes) {
        super(mbeanType, notificationType, description, attributes);
    }

    /**
     * Adds a notification listener using the custom observer interface.
     * This method provides backward compatibility with the MBeanServerProvider interface.
     *
     * @param info Notification info containing type and object name
     * @param observer The observer to receive notifications
     * @param putPending Whether to deliver pending notifications
     */
    public void addNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer, boolean putPending) {
        getBroadcaster().addNotificationListener(info, observer, putPending);
    }

    /**
     * Removes a notification listener using the custom observer interface.
     * This method provides backward compatibility with the MBeanServerProvider interface.
     *
     * @param info Notification info
     * @param observer The observer to remove
     */
    public void removeNotificationListener(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
        getBroadcaster().removeNotificationListener(info, observer);
    }
}
