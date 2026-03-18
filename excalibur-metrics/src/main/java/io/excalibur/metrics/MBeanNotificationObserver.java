package io.excalibur.metrics;

import javax.management.Notification;

/**
 * MBean notification observer interface.
 */
public interface MBeanNotificationObserver {

    void notify(Notification notification);

    void close();
}
