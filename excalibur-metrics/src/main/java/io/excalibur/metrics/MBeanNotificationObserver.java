package io.excalibur.metrics;

import javax.management.Notification;

/**
 * MBean notification observer interface.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe as notifications may be delivered
 * from multiple threads concurrently.</p>
 */
public interface MBeanNotificationObserver {

    void notify(Notification notification);

    void close();
}
