package com.eonmux.excalibur.metrics;

import javax.management.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class that provides notification broadcasting capabilities for MBeans.
 * Wraps standard JMX notification support with the custom observer pattern.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe. Listeners can be added/removed and notifications
 * can be sent from any thread.</p>
 *
 * @author ExcaliburDB
 */
public class MBeanNotificationBroadcaster implements NotificationEmitter {

    private final CopyOnWriteArrayList<ListenerEntry> listeners;
    private final AtomicLong sequenceNumber;
    private final Object source;
    private final String mbeanType;
    private final MBeanNotificationInfo[] notificationInfos;

    /**
     * Creates a new broadcaster for the specified MBean.
     *
     * <p>The owning MBean is used as the notification source so that the
     * MBeanServer substitutes the MBean's registered ObjectName before
     * delivering to listeners registered through
     * {@code MBeanServer.addNotificationListener(ObjectName, ...)}. That
     * substitution only happens when the source is the MBean instance itself.</p>
     *
     * @param source The MBean emitting the notifications (used as notification source)
     * @param mbeanType The type of MBean (used for notification descriptions)
     * @param notificationTypes Array of notification types this MBean can emit
     */
    public MBeanNotificationBroadcaster(Object source, String mbeanType, String[] notificationTypes) {
        this.listeners = new CopyOnWriteArrayList<>();
        this.sequenceNumber = new AtomicLong(0);
        this.source = source;
        this.mbeanType = mbeanType;
        this.notificationInfos = new MBeanNotificationInfo[]{
            new MBeanNotificationInfo(
                notificationTypes,
                javax.management.Notification.class.getName(),
                "Notifications from " + mbeanType + " MBean"
            )
        };
    }

    /**
     * Adds a notification listener using the custom observer interface.
     *
     * @param info Notification info containing type and object name
     * @param observer The observer to receive notifications
     * @param putPending Whether to deliver pending notifications
     */
    public void addNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer,
            boolean putPending) {

        ListenerEntry entry = new ListenerEntry(info, observer);
        listeners.add(entry);
    }

    /**
     * Removes a notification listener.
     *
     * @param info Notification info
     * @param observer The observer to remove
     */
    public void removeNotificationListener(
            ObjectNotificationInfo info,
            MBeanNotificationObserver observer) {

        listeners.removeIf(entry ->
            entry.info.notificationType.equals(info.notificationType) &&
            entry.observer == observer
        );
    }

    /**
     * Emits a notification to all registered listeners.
     *
     * @param type Notification type
     * @param message Human-readable message
     * @param userData Optional user data payload
     */
    public void sendNotification(String type, String message, Object userData) {
        if (listeners.isEmpty()) {
            return;
        }

        long seq = sequenceNumber.incrementAndGet();
        Notification notification = new Notification(
            type,
            source,
            seq,
            System.currentTimeMillis(),
            message
        );

        if (userData != null) {
            notification.setUserData(userData);
        }

        sendNotification(notification);
    }

    /**
     * Emits a notification to all registered listeners.
     *
     * @param notification The notification to send
     */
    public void sendNotification(Notification notification) {
        for (ListenerEntry entry : listeners) {
            try {
                entry.observer.notify(notification);
            } catch (Exception e) {
                // Log and continue - don't let one bad listener affect others
                System.getLogger(MBeanNotificationBroadcaster.class.getName())
                    .log(System.Logger.Level.WARNING,
                        "Notification listener threw exception", e);
            }
        }
    }

    /**
     * Emits a notification with a numeric value as user data.
     *
     * @param type Notification type
     * @param message Human-readable message
     * @param value Numeric value
     */
    public void sendNotification(String type, String message, long value) {
        sendNotification(type, message, Long.valueOf(value));
    }

    /**
     * Emits a notification with a double value as user data.
     *
     * @param type Notification type
     * @param message Human-readable message
     * @param value Double value
     */
    public void sendNotification(String type, String message, double value) {
        sendNotification(type, message, Double.valueOf(value));
    }

    // ============================================
    // NotificationEmitter Interface Implementation
    // ============================================

    @Override
    public void addNotificationListener(
            NotificationListener listener,
            NotificationFilter filter,
            Object handback) throws IllegalArgumentException {

        // Create adapter for standard JMX listener
        ObjectNotificationInfo info = new ObjectNotificationInfo(
            mbeanType,
            null,
            new String[0],
            java.util.Collections.emptyMap()
        );

        // Wrap standard listener in our observer interface
        MBeanNotificationObserver observer = new MBeanNotificationObserver() {
            @Override
            public void notify(Notification notification) {
                if (filter == null || filter.isNotificationEnabled(notification)) {
                    listener.handleNotification(notification, handback);
                }
            }

            @Override
            public void close() {
                // No-op for adapter
            }
        };

        // Store the entry with references to the original listener/filter/handback
        listeners.add(new ListenerEntry(info, observer, listener, filter, handback));
    }

    @Override
    public void removeNotificationListener(NotificationListener listener)
            throws ListenerNotFoundException {

        boolean removed = listeners.removeIf(entry ->
            entry.wrappedListener == listener
        );

        if (!removed) {
            throw new ListenerNotFoundException("Listener not found");
        }
    }

    @Override
    public void removeNotificationListener(
            NotificationListener listener,
            NotificationFilter filter,
            Object handback) throws ListenerNotFoundException {

        boolean removed = listeners.removeIf(entry ->
            entry.wrappedListener == listener &&
            (entry.wrappedFilter == filter || (entry.wrappedFilter != null && entry.wrappedFilter.equals(filter))) &&
            (entry.wrappedHandback == handback || (entry.wrappedHandback != null && entry.wrappedHandback.equals(handback)))
        );

        if (!removed) {
            throw new ListenerNotFoundException("Listener not found");
        }
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        return notificationInfos.clone();
    }

    /**
     * Gets the count of registered listeners.
     *
     * @return Number of listeners
     */
    public int getListenerCount() {
        return listeners.size();
    }

    /**
     * Checks if there are any registered listeners.
     *
     * @return true if at least one listener is registered
     */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /**
     * Removes all registered listeners.
     */
    public void removeAllListeners() {
        listeners.clear();
    }

    /**
     * Gets the current sequence number (for testing).
     *
     * @return Current sequence number
     */
    public long getSequenceNumber() {
        return sequenceNumber.get();
    }

    // ============================================
    // Inner Classes
    // ============================================

    /**
     * Entry representing a registered listener.
     * Stores both the observer and, for wrapped standard JMX listeners,
     * references to the original listener/filter/handback for proper removal.
     */
    private static class ListenerEntry {
        final ObjectNotificationInfo info;
        final MBeanNotificationObserver observer;
        final NotificationListener wrappedListener;
        final NotificationFilter wrappedFilter;
        final Object wrappedHandback;

        ListenerEntry(ObjectNotificationInfo info, MBeanNotificationObserver observer) {
            this(info, observer, null, null, null);
        }

        ListenerEntry(ObjectNotificationInfo info, MBeanNotificationObserver observer,
                      NotificationListener wrappedListener, NotificationFilter wrappedFilter,
                      Object wrappedHandback) {
            this.info = info;
            this.observer = observer;
            this.wrappedListener = wrappedListener;
            this.wrappedFilter = wrappedFilter;
            this.wrappedHandback = wrappedHandback;
        }
    }

    /**
     * Predefined notification types for ExcaliburDB metrics.
     */
    public static class NotificationTypes {
        /** Query latency recorded */
        public static final String QUERY_LATENCY = "excalibur.query.latency";
        /** System health changed */
        public static final String SYSTEM_HEALTH = "excalibur.system.health";
        /** Ingestion queue depth changed */
        public static final String INGESTION_QUEUE = "excalibur.ingest.queue";
        /** Visibility epoch advanced */
        public static final String VISIBILITY_EPOCH = "excalibur.visibility.epoch";
        /** HNSW queue depth changed */
        public static final String HNSW_QUEUE_DEPTH = "excalibur.hnsw.queue";
        /** Ingestion throughput updated */
        public static final String INGESTION_THROUGHPUT = "excalibur.ingest.throughput";
    }
}
