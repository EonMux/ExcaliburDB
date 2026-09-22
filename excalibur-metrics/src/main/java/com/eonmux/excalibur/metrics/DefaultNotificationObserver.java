package com.eonmux.excalibur.metrics;

import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link MBeanNotificationObserver} that stores
 * received notifications in a concurrent queue for retrieval.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is thread-safe. Notifications can be received from multiple
 * threads concurrently and retrieved safely.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DefaultNotificationObserver observer = new DefaultNotificationObserver();
 * mbean.addNotificationListener(info, observer, false);
 *
 * // Later, retrieve notifications
 * List<Notification> notifications = observer.getNotifications();
 *
 * // When done, close the observer
 * observer.close();
 * }</pre>
 *
 * @author ExcaliburDB
 */
public class DefaultNotificationObserver implements MBeanNotificationObserver, NotificationListener {

    private final ConcurrentLinkedQueue<Notification> notifications;
    private final AtomicBoolean closed;
    private final AtomicLong notificationCount;
    private final NotificationFilter filter;
    private final Object handback;

    /**
     * Creates a new observer with no filter.
     */
    public DefaultNotificationObserver() {
        this(null, null);
    }

    /**
     * Creates a new observer with optional filter and handback.
     *
     * @param filter Optional notification filter (null to receive all)
     * @param handback Optional handback object passed to listeners
     */
    public DefaultNotificationObserver(NotificationFilter filter, Object handback) {
        this.notifications = new ConcurrentLinkedQueue<>();
        this.closed = new AtomicBoolean(false);
        this.notificationCount = new AtomicLong(0);
        this.filter = filter;
        this.handback = handback;
    }

    /**
     * Called when a notification is received from an MBean.
     * Stores the notification in the internal queue if not closed.
     *
     * @param notification The notification to process
     */
    @Override
    public void notify(Notification notification) {
        if (closed.get()) {
            return;
        }

        // Apply filter if present
        if (filter != null && !filter.isNotificationEnabled(notification)) {
            return;
        }

        notifications.offer(notification);
        notificationCount.incrementAndGet();
    }

    /**
     * Implementation of {@link NotificationListener} for JMX integration.
     * Delegates to {@link #notify(Notification)}.
     *
     * @param notification The notification
     * @param handback The handback object
     */
    @Override
    public void handleNotification(Notification notification, Object handback) {
        notify(notification);
    }

    /**
     * Closes this observer and releases resources.
     * After closing, no more notifications will be accepted.
     */
    @Override
    public void close() {
        closed.set(true);
        notifications.clear();
    }

    /**
     * Drains all received notifications from the internal queue.
     *
     * <p>Draining is done element by element so a notification delivered
     * concurrently is either returned to this caller or left queued for the
     * next one; it can never be removed without being handed back.</p>
     *
     * @return Array of all notifications received since last retrieval
     */
    public Notification[] getNotifications() {
        List<Notification> drained = new ArrayList<>();
        Notification notification;
        while ((notification = notifications.poll()) != null) {
            drained.add(notification);
        }
        return drained.toArray(new Notification[0]);
    }

    /**
     * Gets all received notifications without clearing the queue.
     *
     * @return Array of all notifications received
     */
    public Notification[] peekNotifications() {
        return notifications.toArray(new Notification[0]);
    }

    /**
     * Gets the total count of notifications received.
     *
     * @return Total notification count
     */
    public long getNotificationCount() {
        return notificationCount.get();
    }

    /**
     * Checks if this observer has been closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Gets the filter for this observer.
     *
     * @return The notification filter, or null if none
     */
    public NotificationFilter getFilter() {
        return filter;
    }

    /**
     * Gets the handback object for this observer.
     *
     * @return The handback object, or null if none
     */
    public Object getHandback() {
        return handback;
    }

    /**
     * Checks if there are any pending notifications.
     *
     * @return true if there are pending notifications
     */
    public boolean hasNotifications() {
        return !notifications.isEmpty();
    }

    /**
     * Clears all stored notifications without closing.
     */
    public void clearNotifications() {
        notifications.clear();
    }

    /**
     * Removes all stored notifications without closing.
     * Alias for clearNotifications().
     */
    public void removeAll() {
        clearNotifications();
    }

    /**
     * Waits for at least one notification to arrive, up to the specified timeout.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if at least one notification was received, false on timeout
     */
    public boolean waitForNotification(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (notifications.isEmpty() && System.currentTimeMillis() < deadline) {
            if (closed.get()) {
                return false;
            }
            Thread.sleep(10);
        }
        return !notifications.isEmpty();
    }

    /**
     * Gets the most recent notification without removing it.
     *
     * @return The most recent notification, or null if none
     */
    public Notification getLatestNotification() {
        Notification latest = null;
        for (Notification n : notifications) {
            latest = n;
        }
        return latest;
    }
}
